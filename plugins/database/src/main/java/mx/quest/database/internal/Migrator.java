package mx.quest.database.internal;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import mx.quest.database.spi.DatabaseException;
import mx.quest.database.spi.DatabaseUnavailableException;
import mx.quest.database.spi.Scope;
import mx.quest.database.spi.ScopeNotReadyException;
import mx.quest.database.spi.ScopeNotReadyException.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brings one consumer plugin's tables in one schema to the version the installed plugin ships.
 *
 * <p><b>The lazy check is the authority.</b> The first call per (schema, plugin) in a JVM runs it, single-flight, on
 * the setup pool; later calls skip it. The startup sweep only warms it up.
 *
 * <ul>
 *   <li><b>PostgreSQL</b> runs all of a plugin's pending scripts in one transaction under a transaction-level
 *       advisory lock: a failure, or an object outside the plugin's prefix, rolls everything back.</li>
 *   <li><b>MariaDB</b> commits each DDL statement on its own, so it runs statement by statement under
 *       {@code GET_LOCK}: the script's row is written {@code failed} first and {@code last_ok_statement} moves after
 *       each statement; the ownership check runs after each statement, and an object a statement created outside
 *       the prefix is dropped at once. Nothing reruns a failed script from statement 1.</li>
 * </ul>
 *
 * A failure, a changed checksum or a schema newer than the shipped scripts stops <b>that plugin in that schema</b>
 * with a {@link ScopeNotReadyException}, recorded in {@code rw_issues} for the health screen, where an operator can
 * retry, mark the script applied, or accept the shipped checksum.
 */
public final class Migrator {

    private static final Logger log = LoggerFactory.getLogger(Migrator.class);

    static final String APPLIED = "applied";
    static final String FAILED = "failed";
    static final String RETRY = "retry";

    /** Issue states shown on the health screen. */
    public static final String ISSUE_FAILED = "FAILED";
    public static final String ISSUE_CHECKSUM = "CHECKSUM";
    public static final String ISSUE_NEWER = "NEWER";
    /** Data a restore kept for the plugin cannot be loaded; the plugin is refused on that site until it can. */
    public static final String ISSUE_PARKED = "PARKED";

    static final Duration WAIT = Duration.ofSeconds(10);
    static final Duration LOCK_WAIT = Duration.ofSeconds(60);
    static final Duration FAILURE_MEMORY = Duration.ofSeconds(30);

    /** A script the installed plugin ships. */
    public record Shipped(int version, String name, String checksum, List<String> statements) {
    }

    /** A row of {@code rw_migrations}. */
    record Applied(int version, String name, String checksum, String state, int lastOk) {
    }

    private record Failure(Reason reason, String message, long untilNanos) {
    }

    private final DatabaseRuntime runtime;
    private final Map<String, List<Shipped>> shipped = new ConcurrentHashMap<>();
    private final Set<String> verified = ConcurrentHashMap.newKeySet();
    private final Set<String> prefixesChecked = ConcurrentHashMap.newKeySet();
    private final Map<String, CompletableFuture<Void>> inflight = new ConcurrentHashMap<>();
    private final Map<String, Failure> failures = new ConcurrentHashMap<>();
    private volatile Parked parked;

    /** Loads rows into a schema whose plugin tables were just migrated to the data's version. */
    @FunctionalInterface
    public interface Loader {
        void load(String schema) throws SQLException, IOException;
    }

    /**
     * Data a restore parked in a site's folder because its plugin was not installed then. When that plugin first
     * migrates in the site's schema, the data is loaded exactly as a restore would: migrate to its version, load,
     * migrate forward.
     */
    public interface Parked {
        /**
         * The version of the plugin's parked data for a site, or empty when there is none.
         *
         * @throws IOException when there is parked data that cannot be read (never answered as "none")
         */
        Optional<Integer> version(String siteId, Consumer consumer) throws IOException;

        /**
         * Loads the parked data into a schema, then removes its manifest (the mark that it waits) and the rest of it
         * from the site folder.
         */
        void load(String siteId, Consumer consumer, String schema) throws SQLException, IOException;
    }

    public Migrator(DatabaseRuntime runtime) {
        this.runtime = runtime;
    }

    /** Sets where parked data comes from. */
    public void parked(Parked source) {
        this.parked = source;
    }

    private static String key(String schema, String pluginId) {
        return schema + "|" + pluginId;
    }

