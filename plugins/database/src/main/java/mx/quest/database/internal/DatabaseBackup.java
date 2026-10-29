package mx.quest.database.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;
import mx.quest.database.spi.Scope;
import mx.quest.runway.spi.BackupParticipant;
import mx.quest.runway.spi.model.BackupReader;
import mx.quest.runway.spi.model.BackupWriter;
import mx.quest.runway.spi.model.SiteRef;
import mx.quest.runway.spi.service.PluginSiteFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A site's database data in its backups ({@code plugin-data/database/} inside the archive).
 *
 * <h2>Export</h2>
 * A site whose folder has no database token has no data: {@code state.json} says {@code none} and the backup goes
 * on. Otherwise every table of every plugin is read in one consistent snapshot — tables streamed with a fetch size,
 * sequences last — into typed, versioned JSON-lines parts (gzip, at most {@value #PART_MAX} bytes each), one
 * {@code index.json} per table, and {@code manifest.json} last. If the database cannot be reached the backup fails,
 * naming the site: a backup is complete or it is not a backup. The whole is capped by {@code backupBudgetMiB}.
 *
 * <h2>Restore</h2>
 * <ol>
 *   <li>The archive is checked whole before anything live is touched ({@link Archive}).</li>
 *   <li>Per plugin: installed and at or above the backup's version, restore it; installed but older, refuse the
 *       restore; not installed, park its data in the site folder until it arrives.</li>
 *   <li>A new generation is built under the site lock: each plugin is migrated to the backup's version, its tables
 *       emptied, checked against the archive, loaded with user triggers and foreign keys out of the way, given its
 *       sequences, and migrated forward.</li>
 *   <li>One transaction switches the site to the new generation; the old one is dropped later. On failure the new
 *       generation is dropped, the old one stays live, and the restore throws; the failure is kept for the health
 *       screen and the site's dashboard.</li>
 * </ol>
 */
public final class DatabaseBackup implements BackupParticipant, Migrator.Parked {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackup.class);

    public static final String KEY = "database";
    static final int PART_MAX = 4 * 1024 * 1024;
    static final int FETCH = 500;
    static final int BATCH = 500;
    static final String PARKED = "deferred/";
    static final Duration RESTORE_LOCK_WAIT = Duration.ofMinutes(5);

    /** The last restore of a site, for the health screen and the site's dashboard. */
    public record Outcome(Instant at, boolean ok, String message) {
    }

    private final DatabaseCore core;
    private final DatabasesImpl databases;
    private final PluginSiteFiles files;
    private final Map<String, Outcome> outcomes = new ConcurrentHashMap<>();
    private final Map<String, Long> exportBytes = new ConcurrentHashMap<>();

    public DatabaseBackup(DatabaseCore core, DatabasesImpl databases, PluginSiteFiles files) {
        this.core = core;
        this.databases = databases;
        this.files = files;
    }

    @Override
    public String key() {
        return KEY;
    }

    /** The last restore outcome of a site. */
    public Optional<Outcome> outcome(String siteId) {
        return Optional.ofNullable(outcomes.get(siteId));
    }

    /** The largest compressed size a site's last backup reached on this node, with the site, if any backup ran. */
    public Optional<Map.Entry<String, Long>> largestBackup() {
        return exportBytes.entrySet().stream().max(Map.Entry.comparingByValue());
    }

    /** The most recent failed restore of any site, for the health screen. */
    public Optional<Map.Entry<String, Outcome>> lastFailure() {
        return outcomes.entrySet().stream().filter(e -> !e.getValue().ok())
                .max(Map.Entry.comparingByValue((a, b) -> a.at().compareTo(b.at())));
    }

    // =============================================================================================== export ==

    @Override
    public void export(BackupWriter out, SiteRef site) throws IOException {
        String siteId = site.id();
        if (!core.schemas().hasToken(siteId)) {
            writeJson(out, Archive.STATE, Map.of("schema", "none"));
            return;
        }
        DatabaseRuntime runtime = core.runtime();
        if (!runtime.available()) {
            throw new IOException("the database is not reachable, so the database data of site '" + siteId
                    + "' cannot be backed up");
        }
        String schema;
        try {
            schema = core.schemas().resolve(siteId);
        } catch (RuntimeException e) {
            throw new IOException("the database schema of site '" + siteId + "' cannot be resolved: "
                    + e.getMessage(), e);
        }
        Engine engine = runtime.engine();
        long[] spent = {0};
        long budget = runtime.config().backupBudgetBytes();
        List<Map<String, Object>> tableEntries = new ArrayList<>();
        List<Map<String, Object>> sequenceEntries = new ArrayList<>();
        Map<String, Integer> versions;
        // The tables are read into a spool on disk and handed to the kernel only after the read transaction has ended
        // and its connection is back: the kernel streams a download straight to the browser, and writing to it from
        // inside the transaction held one of the setup pool's two connections (and an open snapshot) for as long as
        // the slowest browser took. The spool holds at most the backup budget.
        try (Spool spool = new Spool(runtime.workDir().resolve("spool"))) {
            try (Connection c = runtime.setupConnection()) {
                Map<String, String> prefixes;
                c.setAutoCommit(true);
                prefixes = runtime.registry().prefixes(c);
                c.setAutoCommit(false);
                c.setReadOnly(true);
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                try {
                    engine.bind(c, schema, DatabaseRuntime.LONG_TIMEOUT);
                    engine.beginRead(c);
                    Map<String, List<Engine.Column>> tables = engine.tables(c, schema);
                    versions = appliedVersions(c, engine, schema);
                    for (Map.Entry<String, List<Engine.Column>> t : new TreeMap<>(tables).entrySet()) {
                        String table = t.getKey();
                        if (table.startsWith(Names.RESERVED_PREFIX)) {
                            continue;
                        }
                        String plugin = owner(table, prefixes);
                        if (plugin == null) {
                            log.warn("database: table '{}' of site '{}' carries no known plugin prefix; it is not "
                                    + "in the backup", table, siteId);
                            continue;
                        }
                        versions.putIfAbsent(plugin, 0);
                        tableEntries.add(Map.of("name", table, "plugin", plugin));
                        writeTable(spool, c, engine, table, t.getValue(), budget, spent);
                    }
                    for (Engine.Sequence s : engine.sequences(c, schema)) {
                        String plugin = owner(s.name(), prefixes);
                        if (plugin != null) {
                            versions.putIfAbsent(plugin, 0);
                            sequenceEntries.add(Map.of("name", s.name(), "plugin", plugin,
                                    "next", Long.toString(s.next()), "increment", Long.toString(s.increment())));
                        }
                    }
                    c.commit();
                } finally {
                    try {
                        c.rollback();
                    } catch (SQLException ignored) {
                        // committed already
                    }
                    c.setReadOnly(false);
                    c.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new IOException("reading the database data of site '" + siteId + "' failed: "
                        + DatabaseRuntime.describe(e), e);
            }
            spool.drainTo(out);
        }
        Map<String, Object> plugins = new TreeMap<>();
        versions.forEach((id, v) -> plugins.put(id, Map.of("version", v)));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", Archive.FORMAT);
        manifest.put("dialect", engine.id());
        manifest.put("plugins", plugins);
        manifest.put("tables", tableEntries);
        manifest.put("sequences", sequenceEntries);
        writeJson(out, Archive.STATE, Map.of("schema", "present"));
        writeJson(out, Archive.MANIFEST, manifest); // last: a backup cut short has no manifest and never restores
        exportBytes.put(siteId, spent[0]);
    }

    /** The plugin whose prefix a name starts with (prefixes never start one another, so at most one). */
    static String owner(String name, Map<String, String> prefixes) {
        for (Map.Entry<String, String> e : prefixes.entrySet()) {
            if (name.startsWith(e.getValue())) {
                return e.getKey();
            }
        }
        return null;
    }

    private static Map<String, Integer> appliedVersions(Connection c, Engine engine, String schema)
            throws SQLException {
        Map<String, Integer> versions = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT plugin_id, MAX(version) FROM " + engine.quote(schema)
                + ".rw_migrations WHERE state = 'applied' GROUP BY plugin_id")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    versions.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        return versions;
    }

    private void writeTable(BackupWriter out, Connection c, Engine engine, String table, List<Engine.Column> columns,
                            long budget, long[] spent) throws IOException, SQLException {
        List<Map<String, Object>> header = new ArrayList<>();
        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            Engine.Column col = columns.get(i);
            if (col.type().startsWith(Portable.UNSUPPORTED)) {
                throw new IOException("column " + table + "." + col.name() + " has a type the backup cannot carry ("
                        + col.type().substring(Portable.UNSUPPORTED.length()) + ")");
            }
            header.add(Map.of("name", col.name(), "type", col.type()));
            select.append(i == 0 ? "" : ", ").append(engine.quote(col.name()));
        }
        select.append(" FROM ").append(engine.quote(table));
        byte[] headerLine = line(Map.of("columns", header));
        List<Map<String, Object>> parts = new ArrayList<>();
        long total = 0;
        try (PreparedStatement ps = c.prepareStatement(select.toString(), ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY)) {
            ps.setFetchSize(FETCH);
            try (ResultSet rs = ps.executeQuery()) {
                PartBuffer part = new PartBuffer(headerLine);
                Object[] row = new Object[columns.size()];
                while (rs.next()) {
                    for (int i = 0; i < row.length; i++) {
                        row[i] = engine.readValue(rs, i + 1, columns.get(i).type());
                    }
                    part.add(line(row));
                    total++;
                    if (part.size() >= PART_MAX) {
                        parts.add(flush(out, table, parts.size() + 1, part, budget, spent));
                        part = new PartBuffer(headerLine);
                    }
                }
                if (part.rows() > 0 || parts.isEmpty()) {
                    parts.add(flush(out, table, parts.size() + 1, part, budget, spent));
                }
            }
        }
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("table", table);
        index.put("columns", header);
        index.put("rows", total);
        index.put("parts", parts);
        writeJson(out, "tables/" + table + "/index.json", index);
    }

    private Map<String, Object> flush(BackupWriter out, String table, int number, PartBuffer part, long budget,
                                      long[] spent) throws IOException {
        byte[] bytes = part.finish();
        spent[0] += bytes.length;
        if (spent[0] > budget) {
            throw new IOException("the database data is larger than the backup budget (" + (budget / (1024 * 1024))
                    + " MiB compressed); raise backupBudgetMiB or back the database up with its own dump tools");
        }
        String name = String.format("part-%05d.jsonl.gz", number);
        try (OutputStream o = out.put("tables/" + table + "/" + name)) {
            o.write(bytes);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("rows", part.rows());
        entry.put("sha256", Names.sha256Hex(bytes));
        return entry;
    }

    /**
     * Entries written while the read transaction is open, kept in files in a private folder and handed to the real
     * writer, in the same order, once the transaction is over. Deleted on close; a spool left by a crash is deleted by
     * the next export.
     */
    static final class Spool implements BackupWriter, AutoCloseable {

        private final Path dir;
        private final List<String> entries = new ArrayList<>();

        Spool(Path parent) throws IOException {
            Files.createDirectories(parent);
            try (DirectoryStream<Path> old = Files.newDirectoryStream(parent, "export-*")) {
                for (Path p : old) {
                    if (Files.getLastModifiedTime(p).toInstant().isBefore(Instant.now().minus(Duration.ofDays(1)))) {
                        deleteQuietly(p);
                    }
                }
            }
            Path made;
            try {
                made = Files.createTempDirectory(parent, "export-",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException noPosix) {
                made = Files.createTempDirectory(parent, "export-");
            }
            dir = made;
        }

        @Override
        public OutputStream put(String entry) throws IOException {
            Path file = dir.resolve(String.format("%06d", entries.size()));
            entries.add(entry);
            return Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        /** Copies every entry to {@code out}, in the order written. */
        void drainTo(BackupWriter out) throws IOException {
            for (int i = 0; i < entries.size(); i++) {
                try (OutputStream o = out.put(entries.get(i));
                     InputStream in = Files.newInputStream(dir.resolve(String.format("%06d", i)))) {
                    in.transferTo(o);
                }
            }
        }

        @Override
        public void close() {
            deleteQuietly(dir);
        }

        private static void deleteQuietly(Path folder) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
                for (Path f : files) {
                    Files.deleteIfExists(f);
                }
                Files.deleteIfExists(folder);
            } catch (IOException e) {
                log.warn("database: could not delete the export spool {}: {}", folder, e.getMessage());
            }
        }
    }

    /** One part being written: gzip in memory, the header line first. */
    private static final class PartBuffer {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final GZIPOutputStream gzip;
        private long rows;

        PartBuffer(byte[] header) throws IOException {
            gzip = new GZIPOutputStream(bytes, 64 * 1024);
            gzip.write(header);
        }

        void add(byte[] line) throws IOException {
            gzip.write(line);
            rows++;
        }

        long rows() {
            return rows;
        }

        int size() {
            return bytes.size();
        }

        byte[] finish() throws IOException {
            gzip.finish();
            return bytes.toByteArray();
        }
    }

    private static byte[] line(Object value) {
        byte[] json = Archive.json().writeValueAsBytes(value);
        byte[] withNewline = new byte[json.length + 1];
        System.arraycopy(json, 0, withNewline, 0, json.length);
        withNewline[json.length] = '\n';
        return withNewline;
    }

    private static void writeJson(BackupWriter out, String entry, Object value) throws IOException {
        try (OutputStream o = out.put(entry)) {
            o.write(Archive.json().writeValueAsBytes(value));
        }
    }

    // ============================================================================================== restore ==

    @Override
    public void importInto(BackupReader in, SiteRef site) throws IOException {
        String siteId = site.id();
        try {
            restore(entries(in), siteId);
        } catch (IOException | RuntimeException e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            outcomes.put(siteId, new Outcome(Instant.now(), false, why));
            log.error("database: restoring the database data of site '{}' failed: {}; its previous data is still "
                    + "live", siteId, why);
            throw e instanceof IOException io ? io : new IOException(why, e);
        }
    }

    private static Archive.Entries entries(BackupReader in) {
        return new Archive.Entries() {
            @Override
            public boolean has(String entry) {
                return in.has(entry);
            }

            @Override
            public byte[] read(String entry) throws IOException {
                try (var stream = in.open(entry)) {
                    byte[] bytes = stream.readNBytes(Archive.LINE_MAX + 1);
                    if (bytes.length > Archive.LINE_MAX) {
                        throw new IOException(entry + " is too large");
                    }
                    return bytes;
                }
            }
        };
    }

    private void restore(Archive.Entries in, String siteId) throws IOException {
        DatabaseRuntime runtime = core.runtime();
        Optional<String> state;
        try {
            state = Archive.state(in);
        } catch (IOException | RuntimeException e) {
            core.schemas().keepLiveDataAcrossRestore(siteId, RESTORE_LOCK_WAIT); // the live data stays the site's
            throw e;
        }
        if (state.isPresent() && "none".equals(state.get())) {
            retireIfAny(siteId);
            outcomes.put(siteId, new Outcome(Instant.now(), true, "the backup held no database data"));
            return;
        }
        // The kernel has already put the archive's folder in place, with the token of whatever generation was live
        // when the backup was taken (or none). Until this restore actually switches generations, the live data stays
        // the site's, also if a step below fails.
        core.schemas().keepLiveDataAcrossRestore(siteId, RESTORE_LOCK_WAIT);
        if (state.isEmpty()) {
            log.warn("database: the backup of site '{}' has no database state (taken without the database plugin); "
                    + "its database data is left as it is", siteId);
            return;
        }
        if (!"present".equals(state.get())) {
            throw new IOException("the backup's database state is unknown: " + state.get());
        }
        if (!runtime.available()) {
            throw new IOException("the database is not reachable; the site's files were restored but not its "
                    + "database data");
        }
        Engine engine = runtime.engine();
        Archive archive = Archive.read(in, runtime.config().backupBudgetBytes(), engine instanceof PostgresEngine);
        Map<String, Consumer> installed = new HashMap<>();
        for (Consumer c : databases.consumers()) {
            installed.put(c.pluginId(), c);
        }
        List<Consumer> restore = new ArrayList<>();
        List<String> park = new ArrayList<>();
        for (Map.Entry<String, Integer> p : new TreeMap<>(archive.plugins).entrySet()) {
            Consumer consumer = installed.get(p.getKey());
            if (consumer == null || !consumer.scopes().contains(Scope.Kind.SITE)) {
                park.add(p.getKey());
                continue;
            }
            int shipped = core.migrator().shipped(consumer, Scope.Kind.SITE).size();
            if (shipped < p.getValue()) {
                throw new IOException("plugin '" + p.getKey() + "' is older than the backup (its data is at version "
                        + p.getValue() + ", the installed plugin ships " + shipped + "); upgrade it first");
            }
            restore.add(consumer);
        }
        try {
            runtime.locks().site(siteId, RESTORE_LOCK_WAIT, () -> {
                String staging = core.schemas().createStaging(siteId);
                try {
                    for (Consumer consumer : restore) {
                        core.migrator().migrateWithData(consumer, Scope.Kind.SITE, staging,
                                archive.plugins.get(consumer.pluginId()),
                                schema -> load(in, archive, consumer, schema));
                    }
                    core.schemas().switchTo(siteId, staging);
                } catch (SQLException | RuntimeException e) {
                    if (switchCommitted(siteId, staging)) {
                        log.warn("database: switching site '{}' to its restored data reported an error after it was "
                                + "made: {}", siteId, DatabaseRuntime.describe(e));
                        core.schemas().evict(siteId);
                        return null;
                    }
                    core.schemas().abandonStaging(staging);
                    throw e;
                }
                return null;
            });
        } catch (Locks.LockTimeoutException e) {
            throw new IOException("the site's data is busy (another restore or a removal); try again later");
        } catch (SQLException e) {
            throw new IOException("the database restore failed: " + DatabaseRuntime.describe(e), e);
        }
        for (String plugin : park) {
            park(in, archive, siteId, plugin);
        }
        outcomes.put(siteId, new Outcome(Instant.now(), true, "restored " + restore.size() + " plugin(s)"
                + (park.isEmpty() ? "" : ", kept data of " + String.join(", ", park) + " until installed")));
        log.info("database: restored the database data of site '{}'", siteId);
    }

    /** Whether a switch that reported an error had committed: the registry already maps the site to the staging data. */
    private boolean switchCommitted(String siteId, String staging) {
        try {
            return core.schemas().isLive(siteId, staging);
        } catch (SQLException | RuntimeException e) {
            return false; // abandonStaging then leaves a schema that turns out to be mapped alone
        }
    }

    private void retireIfAny(String siteId) {
        DatabaseRuntime runtime = core.runtime();
        if (!runtime.available()) {
            core.schemas().markFresh(siteId); // the old mapping is retired on its next use
            return;
        }
        try {
            runtime.locks().site(siteId, RESTORE_LOCK_WAIT, () -> {
                try (Connection c = runtime.setupConnection()) {
                    c.setAutoCommit(true);
                    Optional<Registry.SiteRow> row = runtime.registry().site(c, siteId);
                    if (row.isPresent()) {
                        core.schemas().retire(c, siteId, row.get().schema());
                    }
                }
                return null;
            });
        } catch (SQLException | RuntimeException e) {
            log.warn("database: could not retire the old data of site '{}' now; the sweep will: {}", siteId,
                    DatabaseRuntime.describe(e));
            core.schemas().markFresh(siteId);
        }
    }

    /**
     * Loads one plugin's archived tables into a schema migrated to the archive's version: checks every archived
     * table and column against the schema's own catalog, empties the plugin's tables (seed rows), loads with user
     * triggers and foreign keys out of the way, then sets the plugin's sequences.
     */
    void load(Archive.Entries in, Archive archive, Consumer consumer, String schema) throws SQLException,
            IOException {
        DatabaseRuntime runtime = core.runtime();
        Engine engine = runtime.engine();
        boolean postgres = engine instanceof PostgresEngine;
        try (Connection c = runtime.setupConnection()) {
            c.setAutoCommit(true);
            engine.bind(c, schema, DatabaseRuntime.LONG_TIMEOUT);
            c.setAutoCommit(false);
            boolean done = false;
            try {
                Map<String, List<Engine.Column>> catalog = engine.tables(c, schema);
                List<String> own = catalog.keySet().stream().filter(t -> t.startsWith(consumer.prefix())).sorted()
                        .toList();
                Map<Archive.Table, Plan> plans = new LinkedHashMap<>();
                for (Archive.Table table : archive.tables(consumer.pluginId())) {
                    plans.put(table, plan(table, catalog, consumer));
                }
                List<String> putBack = engine.suspendForLoad(c, schema, own);
                for (String table : own) {
                    Jdbc.execute(c, "DELETE FROM " + engine.quote(table));
                }
                long[] decompressed = {0};
                long cap = runtime.config().backupBudgetBytes() * 20;
                for (Map.Entry<Archive.Table, Plan> e : plans.entrySet()) {
                    Plan plan = e.getValue();
                    try (PreparedStatement ps = c.prepareStatement(plan.insert(engine, postgres))) {
                        int[] pending = {0};
                        for (Archive.Part part : e.getKey().parts()) {
                            Archive.forEachRow(in.read(part.entry()), e.getKey().name(), e.getKey().columns(), cap,
                                    decompressed, values -> {
                                        try {
                                            for (int i = 0; i < values.length; i++) {
                                                engine.bindValue(ps, i + 1, plan.types().get(i), values[i]);
                                            }
                                            ps.addBatch();
                                            if (++pending[0] >= BATCH) {
                                                ps.executeBatch();
                                                pending[0] = 0;
                                            }
                                        } catch (SQLException | IllegalArgumentException | java.time.DateTimeException
                                                 | ArithmeticException ex) {
                                            throw new IOException("a value of table " + e.getKey().name()
                                                    + " does not fit its column: " + ex.getMessage(), ex);
                                        }
                                    });
                        }
                        if (pending[0] > 0) {
                            ps.executeBatch();
                        }
                    }
                }
                for (String statement : putBack) {
                    Jdbc.execute(c, statement);
                }
                List<String> sequences = engine.sequences(c, schema).stream().map(Engine.Sequence::name).toList();
                for (Archive.Sequence s : archive.sequences(consumer.pluginId())) {
                    if (sequences.contains(s.name()) && s.name().startsWith(consumer.prefix())) {
                        engine.setSequence(c, schema, s.name(), s.next());
                    }
                }
                c.commit();
                done = true;
            } finally {
                if (done) {
                    c.setAutoCommit(true);
                } else {
                    try {
                        c.rollback();
                    } catch (SQLException ignored) {
                        // the generation is dropped anyway
                    }
                    // The load may have left session state behind (on MariaDB, FOREIGN_KEY_CHECKS = 0): the next
                    // migration on this connection would run without referential checks. Never lend it again.
                    runtime.evictSetup(c);
                }
            }
        }
    }

    /** How one archived table loads: the target's own table and column names, and the target types. */
    private record Plan(String table, List<String> columns, List<String> types) {

        String insert(Engine engine, boolean postgres) {
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(engine.quote(table)).append(" (");
            for (int i = 0; i < columns.size(); i++) {
                sql.append(i == 0 ? "" : ", ").append(engine.quote(columns.get(i)));
            }
            sql.append(")").append(postgres ? " OVERRIDING SYSTEM VALUE" : "").append(" VALUES (");
            for (int i = 0; i < columns.size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
            }
            return sql.append(")").toString();
        }
    }

    /** Matches an archived table to the catalog by lookup; refuses what does not fit. */
    private static Plan plan(Archive.Table table, Map<String, List<Engine.Column>> catalog, Consumer consumer)
            throws IOException {
        if (!table.name().startsWith(consumer.prefix())) {
            throw new IOException("table " + table.name() + " is outside the prefix of plugin '"
                    + consumer.pluginId() + "'");
        }
        String target = null;
        for (String name : catalog.keySet()) {
            if (name.equals(table.name())) {
                target = name; // the catalog's own string, never the archive's
            }
        }
        if (target == null) {
            throw new IOException("plugin '" + consumer.pluginId() + "' has no table " + table.name()
                    + " at the backup's version");
        }
        Map<String, Engine.Column> targetColumns = new LinkedHashMap<>();
        for (Engine.Column col : catalog.get(target)) {
            targetColumns.put(col.name(), col);
        }
        List<String> names = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (Archive.Column col : table.columns()) {
            Engine.Column t = targetColumns.remove(col.name());
            if (t == null) {
                throw new IOException("table " + table.name() + " has no column " + col.name());
            }
            if (!Portable.compatible(col.type(), t.type())) {
                throw new IOException("column " + table.name() + "." + col.name() + " is " + t.type()
                        + " here but " + col.type() + " in the backup");
            }
            names.add(t.name());
            types.add(t.type());
        }
        for (Engine.Column rest : targetColumns.values()) {
            if (!rest.nullable() && !rest.hasDefault()) {
                throw new IOException("column " + table.name() + "." + rest.name() + " is required but not in the "
                        + "backup");
            }
        }
        return new Plan(target, List.copyOf(names), List.copyOf(types));
    }

    // =============================================================================================== parked ==

    /** Keeps an absent plugin's data in the site folder (base64 of the archived bytes), best effort. */
    private void park(Archive.Entries in, Archive archive, String siteId, String plugin) {
        String folder = PARKED + plugin + "/";
        try {
            files.deleteTree(siteId, PARKED + plugin);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("format", Archive.FORMAT);
            manifest.put("dialect", archive.dialect);
            manifest.put("plugins", Map.of(plugin, Map.of("version", archive.plugins.get(plugin))));
            List<Map<String, Object>> tables = new ArrayList<>();
            for (Archive.Table t : archive.tables(plugin)) {
                tables.add(Map.of("name", t.name(), "plugin", plugin));
                files.writeText(siteId, folder + "tables/" + t.name() + "/index.json.b64",
                        Base64.getEncoder().encodeToString(in.read("tables/" + t.name() + "/index.json")));
                for (Archive.Part p : t.parts()) {
                    files.writeText(siteId, folder + p.entry() + ".b64",
                            Base64.getEncoder().encodeToString(in.read(p.entry())));
                }
            }
            manifest.put("tables", tables);
            List<Map<String, Object>> sequences = new ArrayList<>();
            for (Archive.Sequence s : archive.sequences(plugin)) {
                sequences.add(Map.of("name", s.name(), "plugin", plugin, "next", Long.toString(s.next()),
                        "increment", Long.toString(s.increment())));
            }
            manifest.put("sequences", sequences);
            files.writeText(siteId, folder + Archive.MANIFEST + ".b64",
                    Base64.getEncoder().encodeToString(Archive.json().writeValueAsBytes(manifest)));
            log.info("database: kept the data of plugin '{}' for site '{}' until that plugin is installed", plugin,
                    siteId);
        } catch (IOException | RuntimeException e) {
            log.warn("database: could not keep the data of absent plugin '{}' for site '{}': {}", plugin, siteId,
                    e.getMessage());
        }
    }

    private Archive.Entries parkedEntries(String siteId, String plugin) {
        String folder = PARKED + plugin + "/";
        return new Archive.Entries() {
            @Override
            public boolean has(String entry) throws IOException {
                return files.stat(siteId, folder + entry + ".b64").isPresent();
            }

            @Override
            public byte[] read(String entry) throws IOException {
                String text = files.readText(siteId, folder + entry + ".b64")
                        .orElseThrow(() -> new IOException("parked entry missing: " + entry));
                try {
                    return Base64.getDecoder().decode(text.strip().getBytes(StandardCharsets.US_ASCII));
                } catch (IllegalArgumentException e) {
                    throw new IOException("parked entry is damaged: " + entry);
                }
            }
        };
    }

    @Override
    public Optional<Integer> version(String siteId, Consumer consumer) throws IOException {
        Archive.Entries in = parkedEntries(siteId, consumer.pluginId());
        if (!in.has(Archive.MANIFEST)) { // a folder that cannot be searched throws: never "none"
            return Optional.empty();
        }
        // Read whole, as the load will: what cannot be loaded is refused now, before any table is created.
        Integer version = Archive.read(in, core.runtime().config().backupBudgetBytes(),
                core.runtime().engine() instanceof PostgresEngine).plugins.get(consumer.pluginId());
        if (version == null) {
            throw new IOException("the kept data does not name plugin '" + consumer.pluginId() + "'");
        }
        return Optional.of(version);
    }

    @Override
    public void load(String siteId, Consumer consumer, String schema) throws SQLException, IOException {
        Archive.Entries in = parkedEntries(siteId, consumer.pluginId());
        Archive archive = Archive.read(in, core.runtime().config().backupBudgetBytes(),
                core.runtime().engine() instanceof PostgresEngine);
        load(in, archive, consumer, schema);
        // The manifest is the mark that data waits: it goes first, and only once the data is in. If this fails the
        // load runs again on the next use (it empties the tables first).
        files.delete(siteId, PARKED + consumer.pluginId() + "/" + Archive.MANIFEST + ".b64");
        log.info("database: loaded the kept data of plugin '{}' into site '{}'", consumer.pluginId(), siteId);
        try {
            files.deleteTree(siteId, PARKED + consumer.pluginId());
        } catch (IOException | RuntimeException e) {
            log.warn("database: the rest of the kept data of plugin '{}' in site '{}' could not be removed: {}",
                    consumer.pluginId(), siteId, DatabaseRuntime.describe(e));
        }
    }
}
