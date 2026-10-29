package mx.quest.database.internal;

import java.io.IOException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import mx.quest.database.spi.DatabaseUnavailableException;
import mx.quest.database.spi.ScopeNotReadyException;
import mx.quest.runway.spi.service.PluginSiteFiles;
import mx.quest.runway.spi.service.Sites;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which schema holds a site's data, and its life: allocation the first time a site uses the database, retirement
 * when the site is deleted or the mapping turns out to belong to an earlier site with the same id, and removal.
 *
 * <p><b>The incarnation token.</b> Allocation records the mapping with a random token and then writes the same token,
 * with the site id it was issued for, to the site's own folder ({@value #TOKEN_FILE}); every resolution compares the
 * two. What the folder says decides:
 * <ul>
 *   <li><b>the same token</b>: the mapping is the site's;</li>
 *   <li><b>a token issued for another site id, or the {@code fresh} marker</b>: the folder came with another site's
 *       backup, or the site is new. It must never bind to the data the registry has for the id, so that mapping is
 *       retired and a new generation is allocated. Creating a site retires such data at once
 *       ({@link #retireEarlierSite}), or marks the folder when the database cannot be reached;</li>
 *   <li><b>another token of this site, or the {@code adopt} marker</b>: a restore put back a folder from another
 *       moment of the same site (a backup carries the token of the generation that was live when it was taken). The
 *       registry is the authority: the live mapping's token is written back and its data stays. Retiring here would
 *       lose a site's data to an ordinary restore, or to one that failed half way;</li>
 *   <li><b>no token file</b> with live data mapped: a restore whose first step has not run yet, or a site re-created
 *       while the plugin was off. Neither binding nor retiring is safe, so resolution answers "being prepared"; the
 *       restore writes the token back, or the sweep retires the data after finding no token on two passes;</li>
 *   <li><b>a folder that cannot be read</b>: nothing is decided and nothing is changed; the call fails as
 *       unavailable and the sweep skips the site. "Cannot tell" is never taken for "absent".</li>
 * </ul>
 * The registry is changed first and the token written after, so a failure between the two leaves a folder that does
 * not name the new mapping, which is then retired (allocation) or adopted (a restore's switch), never an old mapping
 * named by a new folder.
 *
 * <p><b>Caching.</b> A resolved mapping is cached per node; every borrow re-checks it against the registry in the
 * same round trip that binds the connection ({@link Engine#bindSite}), so a mapping changed on another node is
 * noticed at once.
 */
public final class SiteSchemas {

    private static final Logger log = LoggerFactory.getLogger(SiteSchemas.class);

    /** The token file in the site's database folder; declared personal, so transfer copies leave it out. */
    public static final String TOKEN_FILE = "schema.yml";
    /** The line a restore writes when it cannot reach the database: adopt whatever the registry has live. */
    static final String ADOPT_LINE = "adopt: live";
    /**
     * The line written when a site is created (or restored from a backup without database data) while the database
     * cannot be reached: whatever the registry has for this id belongs to an earlier site, never to this one.
     */
    static final String FRESH_LINE = "fresh: new site";

    static final Duration PREPARE_WAIT = Duration.ofSeconds(10);
    static final Duration DROP_WAIT = Duration.ofSeconds(60);
    static final Duration RETIRED_GRACE = Duration.ofMinutes(10);

    static final String CREATING = "creating";
    static final String LIVE = "live";
    static final String STAGING = "staging";
    static final String RETIRED = "retired";
    static final String DROPPING = "dropping";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DatabaseRuntime runtime;
    private final PluginSiteFiles files;
    private final Sites sites;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    /** Sites whose folder had no token on the sweep's last pass (with live data mapped). */
    private final Set<String> absentLastSweep = ConcurrentHashMap.newKeySet();
    /** A test seam: runs after the sweep has read the registry and before it acts on what it read. */
    volatile Runnable afterSweepSnapshot = () -> { };

    public SiteSchemas(DatabaseRuntime runtime, PluginSiteFiles files, Sites sites) {
        this.runtime = runtime;
        this.files = files;
        this.sites = sites;
    }

    /**
     * The live schema for a site, allocating it the first time.
     *
     * @throws ScopeNotReadyException when another node or thread is preparing the site's data for too long
     */
    public String resolve(String siteId) {
        String cached = cache.get(siteId);
        if (cached != null) {
            return cached;
        }
        Folder folder = folderOrUnavailable(siteId);
        Optional<Registry.SiteRow> row;
        try (Connection c = runtime.borrow()) {
            row = runtime.registry().site(c, siteId);
        } catch (SQLException e) {
            if (DatabaseRuntime.isConnectionFailure(e)) {
                runtime.markOutage(e);
            }
            throw new DatabaseUnavailableException("the database is not reachable", e);
        }
        if (row.isPresent() && LIVE.equals(row.get().state()) && folder.names(row.get())) {
            cache.put(siteId, row.get().schema());
            return row.get().schema();
        }
        return allocate(siteId);
    }

    /** Forgets a cached mapping (a failed bind, a site event, a restore). */
    public void evict(String siteId) {
        cache.remove(siteId);
    }

    /** Forgets every cached mapping. */
    void evictAll() {
        cache.clear();
    }

    private String allocate(String siteId) {
        try {
            String schema = runtime.locks().site(siteId, PREPARE_WAIT, () -> allocateLocked(siteId));
            cache.put(siteId, schema);
            return schema;
        } catch (Locks.LockTimeoutException e) {
            throw new ScopeNotReadyException(ScopeNotReadyException.Reason.PREPARING,
                    "site data is being prepared; try again shortly");
        } catch (SQLException e) {
            if (DatabaseRuntime.isConnectionFailure(e)) {
                runtime.markOutage(e); // not for a busy setup pool or a refused statement: those fail this call only
            }
            throw new DatabaseUnavailableException("the site's schema could not be prepared: "
                    + DatabaseRuntime.describe(e), e);
        } catch (UnreadableFolderException e) {
            throw unavailable(e);
        }
    }

    private String allocateLocked(String siteId) throws SQLException {
        Registry registry = runtime.registry();
        Engine engine = runtime.engine();
        String ns = runtime.config().namespace();
        try (Connection c = runtime.setupConnection()) {
            c.setAutoCommit(true);
            Folder folder = folder(siteId);
            Optional<Registry.SiteRow> row = registry.site(c, siteId);
            if (row.isPresent() && folder.names(row.get())) {
                Registry.SiteRow r = row.get();
                if (LIVE.equals(r.state())) {
                    return r.schema();
                }
                if (CREATING.equals(r.state())) {
                    create(c, siteId, r.schema()); // finish what a crash interrupted
                    return r.schema();
                }
            }
            if (row.isPresent() && LIVE.equals(row.get().state()) && folder.adoptable()) {
                adopt(siteId, row.get(), folder);
                return row.get().schema();
            }
            if (row.isPresent() && LIVE.equals(row.get().state()) && folder.state() == FolderState.ABSENT) {
                // No token at all: a restore whose first step has not run yet (it writes the token back), or a site
                // re-created while the plugin was off. Binding could hand an earlier site's data to a new one, and
                // retiring could lose a restored site's live data: neither is done here. The restore settles it, or
                // the sweep retires the data when it finds the folder still empty on its next pass.
                throw new ScopeNotReadyException(ScopeNotReadyException.Reason.PREPARING,
                        "site data is being prepared; try again shortly");
            }
            if (sites.find(siteId).isEmpty()) {
                throw new IllegalArgumentException("no site with id " + siteId);
            }
            String fresh = newToken();
            long generation = registry.nextGeneration(c);
            String schema = Names.requireOwnSchema(ns, Names.siteSchema(ns, siteId, generation,
                    engine.identifierMax()), engine.identifierMax());
            c.setAutoCommit(false);
            try {
                if (row.isPresent()) {
                    // The folder says the data is not this site's (an earlier site with this id, a token copied from
                    // another site, or a crashed allocation). Never bind to it; retire it in the same transaction
                    // that records the new generation.
                    log.info("database: site '{}' has a schema '{}' from an earlier site with the same id; retiring "
                            + "it", siteId, row.get().schema());
                    registry.deleteSite(c, siteId);
                    registry.schemaState(c, row.get().schema(), RETIRED);
                }
                registry.insertSchema(c, schema, siteId, generation, CREATING);
                registry.putSite(c, siteId, schema, fresh, generation, CREATING);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
            // After the commit: if the folder cannot take it (the site was just deleted), the folder names nothing,
            // the CREATING row is retired on the next use and the sweep drops it.
            writeToken(siteId, fresh);
            create(c, siteId, schema);
            log.info("database: site '{}' now keeps its data in schema '{}'", siteId, schema);
            return schema;
        }
    }

    /** Creates a recorded schema and its migrations table, then marks it live. */
    private void create(Connection c, String siteId, String schema) throws SQLException {
        Engine engine = runtime.engine();
        Names.requireOwnSchema(runtime.config().namespace(), schema, engine.identifierMax());
        engine.createSchema(c, schema, runtime.config().collation());
        Jdbc.execute(c, engine.migrationsDdl(schema));
        c.setAutoCommit(false);
        try {
            runtime.registry().schemaState(c, schema, LIVE);
            runtime.registry().siteState(c, siteId, schema, LIVE);
            c.commit();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    /** Removes a site's mapping and marks its schema retired, in one transaction; the sweep drops it later. */
    void retire(Connection c, String siteId, String schema) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            runtime.registry().deleteSite(c, siteId);
            runtime.registry().schemaState(c, schema, RETIRED);
            c.commit();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
        evict(siteId);
    }

    // --- staging generations (restore) -----------------------------------------------------------------------------

    /** A new, empty generation for a site that no reader resolves to; the caller holds the site lock. */
    String createStaging(String siteId) throws SQLException {
        Engine engine = runtime.engine();
        String ns = runtime.config().namespace();
        try (Connection c = runtime.setupConnection()) {
            c.setAutoCommit(true);
            long generation = runtime.registry().nextGeneration(c);
            String schema = Names.requireOwnSchema(ns, Names.siteSchema(ns, siteId, generation,
                    engine.identifierMax()), engine.identifierMax());
            runtime.registry().insertSchema(c, schema, siteId, generation, STAGING);
            engine.createSchema(c, schema, runtime.config().collation());
            Jdbc.execute(c, engine.migrationsDdl(schema));
            return schema;
        }
    }

    /** The generation number recorded for a schema. */
    long generationOf(Connection c, String schema) throws SQLException {
        return runtime.registry().schemas(c).rows().stream().filter(r -> r.schema().equals(schema))
                .mapToLong(Registry.SchemaRow::generation).findFirst()
                .orElseThrow(() -> new SQLException("schema not recorded: " + schema));
    }

    /**
     * Makes a staging generation the site's live one: one transaction points the registry at it and retires the
     * previous generation, then the fresh token goes to the site folder. If that write fails, the folder still names
     * the previous generation, and the next resolution adopts the registry's (the restored data). The caller holds the
     * site lock.
     */
    void switchTo(String siteId, String staging) throws SQLException {
        Registry registry = runtime.registry();
        String fresh = newToken();
        try (Connection c = runtime.setupConnection()) {
            c.setAutoCommit(true);
            Optional<Registry.SiteRow> previous = registry.site(c, siteId);
            long generation = generationOf(c, staging);
            c.setAutoCommit(false);
            try {
                if (previous.isPresent()) {
                    registry.schemaState(c, previous.get().schema(), RETIRED);
                }
                registry.putSite(c, siteId, staging, fresh, generation, LIVE);
                registry.schemaState(c, staging, LIVE);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
        evict(siteId);
        try {
            writeToken(siteId, fresh);
        } catch (RuntimeException e) {
            log.warn("database: site '{}' now uses schema '{}', but its folder could not take the new token; it is "
                    + "written on the next use: {}", siteId, staging, DatabaseRuntime.describe(e));
        }
    }

    /**
     * The first step of a restore, after the kernel has swapped in the archive's folder: makes the folder name the
     * live generation again, so whatever the rest of the restore does (finish, fail, or find no database data in the
     * archive), the site keeps the data it had until a restore actually replaces it. With the database unreachable, a
     * folder left without a token gets the {@code adopt} marker instead, which the next resolution honours.
     *
     * @param wait how long to wait for the site lock
     * @throws UnreadableFolderException when the folder cannot be read (the restore then stops; nothing was changed)
     */
    void keepLiveDataAcrossRestore(String siteId, Duration wait) {
        Folder folder = folder(siteId);
        if (runtime.available()) {
            try {
                runtime.locks().site(siteId, wait, () -> {
                    try (Connection c = runtime.setupConnection()) {
                        c.setAutoCommit(true);
                        Optional<Registry.SiteRow> row = runtime.registry().site(c, siteId);
                        Folder now = folder(siteId);
                        if (row.isPresent() && LIVE.equals(row.get().state()) && !now.names(row.get())) {
                            if (now.state() == FolderState.ABSENT || now.adoptable()) {
                                adopt(siteId, row.get(), now);
                            } else {
                                retire(c, siteId, row.get().schema()); // a token another site was given, or new site
                            }
                        }
                    }
                    return null;
                });
                return;
            } catch (SQLException | RuntimeException e) {
                log.warn("database: could not re-check the live data of restored site '{}': {}", siteId,
                        DatabaseRuntime.describe(e));
            }
        }
        if (folder.state() == FolderState.ABSENT) {
            writeFile(siteId, "# Written by the database plugin during a restore: keep the site's live data.\n"
                    + ADOPT_LINE + "\n");
        }
    }

    /** Marks the site's folder so that whatever the registry has for it is retired on its next use. */
    void markFresh(String siteId) {
        writeFile(siteId, "# Written by the database plugin: none of the data the database has for this id is this "
                + "site's.\n" + FRESH_LINE + "\n");
    }

    /** Whether the registry maps the site to this schema, live (read on a fresh connection). */
    boolean isLive(String siteId, String schema) throws SQLException {
        try (Connection c = runtime.setupConnection()) {
            c.setAutoCommit(true);
            Optional<Registry.SiteRow> row = runtime.registry().site(c, siteId);
            return row.isPresent() && row.get().schema().equals(schema) && LIVE.equals(row.get().state());
        }
    }

    /** Writes the registry's token back to a folder that names another generation of the same site. */
    private void adopt(String siteId, Registry.SiteRow row, Folder folder) {
        writeToken(siteId, row.token());
        evict(siteId);
        log.info("database: site '{}' {}; keeping its live data in schema '{}'", siteId,
                folder.state() == FolderState.ADOPT ? "was restored while the database was unreachable"
                        : "has a restored folder that names another generation of its data", row.schema());
    }

    /** Drops a staging generation a failed restore leaves behind; if that fails, the sweep drops it. */
    void abandonStaging(String staging) {
        try (Connection c = runtime.setupConnection()) {
            c.setAutoCommit(true);
            // Only a schema still staging and mapped by no site: a switch that committed and then reported an error
            // has made it the site's live data.
            if (runtime.registry().schemaStateIfUnmapped(c, staging, STAGING, RETIRED)) {
                dropRecorded(c, staging);
            } else {
                log.warn("database: schema '{}' is no longer a staging generation; it is not dropped", staging);
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("database: could not drop the abandoned schema '{}' now; the sweep will: {}", staging,
                    DatabaseRuntime.describe(e));
        }
    }

    /**
     * Whether the site's folder says it has database data (a token, or the adopt marker).
     *
     * @throws IOException when the folder cannot be read: a caller must not take that for "no data" (a backup would
     *                     then record the site as having none, and restoring it would retire the data)
     */
    public boolean hasToken(String siteId) throws IOException {
        try {
            FolderState state = folder(siteId).state();
            return state == FolderState.TOKEN || state == FolderState.ADOPT;
        } catch (UnreadableFolderException e) {
            throw new IOException(e.getMessage(), e.getCause());
        }
    }

    // --- removal ----------------------------------------------------------------------------------------------------

    /**
     * After a site is deleted (on the node that deleted it): retires and drops its schema unless the site exists
     * again with a token that matches. Runs on the executor.
     */
    public void dropDeleted(String siteId) {
        evict(siteId);
        if (!runtime.available()) {
            return; // the reconcile sweep catches it when the database is back
        }
        try {
            runtime.locks().site(siteId, DROP_WAIT, () -> {
                try (Connection c = runtime.setupConnection()) {
                    c.setAutoCommit(true);
                    Optional<Registry.SiteRow> row = runtime.registry().site(c, siteId);
                    if (row.isEmpty()) {
                        return null;
                    }
                    Folder folder = folder(siteId); // read first: a folder that cannot be read is never taken for none
                    if (sites.find(siteId).isPresent() && folder.names(row.get())) {
                        return null; // the site is back (restored with this very data): it keeps it
                    }
                    retire(c, siteId, row.get().schema());
                    dropRecorded(c, row.get().schema());
                }
                return null;
            });
        } catch (SQLException | RuntimeException e) {
            log.warn("database: could not remove the data of deleted site '{}' now; the sweep will: {}", siteId,
                    DatabaseRuntime.describe(e));
        }
    }

    /** Drops a recorded schema and forgets it. The name, and that no site maps to it, are checked before the DROP. */
    private void dropRecorded(Connection c, String schema) throws SQLException {
        Engine engine = runtime.engine();
        String ns = runtime.config().namespace();
        if (!Names.isSiteSchema(ns, schema, engine.identifierMax())) {
            throw new IllegalStateException("refusing to drop a schema that is not a site schema: " + schema);
        }
        if (runtime.registry().isMapped(c, schema)) {
            throw new IllegalStateException("refusing to drop a schema a site maps to: " + schema);
        }
        runtime.registry().schemaState(c, schema, DROPPING);
        engine.dropSchema(c, schema);
        runtime.registry().deleteSchema(c, schema);
        log.info("database: dropped schema '{}'", schema);
    }

    /**
     * The reconcile sweep: retires mappings whose site is gone or whose folder says the data is not the site's, and
     * drops retired schemas past the grace period, staging generations no restore is building, and creations a crash
     * interrupted. Schemas in the namespace that the registry never recorded are reported, never dropped. A site
     * folder that cannot be read leaves that site's data alone; a site that is gone, or whose folder has no token on
     * two passes in a row, only has its data retired (dropped after the grace period), never dropped at once.
     */
    public void reconcile() {
        if (!runtime.available()) {
            return;
        }
        try {
            List<Registry.SiteRow> mappings;
            Registry.Schemas recorded;
            try (Connection c = runtime.setupConnection()) {
                mappings = runtime.registry().sites(c);
                recorded = runtime.registry().schemas(c);
                reportUnrecorded(c, recorded);
            }
            afterSweepSnapshot.run();
            Set<String> absentNow = new HashSet<>();
            for (Registry.SiteRow row : mappings) {
                try {
                    reconcile(row, absentNow);
                } catch (UnreadableFolderException e) {
                    log.warn("database: {}; the sweep leaves its data alone", e.getMessage());
                } catch (RuntimeException e) {
                    log.warn("database: the sweep skipped site '{}': {}", row.siteId(), DatabaseRuntime.describe(e));
                }
            }
            absentLastSweep.clear();
            absentLastSweep.addAll(absentNow);
            Set<String> mapped = mappings.stream().map(Registry.SiteRow::schema).collect(Collectors.toSet());
            for (Registry.SchemaRow s : recorded.rows()) {
                if (droppable(s, mapped, recorded.now())) {
                    dropIfStillDroppable(s.siteId(), s.schema());
                }
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("database: the reconcile sweep stopped early: {}", DatabaseRuntime.describe(e));
        }
    }

    private void reconcile(Registry.SiteRow row, Set<String> absentNow) {
        String siteId = row.siteId();
        Folder folder = folder(siteId); // first: a folder that cannot be read is never taken for a missing site
        boolean gone = sites.find(siteId).isEmpty();
        if (!gone && (!LIVE.equals(row.state()) || folder.names(row))) {
            return;
        }
        if (!gone && folder.state() == FolderState.ABSENT && !absentLastSweep.contains(siteId)) {
            absentNow.add(siteId); // a restore may be about to write the token back: decided on the next pass
            return;
        }
        boolean wasAbsent = !gone && folder.state() == FolderState.ABSENT;
        lockedSilently(siteId, c -> {
            // Decided again under the lock, from fresh reads: a restore or an allocation may have just finished.
            Optional<Registry.SiteRow> again = runtime.registry().site(c, siteId);
            Folder now = folder(siteId);
            if (again.isEmpty() || now.names(again.get())) {
                return;
            }
            if (sites.find(siteId).isEmpty()) {
                log.info("database: site '{}' no longer exists; retiring its data", siteId);
                retire(c, siteId, again.get().schema());
            } else if (!LIVE.equals(again.get().state())) {
                return;
            } else if (now.adoptable()) {
                adopt(siteId, again.get(), now);
            } else if (now.state() != FolderState.ABSENT || wasAbsent) {
                log.info("database: site '{}' has no token naming schema '{}'; retiring it", siteId,
                        again.get().schema());
                retire(c, siteId, again.get().schema());
            }
        });
    }

    /**
     * After a site is created (on the node that created it): data the registry still has for this id belongs to an
     * earlier site with the same id (deleted while the database was unreachable or the plugin off), so it is retired,
     * unless the folder already names it (a backup import that finished first). With the database unreachable the
     * folder is marked instead, and the next use retires it. Runs on the executor.
     */
    public void retireEarlierSite(String siteId) {
        try {
            if (!runtime.available()) {
                FolderState state = folder(siteId).state();
                if (state != FolderState.TOKEN && state != FolderState.FRESH) {
                    markFresh(siteId);
                }
                return;
            }
            runtime.locks().site(siteId, DROP_WAIT, () -> {
                try (Connection c = runtime.setupConnection()) {
                    c.setAutoCommit(true);
                    Optional<Registry.SiteRow> row = runtime.registry().site(c, siteId);
                    if (row.isPresent() && !folder(siteId).names(row.get())) {
                        log.info("database: new site '{}' has an id an earlier site used; retiring that site's "
                                + "data in schema '{}'", siteId, row.get().schema());
                        retire(c, siteId, row.get().schema());
                    }
                }
                return null;
            });
        } catch (SQLException | RuntimeException e) {
            log.warn("database: could not check new site '{}' for an earlier site's data now; its first use will: "
                    + "{}", siteId, DatabaseRuntime.describe(e));
        }
    }

    /**
     * Drops a schema the sweep found droppable, deciding again under the site lock from fresh reads: the sweep's
     * snapshot is stale once the lock is ours (a restore that held it may have just made this very schema live).
     */
    void dropIfStillDroppable(String siteId, String schema) {
        lockedSilently(siteId, c -> {
            Registry.Schemas fresh = runtime.registry().schemas(c);
            Set<String> mappedNow = runtime.registry().sites(c).stream().map(Registry.SiteRow::schema)
                    .collect(Collectors.toSet());
            for (Registry.SchemaRow now : fresh.rows()) {
                if (now.schema().equals(schema) && droppable(now, mappedNow, fresh.now())) {
                    dropRecorded(c, now.schema());
                }
            }
        });
    }

    /**
     * Whether the sweep may drop a recorded schema: never one a site maps to; a retired one past its grace; one left
     * half dropped; a staging one (a restore builds it under the site lock, so once the sweep holds that lock it is a
     * leftover); a creation a crash interrupted.
     */
    static boolean droppable(Registry.SchemaRow s, Set<String> mapped, Instant now) {
        if (mapped.contains(s.schema())) {
            return false;
        }
        return switch (s.state()) {
            case RETIRED -> s.changedAt() != null && s.changedAt().plus(RETIRED_GRACE).isBefore(now);
            case DROPPING, STAGING, CREATING -> true;
            default -> false;
        };
    }

    private void reportUnrecorded(Connection c, Registry.Schemas recorded) throws SQLException {
        String ns = runtime.config().namespace();
        Set<String> known = recorded.rows().stream().map(Registry.SchemaRow::schema).collect(Collectors.toSet());
        known.add(Names.appSchema(ns));
        for (String name : runtime.engine().listSchemas(c, ns)) {
            if (!known.contains(name)) {
                log.warn("database: schema '{}' is in this install's namespace but not in its registry; leaving it "
                        + "alone (another install sharing the namespace?)", name);
            }
        }
    }

    @FunctionalInterface
    private interface ConnectionWork {
        void run(Connection c) throws SQLException;
    }

    private void lockedSilently(String siteId, ConnectionWork work) {
        try {
            runtime.locks().site(siteId, DROP_WAIT, () -> {
                try (Connection c = runtime.setupConnection()) {
                    c.setAutoCommit(true);
                    work.run(c);
                }
                return null;
            });
        } catch (SQLException | RuntimeException e) {
            log.warn("database: sweep step for site '{}' failed: {}", siteId, DatabaseRuntime.describe(e));
        }
    }

    // --- the token ----------------------------------------------------------------------------------------------

    static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** What a site's folder says: {@link #TOKEN_FILE} absent, holding a token, or holding the adopt marker. */
    /**
     * What a site's folder says about its data: nothing ({@code ABSENT}); a token of this site ({@code TOKEN}); a
     * token written for another site id, copied in with another site's backup ({@code FOREIGN}); keep what the
     * registry has live, written by a restore that could not reach the database ({@code ADOPT}); a new site, so
     * nothing the registry has is its data ({@code FRESH}).
     */
    enum FolderState { ABSENT, TOKEN, FOREIGN, ADOPT, FRESH }

    /** A site's folder as read; {@code token} is set for {@link FolderState#TOKEN} only. */
    record Folder(FolderState state, String token) {

        /** Whether the folder names exactly this mapping. */
        boolean names(Registry.SiteRow row) {
            return state == FolderState.TOKEN && token.equals(row.token());
        }

        /** Whether the folder comes from this site's own history, so the live data stays the site's. */
        boolean adoptable() {
            return state == FolderState.TOKEN || state == FolderState.ADOPT;
        }
    }

    /** The site's folder could not be read, so nothing may be decided from it. */
    static final class UnreadableFolderException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnreadableFolderException(String siteId, Exception cause) {
            super("the database folder of site '" + siteId + "' cannot be read (" + DatabaseRuntime.describe(cause)
                    + ")", cause);
        }
    }

    /**
     * Reads the site's folder. A site that does not exist reads as {@link FolderState#ABSENT}; a file with neither a
     * valid token nor the marker (edited by hand) too.
     *
     * @throws UnreadableFolderException when the file or its folder cannot be read
     */
    Folder folder(String siteId) {
        Optional<String> text;
        try {
            text = files.readText(siteId, TOKEN_FILE);
        } catch (IOException | RuntimeException e) {
            throw new UnreadableFolderException(siteId, e);
        }
        if (text.isEmpty()) {
            return new Folder(FolderState.ABSENT, null);
        }
        return parse(siteId, text.get());
    }

    /** A token file's meaning for site {@code siteId}. */
    static Folder parse(String siteId, String text) {
        List<String> lines = text.lines().map(String::strip).toList();
        Optional<String> token = parseToken(text);
        if (token.isPresent()) {
            // A file written before tokens named their site has no site line; one naming another id was copied in
            // with that site's backup and says nothing about this site's data.
            boolean ours = lines.stream().filter(l -> l.startsWith("site:"))
                    .allMatch(l -> l.substring("site:".length()).strip().equals(siteId));
            return ours ? new Folder(FolderState.TOKEN, token.get()) : new Folder(FolderState.FOREIGN, null);
        }
        if (lines.contains(FRESH_LINE)) {
            return new Folder(FolderState.FRESH, null);
        }
        return new Folder(lines.contains(ADOPT_LINE) ? FolderState.ADOPT : FolderState.ABSENT, null);
    }

    private Folder folderOrUnavailable(String siteId) {
        try {
            return folder(siteId);
        } catch (UnreadableFolderException e) {
            throw unavailable(e);
        }
    }

    private static DatabaseUnavailableException unavailable(UnreadableFolderException e) {
        return new DatabaseUnavailableException(e.getMessage() + "; its data is left as it is", e);
    }

    static Optional<String> parseToken(String text) {
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (t.startsWith("token:")) {
                String v = t.substring("token:".length()).strip();
                if (v.matches("[0-9a-f]{32}")) {
                    return Optional.of(v);
                }
            }
        }
        return Optional.empty();
    }

    private void writeToken(String siteId, String token) {
        writeFile(siteId, "# Written by the database plugin: which database schema holds this site's data.\n"
                + "# Do not edit or copy it; a site without it gets a fresh schema.\nsite: " + siteId + "\ntoken: "
                + token + "\n");
    }

    private void writeFile(String siteId, String text) {
        try {
            files.writeText(siteId, TOKEN_FILE, text);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("the site's folder cannot take the database token (deleted site?)", e);
        }
    }
}