    /**
     * Makes sure the plugin's tables in the schema are at the shipped version, migrating if needed. Waits a bounded
     * time; a migration that takes longer carries on in the background.
     *
     * @throws ScopeNotReadyException      when the plugin cannot use this schema yet, or until an operator acts
     * @throws DatabaseUnavailableException when the database cannot be reached
     */
    public void ensure(Consumer consumer, Scope.Kind kind, String siteId, String schema) {
        String key = key(schema, consumer.pluginId());
        if (verified.contains(key)) {
            return;
        }
        Failure remembered = failures.get(key);
        if (remembered != null && System.nanoTime() < remembered.untilNanos()) {
            throw new ScopeNotReadyException(remembered.reason(), remembered.message());
        }
        CompletableFuture<Void> future = inflight.computeIfAbsent(key, k -> CompletableFuture.runAsync(
                () -> run(consumer, kind, siteId, schema, k), runtime.executor()));
        try {
            future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ScopeNotReadyException(Reason.PREPARING, "the plugin's tables are being prepared; try again "
                    + "shortly");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScopeNotReadyException(Reason.PREPARING, "interrupted while the tables were being prepared");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() instanceof CompletionException ce && ce.getCause() != null
                    ? ce.getCause() : e.getCause();
            if (cause instanceof DatabaseException de) {
                throw de;
            }
            throw new DatabaseException("the plugin's tables could not be prepared", cause);
        }
    }

    /**
     * The startup warm-up for one (schema, plugin): the same single-flight check, waited for without a time limit
     * and with every failure kept to the log, since the first call's check stays the authority.
     */
    public void warm(Consumer consumer, Scope.Kind kind, String siteId, String schema) {
        String key = key(schema, consumer.pluginId());
        if (verified.contains(key)) {
            return;
        }
        try {
            inflight.computeIfAbsent(key, k -> CompletableFuture.runAsync(
                    () -> run(consumer, kind, siteId, schema, k), runtime.executor())).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.debug("database: warm-up of plugin '{}' in schema '{}' did not finish: {}", consumer.pluginId(),
                    schema, e.getCause() == null ? e.toString() : e.getCause().getMessage());
        }
    }

    /** Runs the check on the executor; never on a request thread. */
    void run(Consumer consumer, Scope.Kind kind, String siteId, String schema, String key) {
        try {
            migrate(consumer, kind, siteId, schema);
            verified.add(key);
            failures.remove(key);
        } catch (ScopeNotReadyException e) {
            if (e.reason() != Reason.PREPARING) {
                failures.put(key, new Failure(e.reason(), e.getMessage(),
                        System.nanoTime() + FAILURE_MEMORY.toNanos()));
            }
            throw e;
        } catch (SQLException e) {
            if (DatabaseRuntime.isConnectionFailure(e)) {
                runtime.markOutage(e);
            }
            throw new DatabaseUnavailableException("the database failed while preparing the plugin's tables: "
                    + DatabaseRuntime.describe(e), e);
        } finally {
            inflight.remove(key);
        }
    }

    /** Forgets what is known about a schema (dropped, replaced, or after an operator action). */
    public void forget(String schema) {
        verified.removeIf(k -> k.startsWith(schema + "|"));
        failures.keySet().removeIf(k -> k.startsWith(schema + "|"));
    }

    // --- the shipped scripts -------------------------------------------------------------------------------------

    /** The scripts a plugin ships for the configured product and a scope, read once from its jar. */
    public List<Shipped> shipped(Consumer consumer, Scope.Kind kind) {
        String folder = "db/" + consumer.pluginId() + "/" + runtime.engine().id() + "/"
                + kind.name().toLowerCase(Locale.ROOT) + "/";
        return shipped.computeIfAbsent(consumer.pluginId() + "|" + folder, k -> load(consumer.scripts(), folder));
    }

    static List<Shipped> load(ScriptSource source, String folder) {
        try {
            Optional<byte[]> index = source.read(folder + "index");
            if (index.isEmpty()) {
                return List.of();
            }
            List<Shipped> scripts = new ArrayList<>();
            for (Scripts.Entry entry : Scripts.parseIndex(Scripts.normalize(index.get()))) {
                byte[] bytes = source.read(folder + entry.fileName()).orElseThrow(() -> new IllegalArgumentException(
                        "the index lists " + entry.fileName() + " but the jar does not hold it"));
                String text = Scripts.normalize(bytes);
                scripts.add(new Shipped(entry.version(), entry.fileName(), Scripts.checksum(text),
                        Scripts.split(text)));
            }
            return List.copyOf(scripts);
        } catch (IOException | IllegalArgumentException e) {
            throw new ScopeNotReadyException(Reason.PLUGIN_REFUSED, "plugin '" + source.pluginId()
                    + "' ships unusable migration scripts in " + folder + ": " + e.getMessage());
        }
    }

