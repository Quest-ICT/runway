package mx.quest.database.internal;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import mx.quest.database.spi.ConnectionSettings;
import mx.quest.database.spi.DatabaseProvider;
import mx.quest.database.spi.DatabaseUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The connection to the configured database: the settings, the engine, the pools and whether the database can be
 * used right now. <b>Runway never fails to start because of it</b>: nothing here connects on the caller's thread;
 * {@link #start()} hands a connect-with-back-off task to the plugin's executor, and {@link #available()} turns true
 * once the server has passed its checks and the registry exists.
 *
 * <p>Three ways to a connection, for three jobs:
 * <ul>
 *   <li>the <b>main pool</b> ({@code poolMax}) lends connections to consumers, with a 2-second borrow timeout and the
 *       consumer statement timeout;</li>
 *   <li>the <b>setup pool</b> (two connections, a long timeout) runs schema creation, registry writes, migrations,
 *       backup and restore, so none of that ever happens on a consumer's connection;</li>
 *   <li><b>lock connections</b> are opened unpooled from the provider's data source and closed after use, so a
 *       database lock is never held on a pooled connection and lock holders cannot starve either pool.</li>
 * </ul>
 */
public final class DatabaseRuntime {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRuntime.class);

    /** Where the runtime stands, for {@link #available()} and the health screen. */
    public enum Status {
        /** The settings are missing or unusable; fixed by saving the settings and restarting. */
        NOT_CONFIGURED,
        /** The chosen provider plugin is not installed (or two are). */
        NO_PROVIDER,
        /** Trying to reach the server. */
        CONNECTING,
        /** The server answered but is not supported. */
        UNSUPPORTED,
        /** Ready. */
        AVAILABLE,
        /** Shut down. */
        STOPPED
    }

    /** A snapshot for the health screen. Never holds a credential. */
    public record Health(Status status, String detail, String server, String driver, boolean outage,
                         Instant lastErrorAt, String lastError, int poolActive, int poolIdle, int poolMax) {
    }

    static final Duration BORROW_TIMEOUT = Duration.ofSeconds(2);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration LONG_TIMEOUT = Duration.ofMinutes(30);
    static final Duration HEARTBEAT = Duration.ofSeconds(60);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
    static final Duration UNSUPPORTED_RETRY = Duration.ofMinutes(5);
    private static final int LOCK_CONNECTIONS = 4;

    private final Supplier<DatabaseConfig> configSource;
    private final Supplier<List<DatabaseProvider>> providers;
    private final Path workDir;
    private final ScheduledExecutorService executor;
    private final Semaphore lockConnections = new Semaphore(LOCK_CONNECTIONS, true);
    private final List<Runnable> onAvailable = new CopyOnWriteArrayList<>();
    private final Locks locks = new Locks(this);

    private volatile Status status = Status.CONNECTING;
    private volatile String detail = "starting";
    private volatile String server;
    private volatile String driver;
    private volatile boolean outage;
    private volatile Instant lastErrorAt;
    private volatile String lastError;
    private volatile DatabaseConfig config;
    private volatile Engine engine;
    private volatile DataSource rawMain;
    private volatile DataSource rawSetup;
    private volatile HikariDataSource main;
    private volatile HikariDataSource setup;
    private volatile Registry registry;
    private long backoffMillis = 1000;

    /**
     * @param configSource builds the checked settings; throws {@link IllegalArgumentException} with a reason
     * @param providers    the provider beans installed
     * @param workDir      the plugin's own folder for the CA file
     * @param executor     the plugin's executor
     */
    public DatabaseRuntime(Supplier<DatabaseConfig> configSource, Supplier<List<DatabaseProvider>> providers,
                           Path workDir, ScheduledExecutorService executor) {
        this.configSource = Objects.requireNonNull(configSource, "configSource");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.workDir = Objects.requireNonNull(workDir, "workDir");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Hands the first connection attempt to the executor; never connects on the caller's thread. */
    public void start() {
        executor.execute(this::connect);
    }

    /** Runs {@code hook} on the executor each time the database becomes available (first time and after an outage). */
    public void onAvailable(Runnable hook) {
        onAvailable.add(hook);
    }

    /** Whether consumers can use the database now. */
    public boolean available() {
        return status == Status.AVAILABLE && !outage;
    }

    /** The current status. */
    public Status status() {
        return status;
    }

    /** The checked settings, or {@code null} when not configured. */
    public DatabaseConfig config() {
        return config;
    }

    /** The engine for the configured provider, or {@code null} when not configured. */
    public Engine engine() {
        return engine;
    }

    /** The registry, once available. */
    public Registry registry() {
        return registry;
    }

    /** The per-site and install-wide locks. */
    Locks locks() {
        return locks;
    }

    /** The plugin's own folder (the CA file, export spools). */
    Path workDir() {
        return workDir;
    }

    /** The executor. */
    ScheduledExecutorService executor() {
        return executor;
    }

    /** The health snapshot. */
    public Health health() {
        HikariDataSource pool = main;
        int active = 0;
        int idle = 0;
        if (pool != null && pool.getHikariPoolMXBean() != null) {
            active = pool.getHikariPoolMXBean().getActiveConnections();
            idle = pool.getHikariPoolMXBean().getIdleConnections();
        }
        DatabaseConfig c = config;
        return new Health(status, detail, server, driver, outage, lastErrorAt, lastError, active, idle,
                c == null ? 0 : c.poolMax());
    }

    // --- connecting ---------------------------------------------------------------------------------------------

    private void connect() {
        if (status == Status.STOPPED) {
            return;
        }
        if (config == null && !prepare()) {
            return; // settings or provider problem: fixed only by a restart
        }
        try (Connection c = rawSetup.getConnection()) {
            server = engine.inspectServer(c);
        } catch (UnsupportedServerException e) {
            fail(Status.UNSUPPORTED, e.getMessage(), null);
            schedule(UNSUPPORTED_RETRY);
            return;
        } catch (SQLException | RuntimeException e) {
            fail(Status.CONNECTING, describe(e), e);
            schedule(nextBackoff());
            return;
        }
        try {
            openPools();
            Registry r = new Registry(this);
            r.ensure();
            registry = r;
        } catch (SQLException | RuntimeException e) {
            fail(Status.CONNECTING, "the registry could not be prepared: " + describe(e), e);
            schedule(nextBackoff());
            return;
        }
        status = Status.AVAILABLE;
        detail = "available";
        outage = false;
        backoffMillis = 1000;
        log.info("database: connected to {} ({}), namespace '{}'", server, config.provider(), config.namespace());
        executor.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT.toMillis(), HEARTBEAT.toMillis(),
                TimeUnit.MILLISECONDS);
        fireAvailable();
    }

    /** Reads the settings and finds the provider; false (with the status set) when that cannot work. */
    private boolean prepare() {
        DatabaseConfig c;
        try {
            c = configSource.get();
        } catch (IllegalArgumentException e) {
            status = Status.NOT_CONFIGURED;
            detail = e.getMessage();
            log.info("database: not configured: {}", e.getMessage());
            return false;
        }
        List<DatabaseProvider> matching = providers.get().stream().filter(p -> c.provider().equals(p.id())).toList();
        if (matching.size() != 1) {
            status = Status.NO_PROVIDER;
            detail = matching.isEmpty()
                    ? "the provider plugin for '" + c.provider() + "' (database-" + c.provider() + ") is not installed"
                    : "two provider plugins offer '" + c.provider() + "'; remove one";
            log.warn("database: {}", detail);
            return false;
        }
        DatabaseProvider provider = matching.get(0);
        Engine e = engineFor(c.provider());
        Path ca;
        try {
            ca = writeCa(workDir, c.caCertificate());
        } catch (IOException ex) {
            status = Status.NOT_CONFIGURED;
            detail = "the CA certificate could not be written to the plugin folder: " + ex.getClass().getSimpleName();
            log.warn("database: {}", detail);
            return false;
        }
        driver = provider.driverVersion();
        engine = e;
        rawMain = provider.dataSource(settings(c, ca, c.statementTimeout().plusSeconds(30)));
        rawSetup = provider.dataSource(settings(c, ca, LONG_TIMEOUT.plusSeconds(60)));
        config = c;
        return true;
    }

    /** The engine that has SQL for a provider id. */
    public static Engine engineFor(String provider) {
        return switch (provider) {
            case "postgresql" -> new PostgresEngine();
            case "mariadb" -> new MariaDbEngine();
            default -> throw new IllegalArgumentException("unknown provider: " + provider);
        };
    }

    /** Connection settings for one pool. */
    static ConnectionSettings settings(DatabaseConfig c, Path ca, Duration socketTimeout) {
        return new ConnectionSettings(c.host(), c.port(), c.database(), c.username(), c.password(), c.tls(), ca,
                CONNECT_TIMEOUT, socketTimeout);
    }

    /** Writes the CA certificate to an owner-only file named by its hash; {@code null} when there is none. */
    static Path writeCa(Path workDir, String pem) throws IOException {
        if (pem == null) {
            return null;
        }
        Files.createDirectories(workDir);
        Path target = workDir.resolve("ca-" + Names.sha256Hex(pem).substring(0, 16) + ".pem");
        if (Files.isRegularFile(target)) {
            return target;
        }
        Path tmp = Files.createTempFile(workDir, "ca-", ".tmp");
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            }
            Files.writeString(tmp, pem, StandardCharsets.US_ASCII);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    private void openPools() {
        if (main != null) {
            return;
        }
        DatabaseConfig c = config;
        HikariConfig m = new HikariConfig();
        m.setPoolName("runway-database");
        m.setDataSource(rawMain);
        m.setMaximumPoolSize(c.poolMax());
        m.setMinimumIdle(Math.min(2, c.poolMax()));
        m.setConnectionTimeout(BORROW_TIMEOUT.toMillis());
        m.setValidationTimeout(1000);
        m.setInitializationFailTimeout(-1);
        m.setKeepaliveTime(Duration.ofMinutes(5).toMillis());
        m.setIdleTimeout(Duration.ofMinutes(10).toMillis());
        m.setMaxLifetime(Duration.ofMinutes(30).toMillis());
        m.setConnectionInitSql(engine.connectionInitSql(c.statementTimeout(), c.collation()));
        HikariConfig s = new HikariConfig();
        s.setPoolName("runway-database-setup");
        s.setDataSource(rawSetup);
        s.setMaximumPoolSize(2);
        s.setMinimumIdle(0);
        s.setConnectionTimeout(Duration.ofSeconds(30).toMillis());
        s.setValidationTimeout(1000);
        s.setInitializationFailTimeout(-1);
        s.setIdleTimeout(Duration.ofMinutes(2).toMillis());
        s.setMaxLifetime(Duration.ofMinutes(30).toMillis());
        s.setConnectionInitSql(engine.connectionInitSql(LONG_TIMEOUT, c.collation()));
        main = new HikariDataSource(m);
        setup = new HikariDataSource(s);
    }

    /** How soon after an outage is marked the server is tried again, once, before the regular heartbeat. */
    static final Duration OUTAGE_RECHECK = Duration.ofSeconds(2);

    /**
     * One heartbeat: records this node and ends an outage once the server answers. It uses a connection of its own,
     * not the setup pool, so a pool that is only busy (a long restore, a migration) is never taken for an outage.
     * Package-visible for tests.
     */
    void heartbeat() {
        Registry r = registry;
        DataSource raw = rawSetup;
        if (r == null || raw == null || status != Status.AVAILABLE) {
            return;
        }
        Connection c;
        try {
            c = raw.getConnection();
        } catch (SQLException | RuntimeException e) {
            markOutage(e);
            return;
        }
        try (c) {
            c.setAutoCommit(true);
            r.heartbeat(c);
        } catch (SQLException e) {
            if (isConnectionFailure(e)) {
                markOutage(e);
                return;
            }
            log.warn("database: could not record this node's heartbeat: {}", describe(e));
        } catch (RuntimeException e) {
            log.warn("database: could not record this node's heartbeat: {}", describe(e));
        }
        if (outage) {
            outage = false;
            log.info("database: reachable again");
            fireAvailable();
        }
    }

    private void fireAvailable() {
        for (Runnable hook : onAvailable) {
            executor.execute(() -> {
                try {
                    hook.run();
                } catch (RuntimeException e) {
                    log.warn("database: a task after connecting failed: {}", describe(e));
                }
            });
        }
    }

    private void fail(Status s, String why, Throwable cause) {
        status = s;
        detail = why;
        lastError = why;
        lastErrorAt = Instant.now();
        log.warn("database: {}{}", why, cause == null ? "" : " (retrying)");
    }

    private void schedule(Duration delay) {
        if (status != Status.STOPPED) {
            executor.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private Duration nextBackoff() {
        long now = backoffMillis;
        backoffMillis = Math.min(MAX_BACKOFF.toMillis(), backoffMillis * 2);
        return Duration.ofMillis(now);
    }

    /** Records a failure to reach the server after it was available. */
    void markOutage(Exception e) {
        if (!outage) {
            log.warn("database: unreachable: {}", describe(e));
            try {
                // One early re-check, so a blip ends in seconds rather than at the next minute's heartbeat.
                executor.schedule(this::heartbeat, OUTAGE_RECHECK.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException stopping) {
                // shutting down
            }
        }
        outage = true;
        lastError = describe(e);
        lastErrorAt = Instant.now();
    }

    /** A one-line description without credentials: the exception type, the SQL state and the driver message. */
    public static String describe(Throwable e) {
        StringBuilder b = new StringBuilder(e.getClass().getSimpleName());
        if (e instanceof SQLException sql && sql.getSQLState() != null) {
            b.append(" [").append(sql.getSQLState()).append(']');
        }
        if (e.getMessage() != null) {
            String msg = e.getMessage().replaceAll("[\\r\\n]+", " ");
            b.append(": ").append(msg.length() > 300 ? msg.substring(0, 300) + "…" : msg);
        }
        return b.toString();
    }

    // --- connections ----------------------------------------------------------------------------------------------

    private void requireAvailable() {
        if (status != Status.AVAILABLE) {
            throw new DatabaseUnavailableException("the database is not available: " + detail);
        }
    }

    /**
     * A consumer connection from the main pool. A failure to reach the server marks an outage; a pool that is merely
     * full (every connection in use for the whole borrow timeout) does not: it fails this call only, since marking an
     * outage would refuse every consumer on every site until the next heartbeat.
     */
    Connection borrow() {
        requireAvailable();
        try {
            return main.getConnection();
        } catch (SQLTransientConnectionException e) {
            if (e.getCause() == null) {
                // HikariCP attaches the last failed connection attempt as the cause; none means the pool is full.
                lastError = describe(e);
                lastErrorAt = Instant.now();
                throw new DatabaseUnavailableException("too many database calls in flight; try again shortly", e);
            }
            markOutage(e);
            throw new DatabaseUnavailableException("the database is not reachable", e);
        } catch (SQLException e) {
            markOutage(e);
            throw new DatabaseUnavailableException("the database is not reachable", e);
        }
    }

    /**
     * Whether a failure says the server cannot be reached (SQLState class {@code 08}, a connection-class exception, or
     * a pool timeout caused by a failed connection attempt), as opposed to a statement the server refused.
     */
    static boolean isConnectionFailure(SQLException e) {
        String state = e.getSQLState();
        return (state != null && state.startsWith("08")) || e instanceof SQLNonTransientConnectionException
                || e instanceof SQLRecoverableException
                || (e instanceof SQLTransientConnectionException && e.getCause() != null);
    }

    /** A connection from the setup pool. */
    Connection setupConnection() throws SQLException {
        HikariDataSource s = setup;
        if (s == null) {
            throw new SQLException("the database is not connected yet");
        }
        return s.getConnection();
    }

    /** Throws a consumer connection away instead of returning it to its pool (a failed bind or reset). */
    void evict(Connection c) {
        try {
            main.evictConnection(c);
        } catch (RuntimeException e) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // already broken
            }
        }
    }

    /** Throws a setup connection away. */
    void evictSetup(Connection c) {
        try {
            setup.evictConnection(c);
        } catch (RuntimeException e) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // already broken
            }
        }
    }

    /**
     * An unpooled connection for a database lock, waiting at most {@code wait} for one of the few lock slots. Hand it
     * back with {@link #closeLockConnection}, which frees the slot.
     *
     * @return {@code null} when no slot came free in time
     */
    Connection openLockConnection(Duration wait) throws SQLException {
        try {
            if (!lockConnections.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            Connection c = rawSetup.getConnection();
            c.setAutoCommit(true);
            return c;
        } catch (SQLException | RuntimeException e) {
            lockConnections.release();
            throw e;
        }
    }

    /** Closes a lock connection (which releases any lock still on it) and frees its slot. */
    void closeLockConnection(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // the server releases session locks when the connection goes away
        } finally {
            lockConnections.release();
        }
    }

    /** Stops the runtime and closes the pools. */
    public void stop() {
        status = Status.STOPPED;
        detail = "stopped";
        HikariDataSource m = main;
        HikariDataSource s = setup;
        if (m != null) {
            m.close();
        }
        if (s != null) {
            s.close();
        }
    }
}
