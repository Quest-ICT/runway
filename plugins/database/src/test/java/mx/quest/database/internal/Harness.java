package mx.quest.database.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import mx.quest.database.spi.ConnectionSettings;
import mx.quest.database.spi.DatabaseConsumer;
import mx.quest.database.spi.DatabaseProvider;
import mx.quest.database.spi.ProviderSettings;
import mx.quest.database.spi.PluginDatabase;
import mx.quest.database.spi.Scope;
import mx.quest.runway.spi.model.BackupReader;
import mx.quest.runway.spi.model.BackupWriter;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.RequestContext;
import mx.quest.runway.spi.service.Sites;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import org.mariadb.jdbc.MariaDbDataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * One "node" of the database plugin wired against a real server, without Spring: the runtime, the schemas, the
 * migrator, the backup participant, test providers built the way the provider plugins build theirs, and fake sites.
 * The servers come from system properties ({@code db.it.postgresql}, {@code db.it.mariadb} as host:port, and
 * {@code db.it.password-file}); without them the tests that need a server are skipped.
 */
final class Harness implements AutoCloseable {

    static final String DATABASE = "runway";
    static final String USER = "runway";

    /** A server under test. */
    record Endpoint(String host, int port, String password) {
    }

    static Optional<Endpoint> endpoint(String engine) {
        String hostPort = System.getProperty("db.it." + engine);
        String file = System.getProperty("db.it.password-file");
        if (hostPort == null || file == null) {
            return Optional.empty();
        }
        try {
            String[] parts = hostPort.split(":");
            return Optional.of(new Endpoint(parts[0], Integer.parseInt(parts[1]),
                    Files.readString(Path.of(file)).strip()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The provider the provider plugins will be: their own DataSource, pinned properties, no DriverManager. */
    static DatabaseProvider provider(String engine) {
        return provider(engine, () -> null, () -> null);
    }

    /** A provider whose running and saved settings come from the suppliers ({@code null}: none, as before 2.0.0). */
    static DatabaseProvider provider(String engine, Supplier<ProviderSettings> running,
                                     Supplier<ProviderSettings> saved) {
        return new DatabaseProvider() {
            @Override
            public Optional<ProviderSettings> runningSettings() {
                return Optional.ofNullable(running.get());
            }

            @Override
            public Optional<ProviderSettings> savedSettings() {
                return Optional.ofNullable(saved.get());
            }

            @Override
            public String id() {
                return engine;
            }

            @Override
            public String driverVersion() {
                return "test";
            }

            @Override
            public DataSource dataSource(ConnectionSettings s) {
                if (engine.equals("postgresql")) {
                    PGSimpleDataSource ds = new PGSimpleDataSource();
                    ds.setServerNames(new String[] {s.host()});
                    ds.setPortNumbers(new int[] {s.portOr(5432)});
                    ds.setDatabaseName(s.database());
                    ds.setUser(s.username());
                    ds.setPassword(s.password());
                    ds.setSslMode("disable");
                    ds.setConnectTimeout((int) s.connectTimeout().toSeconds());
                    ds.setSocketTimeout((int) s.socketTimeout().toSeconds());
                    ds.setTcpKeepAlive(true);
                    return ds;
                }
                try {
                    // The properties database-mariadb's MariaDbProvider.url pins (no preserveInstants: see its test).
                    MariaDbDataSource ds = new MariaDbDataSource("jdbc:mariadb://" + s.host() + ":" + s.portOr(3306)
                            + "/" + s.database() + "?allowLocalInfile=false&allowMultiQueries=false"
                            + "&permitRedirect=false&allowPublicKeyRetrieval=false&connectionTimeZone=UTC"
                            + "&sslMode=disable&tcpKeepAlive=true&connectTimeout="
                            + s.connectTimeout().toMillis() + "&socketTimeout=" + s.socketTimeout().toMillis());
                    ds.setUser(s.username());
                    ds.setPassword(s.password());
                    return ds;
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    /** Sites and the current request's site. */
    static final class FakeSites implements Sites, RequestContext {
        final Path root;
        final Map<String, SiteView> sites = new ConcurrentHashMap<>();
        /** Ids find() answers as missing although they exist (a kernel that cannot read the folder and says so). */
        final Set<String> hidden = ConcurrentHashMap.newKeySet();
        /** Ids find() throws for (a kernel that says it cannot tell). */
        final Set<String> throwing = ConcurrentHashMap.newKeySet();
        volatile String current;

        FakeSites(Path root) {
            this.root = root;
        }

        void add(String id) throws IOException {
            Files.createDirectories(root.resolve(id));
            sites.put(id, new SiteView(id, id, Locale.ENGLISH, List.of(Locale.ENGLISH), "https://" + id));
        }

        void delete(String id) throws IOException {
            sites.remove(id);
            try (var walk = Files.walk(root.resolve(id))) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }

        @Override
        public Optional<SiteView> find(String siteId) {
            if (throwing.contains(siteId)) {
                throw new java.io.UncheckedIOException(new IOException("cannot read site " + siteId));
            }
            if (hidden.contains(siteId)) {
                return Optional.empty();
            }
            return Optional.ofNullable(sites.get(siteId));
        }

        @Override
        public Optional<SiteView> site() {
            return current == null ? Optional.empty() : find(current);
        }
    }

    /** Scripts held in memory, by entry name. */
    record MemoryScripts(String pluginId, Map<String, String> entries) implements ScriptSource {
        @Override
        public Optional<byte[]> read(String entry) {
            return Optional.ofNullable(entries.get(entry)).map(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    final String engine;
    final String namespace;
    final Endpoint endpoint;
    final Path work;
    final FakeSites sites;
    final TempDirSiteFiles files;
    final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
    final DatabaseProps props = new DatabaseProps();
    final List<DatabaseProvider> providers = new CopyOnWriteArrayList<>();
    final Map<String, ScriptSource> scripts = new ConcurrentHashMap<>();
    final DatabaseRuntime runtime;
    final SiteSchemas schemas;
    final Migrator migrator;
    final DatabaseCore core;
    final DatabasesImpl databases;
    final DatabaseBackup backup;
    // The provider's running server settings, as its settings page would hold them; tests change them freely.
    volatile String host;
    volatile String port;
    volatile String username;
    volatile String password;
    /** What the provider's settings page has saved now (the connection test's input); the running ones if null. */
    volatile ProviderSettings saved;

    Harness(String engine, String namespace, Endpoint endpoint, Path work) throws IOException {
        this(engine, namespace, endpoint, work, new FakeSites(work.resolve("sites")));
    }

    /** A second node: its own runtime and caches over the same sites and server. */
    Harness(String engine, String namespace, Endpoint endpoint, Path work, FakeSites sites) throws IOException {
        this.engine = engine;
        this.namespace = namespace;
        this.endpoint = endpoint;
        this.work = work;
        this.sites = sites;
        Files.createDirectories(sites.root);
        this.files = new TempDirSiteFiles(sites.root, "database");
        props.setProvider(engine);
        props.setNamespace(namespace);
        host = endpoint.host();
        port = String.valueOf(endpoint.port());
        username = USER;
        password = endpoint.password();
        providers.add(provider(engine, this::running, () -> saved != null ? saved : running()));
        runtime = new DatabaseRuntime(() -> ProviderChoice.running(props, List.copyOf(providers)),
                work.resolve("run-" + System.nanoTime()), executor);
        schemas = new SiteSchemas(runtime, files, sites);
        migrator = new Migrator(runtime);
        core = new DatabaseCore(runtime, schemas, migrator, sites, sites);
        databases = new DatabasesImpl(core, (cls, id) -> {
            ScriptSource s = scripts.get(id);
            if (s == null) {
                throw new IllegalArgumentException("not in the jar of plugin " + id);
            }
            return s;
        });
        backup = new DatabaseBackup(core, databases, files);
        migrator.parked(backup);
    }

    /** The running server settings from the fields above, TLS off as the test servers run. */
    ProviderSettings running() {
        return new ProviderSettings(host, port, DATABASE, username, password, "disable", null, null);
    }

    Harness start() throws InterruptedException {
        runtime.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!runtime.available() && System.nanoTime() < deadline
                && runtime.status() != DatabaseRuntime.Status.NOT_CONFIGURED
                && runtime.status() != DatabaseRuntime.Status.NO_PROVIDER) {
            Thread.sleep(50);
        }
        return this;
    }

    /** A consumer plugin with its scripts; returns its handle. */
    PluginDatabase consumer(String pluginId, Set<Scope.Kind> scopes, Map<String, String> entries) {
        scripts.put(pluginId, new MemoryScripts(pluginId, entries));
        return databases.of(new DatabaseConsumer() {
            @Override
            public String pluginId() {
                return pluginId;
            }

            @Override
            public Set<Scope.Kind> scopes() {
                return scopes;
            }
        });
    }

    Consumer registered(String pluginId) {
        return databases.consumers().stream().filter(c -> c.pluginId().equals(pluginId)).findFirst().orElseThrow();
    }

    /** A raw connection as the test account, outside every pool. */
    Connection raw() throws SQLException {
        ProviderSettings test = new ProviderSettings(endpoint.host(), String.valueOf(endpoint.port()), DATABASE, USER,
                endpoint.password(), "disable", null, null);
        return provider(engine).dataSource(DatabaseRuntime.settings(DatabaseConfig.from(props, engine, test), null,
                Duration.ofSeconds(30))).getConnection();
    }

    List<String> schemasInNamespace() throws SQLException {
        try (Connection c = raw()) {
            return runtime.engine() == null ? List.of()
                    : DatabaseRuntime.engineFor(engine).listSchemas(c, namespace);
        }
    }

    @Override
    public void close() throws SQLException {
        runtime.stop();
        executor.shutdownNow();
    }

    /** Drops every schema of a namespace. */
    static void dropNamespace(String engine, Endpoint endpoint, String namespace, Path work) throws Exception {
        Harness h = new Harness(engine, namespace, endpoint, work.resolve("cleanup"));
        Engine e = DatabaseRuntime.engineFor(engine);
        try (Connection c = h.raw()) {
            for (String s : e.listSchemas(c, namespace)) {
                e.dropSchema(c, s);
            }
        } finally {
            h.close();
        }
    }

    // --- an in-memory backup archive ---------------------------------------------------------------------------

    static class MemoryArchive implements BackupWriter, BackupReader {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        final List<String> order = new ArrayList<>();

        @Override
        public OutputStream put(String entry) {
            return new ByteArrayOutputStream() {
                @Override
                public void close() {
                    entries.put(entry, toByteArray());
                    order.add(entry);
                }
            };
        }

        @Override
        public boolean has(String entry) {
            return entries.containsKey(entry);
        }

        @Override
        public InputStream open(String entry) throws IOException {
            byte[] b = entries.get(entry);
            if (b == null) {
                throw new IOException("missing " + entry);
            }
            return new ByteArrayInputStream(b);
        }
    }
}