    // --- the prefix ----------------------------------------------------------------------------------------------

    /** Records the plugin's prefix the first time, refusing one that clashes with another plugin's. */
    void checkPrefix(Consumer consumer) throws SQLException {
        if (prefixesChecked.contains(consumer.pluginId())) {
            return;
        }
        String prefix = consumer.prefix();
        if (Names.isReservedPrefix(prefix)) {
            throw refused(consumer, "its prefix '" + prefix + "' starts with the reserved 'rw_'");
        }
        String problem = runtime.locks().global("plugins", Registry.GLOBAL_LOCK_WAIT, () -> {
            try (Connection c = runtime.setupConnection()) {
                c.setAutoCommit(true);
                Map<String, String> recorded = runtime.registry().prefixes(c);
                String own = recorded.get(consumer.pluginId());
                if (own != null) {
                    return own.equals(prefix) ? null : "the registry records the prefix '" + own + "', not '"
                            + prefix + "'";
                }
                for (Map.Entry<String, String> other : recorded.entrySet()) {
                    if (Names.prefixesClash(prefix, other.getValue())) {
                        return "its prefix '" + prefix + "' clashes with '" + other.getValue() + "' of plugin '"
                                + other.getKey() + "'";
                    }
                }
                runtime.registry().insertPrefix(c, consumer.pluginId(), prefix);
                return null;
            }
        });
        if (problem != null) {
            throw refused(consumer, problem);
        }
        prefixesChecked.add(consumer.pluginId());
    }

    private static ScopeNotReadyException refused(Consumer consumer, String why) {
        return new ScopeNotReadyException(Reason.PLUGIN_REFUSED, "plugin '" + consumer.pluginId()
                + "' cannot use the database: " + why);
    }

    // --- migrating -------------------------------------------------------------------------------------------

    /** Migrates, loading parked data first when the plugin is new to a site's schema and data waits for it. */
    void migrate(Consumer consumer, Scope.Kind kind, String siteId, String schema) throws SQLException {
        checkPrefix(consumer);
        List<Shipped> scripts = shipped(consumer, kind);
        Parked source = parked;
        if (kind == Scope.Kind.SITE && siteId != null && source != null) {
            Optional<Integer> version;
            try {
                version = source.version(siteId, consumer);
            } catch (IOException | RuntimeException e) {
                // Never "no parked data": migrating the tables empty would make the schema "migrated", and the kept
                // data would never be read again.
                throw recordIssue(schema, consumer, ISSUE_PARKED, 0, Reason.PLUGIN_REFUSED, "the data kept for "
                        + "plugin '" + consumer.pluginId() + "' from a restore (" + DatabaseBackup.PARKED
                        + consumer.pluginId() + "/ in the site's folder) cannot be read: " + DatabaseRuntime.describe(e)
                        + "; the plugin is refused on this site until it can");
            }
            if (version.isPresent()) {
                if (version.get() > scripts.size()) {
                    // Not skipped either: the folder stays until a plugin that can read it arrives.
                    throw recordIssue(schema, consumer, ISSUE_NEWER, version.get(), Reason.NEWER_THAN_INSTALLED,
                            "data kept for plugin '" + consumer.pluginId() + "' from a restore is at version "
                                    + version.get() + ", newer than the " + scripts.size() + " scripts the installed "
                                    + "plugin ships; install that version or later (the data is kept until then)");
                }
                int recorded = highestRecorded(consumer, schema);
                if (recorded > version.get()) {
                    throw recordIssue(schema, consumer, ISSUE_PARKED, version.get(), Reason.PLUGIN_REFUSED,
                            "data kept for plugin '" + consumer.pluginId() + "' from a restore is at version "
                                    + version.get() + ", but its tables in this schema are already at version "
                                    + recorded + "; restore the site's backup again, or remove " + DatabaseBackup.PARKED
                                    + consumer.pluginId() + "/ from the site's folder to go on without that data");
                }
                // First use, or a load that failed part way (the manifest goes only once the data is in): the loader
                // empties the plugin's tables before it loads.
                migrateWithData(consumer, kind, schema, version.get(), s -> source.load(siteId, consumer, s));
                return;
            }
        }
        migrateScripts(consumer, schema, scripts);
    }

    /**
     * Migrates a plugin to {@code version}, runs the loader, then migrates to the shipped version: what a restore
     * does in a new generation, and what parked data gets when its plugin arrives.
     */
    public void migrateWithData(Consumer consumer, Scope.Kind kind, String schema, int version, Loader loader)
            throws SQLException {
        checkPrefix(consumer);
        List<Shipped> scripts = shipped(consumer, kind);
        if (version > scripts.size()) {
            throw new ScopeNotReadyException(Reason.NEWER_THAN_INSTALLED, "the data is at version " + version
                    + " of plugin '" + consumer.pluginId() + "', newer than the installed plugin's " + scripts.size()
                    + "; install that version or later (the data is kept until then)");
        }
        migrateScripts(consumer, schema, scripts.subList(0, version));
        try {
            loader.load(schema);
        } catch (IOException e) {
            throw new SQLException("loading the data failed: " + e.getMessage(), e);
        }
        migrateScripts(consumer, schema, scripts);
        verified.add(key(schema, consumer.pluginId()));
    }

    /** The highest script version a plugin has a row for in a schema, in any state; 0 for none. */
    private int highestRecorded(Consumer consumer, String schema) throws SQLException {
        try (Connection c = runtime.setupConnection()) {
            c.setAutoCommit(true);
            return readApplied(c, schema, consumer.pluginId()).keySet().stream().mapToInt(Integer::intValue).max()
                    .orElse(0);
        }
    }

    /** Records a problem on the database screen, logs it, and returns the refusal to throw. */
    private ScopeNotReadyException recordIssue(String schema, Consumer consumer, String state, int version,
                                               Reason reason, String message) throws SQLException {
        log.warn("database: plugin '{}' in schema '{}': {}", consumer.pluginId(), schema, message);
        try (Connection c = runtime.setupConnection()) {
            c.setAutoCommit(true);
            return issue(c, schema, consumer, state, version, 0, reason, message);
        }
    }

    private void migrateScripts(Consumer consumer, String schema, List<Shipped> scripts) throws SQLException {
        String lockKey = "migrate:" + schema + ":" + consumer.pluginId();
        try (Connection c = runtime.setupConnection()) {
            try {
                if (runtime.engine().transactionalDdl()) {
                    migrateInTransaction(c, consumer, schema, scripts, lockKey);
                } else {
                    migrateStepwise(c, consumer, schema, scripts, lockKey);
                }
            } finally {
                c.setAutoCommit(true);
            }
        } catch (Locks.LockTimeoutException e) {
            throw new ScopeNotReadyException(Reason.PREPARING, "another node is migrating; try again shortly");
        }
    }

    /** PostgreSQL: everything in one transaction. */
    private void migrateInTransaction(Connection c, Consumer consumer, String schema, List<Shipped> scripts,
                                         String lockKey) throws SQLException {
        Engine engine = runtime.engine();
        c.setAutoCommit(false);
        boolean committed = false;
        try {
            if (!engine.migrationLock(c, lockKey, LOCK_WAIT)) {
                throw new Locks.LockTimeoutException(lockKey);
            }
            engine.bind(c, schema, DatabaseRuntime.LONG_TIMEOUT);
            Map<Integer, Applied> applied = readApplied(c, schema, consumer.pluginId());
            List<Shipped> pending = pending(c, consumer, schema, scripts, applied);
            if (pending.isEmpty()) {
                c.commit();
                committed = true;
                clearIssue(schema, consumer);
                return;
            }
            Set<Engine.DbObject> before = engine.ownedObjects(c, schema);
            for (int k = 0; k < pending.size(); k++) {
                Shipped script = pending.get(k);
                if (applied.containsKey(script.version())) {
                    deleteRow(c, schema, consumer.pluginId(), script.version()); // a RETRY: run it again whole
                }
                for (int i = 0; i < script.statements().size(); i++) {
                    try {
                        Jdbc.execute(c, script.statements().get(i));
                    } catch (SQLException e) {
                        c.rollback();
                        markRolledBack(c, schema, consumer, pending.subList(0, k));
                        throw fail(c, schema, consumer, script, i, "statement " + (i + 1) + " failed: "
                                + DatabaseRuntime.describe(e) + rolledBackNote(pending.subList(0, k)));
                    }
                    if (!engine.stillBound(c, schema)) {
                        c.rollback();
                        markRolledBack(c, schema, consumer, pending.subList(0, k));
                        throw fail(c, schema, consumer, script, i, "statement " + (i + 1)
                                + " moved the connection to another schema" + rolledBackNote(pending.subList(0, k)));
                    }
                }
                insertRow(c, schema, consumer.pluginId(), script, APPLIED, script.statements().size());
            }
            List<String> foreign = foreign(before, engine.ownedObjects(c, schema), consumer.prefix());
            if (!foreign.isEmpty()) {
                c.rollback();
                Shipped last = pending.get(pending.size() - 1);
                markRolledBack(c, schema, consumer, pending.subList(0, pending.size() - 1));
                throw fail(c, schema, consumer, last, 0, "the scripts touch objects outside the prefix '"
                        + consumer.prefix() + "': " + String.join(", ", foreign)
                        + rolledBackNote(pending.subList(0, pending.size() - 1)));
            }
            c.commit();
            committed = true;
            log.info("database: plugin '{}' in schema '{}' is at version {}", consumer.pluginId(), schema,
                    scripts.size());
            clearIssue(schema, consumer);
        } finally {
            if (!committed) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // the connection is closed next
                }
            }
        }
    }

    /** MariaDB: statement by statement, the script row written first. */
    private void migrateStepwise(Connection c, Consumer consumer, String schema, List<Shipped> scripts,
                                    String lockKey) throws SQLException {
        Engine engine = runtime.engine();
        c.setAutoCommit(true);
        engine.bind(c, schema, DatabaseRuntime.LONG_TIMEOUT);
        if (!engine.migrationLock(c, lockKey, LOCK_WAIT)) {
            throw new Locks.LockTimeoutException(lockKey);
        }
        try {
            Map<Integer, Applied> applied = readApplied(c, schema, consumer.pluginId());
            List<Shipped> pending = pending(c, consumer, schema, scripts, applied);
            if (pending.isEmpty()) {
                clearIssue(schema, consumer);
                return;
            }
            Set<Engine.DbObject> current = engine.ownedObjects(c, schema);
            Set<String> wrongEngine = new LinkedHashSet<>(engine.tablesWithWrongEngine(c, schema));
            for (Shipped script : pending) {
                Applied row = applied.get(script.version());
                int start = row != null && RETRY.equals(row.state()) ? row.lastOk() : 0;
                if (row == null) {
                    insertRow(c, schema, consumer.pluginId(), script, FAILED, 0);
                } else {
                    setState(c, schema, consumer.pluginId(), script.version(), FAILED);
                }
                for (int i = start; i < script.statements().size(); i++) {
                    try {
                        Jdbc.execute(c, script.statements().get(i));
                    } catch (SQLException e) {
                        throw fail(c, schema, consumer, script, i, "statement " + (i + 1) + " failed: "
                                + DatabaseRuntime.describe(e));
                    }
                    if (!engine.stillBound(c, schema)) {
                        engine.bind(c, schema, DatabaseRuntime.LONG_TIMEOUT);
                        throw fail(c, schema, consumer, script, i, "statement " + (i + 1)
                                + " moved the connection to another database");
                    }
                    Set<Engine.DbObject> after = engine.ownedObjects(c, schema);
                    List<Engine.DbObject> added = new ArrayList<>();
                    for (Engine.DbObject o : after) {
                        if (!current.contains(o) && !o.name().startsWith(consumer.prefix())) {
                            added.add(o);
                        }
                    }
                    List<String> removed = new ArrayList<>();
                    for (Engine.DbObject o : current) {
                        if (!after.contains(o) && !o.name().startsWith(consumer.prefix())) {
                            removed.add(o.kind() + " " + o.name());
                        }
                    }
                    List<String> newWrongEngine = new ArrayList<>();
                    for (String t : engine.tablesWithWrongEngine(c, schema)) {
                        if (!wrongEngine.contains(t)) {
                            newWrongEngine.add(t);
                        }
                    }
                    if (!added.isEmpty() || !removed.isEmpty() || !newWrongEngine.isEmpty()) {
                        for (Engine.DbObject o : added) {
                            engine.dropObject(c, schema, o);
                        }
                        for (String t : newWrongEngine) {
                            engine.dropObject(c, schema, new Engine.DbObject("table", t, null));
                        }
                        StringBuilder why = new StringBuilder("statement " + (i + 1));
                        if (!added.isEmpty()) {
                            why.append(" created objects outside the prefix '").append(consumer.prefix())
                                    .append("' (dropped again): ").append(names(added));
                        }
                        if (!removed.isEmpty()) {
                            why.append(" removed objects outside the prefix: ").append(String.join(", ", removed));
                        }
                        if (!newWrongEngine.isEmpty()) {
                            why.append(" created tables that are not InnoDB (dropped again): ")
                                    .append(String.join(", ", newWrongEngine));
                        }
                        throw fail(c, schema, consumer, script, i, why.toString());
                    }
                    current = after;
                    setLastOk(c, schema, consumer.pluginId(), script.version(), i + 1);
                }
                setState(c, schema, consumer.pluginId(), script.version(), APPLIED);
            }
            log.info("database: plugin '{}' in schema '{}' is at version {}", consumer.pluginId(), schema,
                    scripts.size());
            clearIssue(schema, consumer);
        } finally {
            try {
                engine.migrationUnlock(c, lockKey);
            } catch (SQLException e) {
                runtime.evictSetup(c);
            }
        }
    }

    /** What the applied rows allow: the scripts still to run, or a refusal. */
    private List<Shipped> pending(Connection c, Consumer consumer, String schema, List<Shipped> scripts,
                                  Map<Integer, Applied> applied) throws SQLException {
        int highest = 0;
        // A downgrade first: a schema ahead of the installed plugin explains any checksum difference too.
        for (Applied a : applied.values()) {
            if (a.version() > scripts.size()) {
                throw issue(c, schema, consumer, ISSUE_NEWER, a.version(), 0, Reason.NEWER_THAN_INSTALLED,
                        "the schema is at version " + a.version() + " of plugin '" + consumer.pluginId()
                                + "', newer than the " + scripts.size() + " scripts the installed plugin ships; "
                                + "install that version or later");
            }
        }
        List<Applied> failed = applied.values().stream().filter(a -> FAILED.equals(a.state()))
                .sorted(java.util.Comparator.comparingInt(Applied::version)).toList();
        if (!failed.isEmpty()) {
            // The highest failed script is the one that failed: on PostgreSQL the scripts of its batch below it were
            // rolled back with it and are recorded failed at statement 0, to run again.
            Applied worst = failed.get(failed.size() - 1);
            Optional<Registry.Issue> recorded = runtime.registry().issue(c, schema, consumer.pluginId());
            if (recorded.isPresent() && ISSUE_FAILED.equals(recorded.get().state())
                    && recorded.get().version() == worst.version()) {
                // Recorded when it failed, with the server's error: kept, not replaced by a vaguer one.
                throw new ScopeNotReadyException(Reason.MIGRATION_FAILED, recorded.get().detail());
            }
            List<Shipped> undone = failed.subList(0, failed.size() - 1).stream()
                    .map(a -> scripts.get(a.version() - 1)).toList();
            throw issue(c, schema, consumer, ISSUE_FAILED, worst.version(), worst.lastOk() + 1,
                    Reason.MIGRATION_FAILED, "script " + scripts.get(worst.version() - 1).name() + " of plugin '"
                            + consumer.pluginId() + "' failed at statement " + (worst.lastOk() + 1)
                            + rolledBackNote(undone) + "; retry it or mark it applied on the database screen");
        }
        for (Applied a : applied.values()) {
            Shipped s = scripts.get(a.version() - 1);
            if (APPLIED.equals(a.state()) && !a.checksum().equals(s.checksum())) {
                throw issue(c, schema, consumer, ISSUE_CHECKSUM, a.version(), 0, Reason.CHECKSUM_MISMATCH,
                        "script " + s.name() + " of plugin '" + consumer.pluginId() + "' changed after it was "
                                + "applied; released scripts must not change. Accept the shipped checksum on the "
                                + "database screen once the change is understood");
            }
            if (APPLIED.equals(a.state())) {
                highest = Math.max(highest, a.version());
            }
        }
        for (Shipped s : scripts) {
            if (s.version() < highest && !applied.containsKey(s.version())) {
                // A script below the highest applied one that never ran: running later scripts on top of the gap
                // would build on something that is not there.
                throw issue(c, schema, consumer, ISSUE_FAILED, s.version(), 0, Reason.MIGRATION_FAILED,
                        "script " + s.name() + " of plugin '" + consumer.pluginId() + "' was never applied, but a "
                                + "later one was; apply it by hand and then use Mark applied (Retry cannot run it), "
                                + "or restore the schema");
            }
        }
        List<Shipped> pending = new ArrayList<>();
        for (Shipped s : scripts) {
            Applied a = applied.get(s.version());
            if (s.version() > highest && (a == null || RETRY.equals(a.state()))) {
                pending.add(s);
            }
        }
        return pending;
    }

    private static String names(List<Engine.DbObject> objects) {
        List<String> out = new ArrayList<>();
        for (Engine.DbObject o : objects) {
            out.add(o.kind() + " " + o.name());
        }
        return String.join(", ", out);
    }

    /** Objects added or removed between two snapshots whose names lack the prefix. */
    static List<String> foreign(Set<Engine.DbObject> before, Set<Engine.DbObject> after, String prefix) {
        List<String> out = new ArrayList<>();
        for (Engine.DbObject o : after) {
            if (!before.contains(o) && !o.name().startsWith(prefix)) {
                out.add("created " + o.kind() + " " + o.name());
            }
        }
        for (Engine.DbObject o : before) {
            if (!after.contains(o) && !o.name().startsWith(prefix)) {
                out.add("removed " + o.kind() + " " + o.name());
            }
        }
        return out;
    }

    // --- failure records -------------------------------------------------------------------------------------

    /**
     * On the transactional engine one failure rolls back the whole batch: the scripts that ran before the failing
     * one are undone too. Each gets a {@code failed} row (no statement kept), so <b>Retry</b> runs them all again and
     * <b>Mark applied</b> covers them all, instead of the batch's earlier scripts being skipped for ever once the
     * failing one is marked.
     */
    private void markRolledBack(Connection c, String schema, Consumer consumer, List<Shipped> undone)
            throws SQLException {
        if (undone.isEmpty()) {
            return;
        }
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(true);
        Map<Integer, Applied> rows = readApplied(c, schema, consumer.pluginId());
        for (Shipped s : undone) {
            if (rows.containsKey(s.version())) {
                setState(c, schema, consumer.pluginId(), s.version(), FAILED);
                setLastOk(c, schema, consumer.pluginId(), s.version(), 0);
            } else {
                insertRow(c, schema, consumer.pluginId(), s, FAILED, 0);
            }
        }
        c.setAutoCommit(auto);
    }

    private static String rolledBackNote(List<Shipped> undone) {
        return undone.isEmpty() ? "" : "; rolled back with it, and to run again: "
                + undone.stream().map(Shipped::name).collect(java.util.stream.Collectors.joining(", "));
    }

    private ScopeNotReadyException fail(Connection c, String schema, Consumer consumer, Shipped script, int statement,
                                        String why) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(true);
        if (readApplied(c, schema, consumer.pluginId()).containsKey(script.version())) {
            setState(c, schema, consumer.pluginId(), script.version(), FAILED);
            setLastOk(c, schema, consumer.pluginId(), script.version(), statement);
        } else {
            insertRow(c, schema, consumer.pluginId(), script, FAILED, statement);
        }
        c.setAutoCommit(auto);
        log.warn("database: plugin '{}' in schema '{}': script {} {}", consumer.pluginId(), schema, script.name(), why);
        return issue(c, schema, consumer, ISSUE_FAILED, script.version(), statement + 1, Reason.MIGRATION_FAILED,
                "script " + script.name() + " of plugin '" + consumer.pluginId() + "' failed: " + why);
    }

    private ScopeNotReadyException issue(Connection c, String schema, Consumer consumer, String state, int version,
                                         int statement, Reason reason, String message) throws SQLException {
        boolean auto = c.getAutoCommit();
        if (!auto) {
            c.rollback();
            c.setAutoCommit(true);
        }
        runtime.registry().putIssue(c, schema, consumer.pluginId(), state, version, statement, message);
        c.setAutoCommit(auto);
        return new ScopeNotReadyException(reason, message);
    }

    private void clearIssue(String schema, Consumer consumer) throws SQLException {
        try (Connection c = runtime.setupConnection()) {
            c.setAutoCommit(true);
            runtime.registry().clearIssue(c, schema, consumer.pluginId());
        }
    }

    // --- rw_migrations ---------------------------------------------------------------------------------------

    private String table(String schema) {
        return runtime.engine().quote(schema) + ".rw_migrations";
    }

    Map<Integer, Applied> readApplied(Connection c, String schema, String pluginId) throws SQLException {
        Map<Integer, Applied> rows = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT version, name, checksum, state, last_ok_statement FROM "
                + table(schema) + " WHERE plugin_id = ? ORDER BY version")) {
            ps.setString(1, pluginId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.put(rs.getInt(1), new Applied(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getInt(5)));
                }
            }
        }
        return rows;
    }

    private void insertRow(Connection c, String schema, String pluginId, Shipped script, String state, int lastOk)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table(schema) + " (plugin_id, version, name, "
                + "checksum, state, last_ok_statement, applied_at) VALUES (?, ?, ?, ?, ?, ?, "
                + (APPLIED.equals(state) ? runtime.engine().nowSql() : "NULL") + ")")) {
            ps.setString(1, pluginId);
            ps.setInt(2, script.version());
            ps.setString(3, script.name());
            ps.setString(4, script.checksum());
            ps.setString(5, state);
            ps.setInt(6, lastOk);
            ps.executeUpdate();
        }
    }

    private void setState(Connection c, String schema, String pluginId, int version, String state)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + table(schema) + " SET state = ?, applied_at = "
                + (APPLIED.equals(state) ? runtime.engine().nowSql() : "applied_at")
                + " WHERE plugin_id = ? AND version = ?")) {
            ps.setString(1, state);
            ps.setString(2, pluginId);
            ps.setInt(3, version);
            ps.executeUpdate();
        }
    }

    private void setLastOk(Connection c, String schema, String pluginId, int version, int lastOk) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + table(schema) + " SET last_ok_statement = ? "
                + "WHERE plugin_id = ? AND version = ?")) {
            ps.setInt(1, lastOk);
            ps.setString(2, pluginId);
            ps.setInt(3, version);
            ps.executeUpdate();
        }
    }

    private void deleteRow(Connection c, String schema, String pluginId, int version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table(schema)
                + " WHERE plugin_id = ? AND version = ?")) {
            ps.setString(1, pluginId);
            ps.setInt(2, version);
            ps.executeUpdate();
        }
    }

    /** The highest applied version of a plugin in a schema (0 when none), read on a bound or unbound connection. */
    int appliedVersion(Connection c, String schema, String pluginId) throws SQLException {
        int highest = 0;
        for (Applied a : readApplied(c, schema, pluginId).values()) {
            if (APPLIED.equals(a.state())) {
                highest = Math.max(highest, a.version());
            }
        }
        return highest;
    }

    // --- operator recovery (audited by the caller) -----------------------------------------------------------

    /** The recovery actions on the health screen. */
    public enum Recovery {
        /** Run the failed script again from the statement after the last one that succeeded. */
        RETRY,
        /** Record the failed script as applied (the operator finished it by hand). */
        MARK_APPLIED,
        /** Record the shipped checksum for every applied script whose checksum changed. */
        ACCEPT_CHECKSUM
    }

    /** Applies a recovery action to one plugin in one schema. */
    public void recover(Consumer consumer, Scope.Kind kind, String schema, Recovery action) throws SQLException {
        List<Shipped> scripts = shipped(consumer, kind);
        try (Connection c = runtime.setupConnection()) {
            c.setAutoCommit(true);
            Map<Integer, Applied> applied = readApplied(c, schema, consumer.pluginId());
            for (Applied a : applied.values()) {
                switch (action) {
                    case RETRY -> {
                        if (FAILED.equals(a.state())) {
                            setState(c, schema, consumer.pluginId(), a.version(), RETRY);
                        }
                    }
                    case MARK_APPLIED -> {
                        if (FAILED.equals(a.state()) || RETRY.equals(a.state())) {
                            setState(c, schema, consumer.pluginId(), a.version(), APPLIED);
                        }
                    }
                    case ACCEPT_CHECKSUM -> {
                        if (APPLIED.equals(a.state()) && a.version() <= scripts.size()) {
                            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table(schema)
                                    + " SET checksum = ? WHERE plugin_id = ? AND version = ?")) {
                                ps.setString(1, scripts.get(a.version() - 1).checksum());
                                ps.setString(2, consumer.pluginId());
                                ps.setInt(3, a.version());
                                ps.executeUpdate();
                            }
                        }
                    }
                    default -> throw new IllegalArgumentException("unknown action " + action);
                }
            }
            if (action == Recovery.MARK_APPLIED) {
                // A script below the highest applied one that never ran (the gap refusal): the operator applied it.
                Map<Integer, Applied> now = readApplied(c, schema, consumer.pluginId());
                int highest = now.values().stream().filter(a -> APPLIED.equals(a.state()))
                        .mapToInt(Applied::version).max().orElse(0);
                for (Shipped s : scripts) {
                    if (s.version() < highest && !now.containsKey(s.version())) {
                        insertRow(c, schema, consumer.pluginId(), s, APPLIED, s.statements().size());
                    }
                }
            }
            runtime.registry().clearIssue(c, schema, consumer.pluginId());
        }
        String key = key(schema, consumer.pluginId());
        failures.remove(key);
        verified.remove(key);
    }
}
