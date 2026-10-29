package mx.quest.database.internal;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runway's own tables in {@code <ns>_app}: which schema each site uses ({@code rw_sites}), every schema ever created
 * and its state ({@code rw_schemas}), the plugin prefixes ({@code rw_plugins}), the nodes seen ({@code rw_nodes}) and
 * the migration problems the health screen lists ({@code rw_issues}). Every identifier column is binary, so a site
 * id never matches another by case. Plain SQL that both products accept; the names are quoted by the engine.
 */
public final class Registry {

    /** A site's live mapping. */
    public record SiteRow(String siteId, String schema, String token, long generation, String state) {
    }

    /** A schema this install created, with its state: {@code creating}, {@code live}, {@code staging},
     *  {@code retired}. */
    public record SchemaRow(String schema, String siteId, long generation, String state, Instant changedAt) {
    }

    /** A migration problem for one plugin in one schema. */
    public record Issue(String schema, String pluginId, String state, int version, int statement, String detail,
                        Instant at) {
    }

    /** A node seen recently. */
    public record Node(String nodeId, String host, String version, Instant startedAt, Instant seenAt) {
    }

    static final Duration GLOBAL_LOCK_WAIT = Duration.ofSeconds(30);
    static final String FORMAT = "1";

    private final DatabaseRuntime runtime;
    private final Engine engine;
    private final String app;
    private final String appQ;
    private final String nodeId = UUID.randomUUID().toString();
    private final String host;
    private final String version;

    Registry(DatabaseRuntime runtime) {
        this(runtime, Registry.class.getPackage().getImplementationVersion());
    }

    Registry(DatabaseRuntime runtime, String version) {
        this.runtime = runtime;
        this.engine = runtime.engine();
        this.app = Names.appSchema(runtime.config().namespace());
        this.appQ = engine.quote(app);
        this.host = hostName();
        this.version = version == null ? "dev" : version;
    }

    /** The app schema's name. */
    public String appSchema() {
        return app;
    }

    /** This JVM's node id. */
    public String nodeId() {
        return nodeId;
    }

    private static String hostName() {
        try {
            String h = InetAddress.getLocalHost().getHostName();
            return h.length() > 255 ? h.substring(0, 255) : h;
        } catch (Exception e) {
            return "unknown";
        }
    }

    // --- set-up -------------------------------------------------------------------------------------------------

    /** Creates the app schema and the registry tables if missing, then records this node. Idempotent. */
    void ensure() throws SQLException {
        runtime.locks().global("registry", GLOBAL_LOCK_WAIT, () -> {
            try (Connection c = runtime.setupConnection()) {
                c.setAutoCommit(true);
                Names.requireOwnSchema(runtime.config().namespace(), app, engine.identifierMax());
                engine.createSchema(c, app, runtime.config().collation());
                try (Statement s = c.createStatement()) {
                    for (String ddl : engine.registryDdl(app)) {
                        s.execute(ddl);
                    }
                    s.execute(engine.migrationsDdl(app));
                }
                String key = engine.quote("key");
                boolean present;
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + appQ + ".rw_install WHERE "
                        + key + " = 'format'")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        present = rs.getInt(1) > 0;
                    }
                }
                if (!present) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + appQ + ".rw_install (" + key
                            + ", value) VALUES ('format', ?)")) {
                        ps.setString(1, FORMAT);
                        ps.executeUpdate();
                    }
                }
            }
            return null;
        });
        try (Connection c = runtime.setupConnection()) {
            c.setAutoCommit(true);
            heartbeat(c);
        }
    }

    /** How long a node that stopped sending heartbeats stays in {@code rw_nodes}. */
    static final String NODE_RETENTION = "INTERVAL '1' DAY";

    /**
     * Records that this node is alive, and forgets nodes not seen for a day: every start is a new node id, so without
     * the prune the table would grow by a row per restart, and the health screen reads all of it.
     */
    void heartbeat(Connection c) throws SQLException {
        int updated;
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + appQ + ".rw_nodes SET seen_at = "
                + engine.nowSql() + " WHERE node_id = ?")) {
            ps.setString(1, nodeId);
            updated = ps.executeUpdate();
        }
        if (updated == 0) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + appQ + ".rw_nodes (node_id, host, "
                    + "version, started_at, seen_at) VALUES (?, ?, ?, " + engine.nowSql() + ", " + engine.nowSql()
                    + ")")) {
                ps.setString(1, nodeId);
                ps.setString(2, host);
                ps.setString(3, version.length() > 32 ? version.substring(0, 32) : version);
                ps.executeUpdate();
            }
        }
        try (Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM " + appQ + ".rw_nodes WHERE seen_at < " + engine.nowSql() + " - "
                    + NODE_RETENTION);
        }
    }

    // --- sites ----------------------------------------------------------------------------------------------------

    /** A site's mapping, read on a consumer connection that is not bound to anything yet. */
    Optional<SiteRow> site(Connection c, String siteId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT site_id, schema_name, token, generation, state FROM "
                + appQ + ".rw_sites WHERE site_id = ?")) {
            ps.setString(1, siteId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SiteRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5)));
            }
        }
    }

    /** Every site mapping. */
    List<SiteRow> sites(Connection c) throws SQLException {
        List<SiteRow> rows = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT site_id, schema_name, token, generation, state FROM " + appQ
                     + ".rw_sites ORDER BY site_id")) {
            while (rs.next()) {
                rows.add(new SiteRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5)));
            }
        }
        return rows;
    }

    /** The next generation number. */
    long nextGeneration(Connection c) throws SQLException {
        return engine.nextGeneration(c, app);
    }

    /** Records a new schema before it is created, so a crash leaves a row the reconcile sweep can act on. */
    void insertSchema(Connection c, String schema, String siteId, long generation, String state) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + appQ + ".rw_schemas (schema_name, site_id, "
                + "generation, state, changed_at) VALUES (?, ?, ?, ?, " + engine.nowSql() + ")")) {
            ps.setString(1, schema);
            ps.setString(2, siteId);
            ps.setLong(3, generation);
            ps.setString(4, state);
            ps.executeUpdate();
        }
    }

    /** Changes a schema's state. */
    void schemaState(Connection c, String schema, String state) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + appQ + ".rw_schemas SET state = ?, changed_at = "
                + engine.nowSql() + " WHERE schema_name = ?")) {
            ps.setString(1, state);
            ps.setString(2, schema);
            ps.executeUpdate();
        }
    }

    /**
     * Moves a schema from one state to another only when it is in {@code from} and no site maps to it.
     *
     * @return whether it moved
     */
    boolean schemaStateIfUnmapped(Connection c, String schema, String from, String to) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + appQ + ".rw_schemas SET state = ?, changed_at = "
                + engine.nowSql() + " WHERE schema_name = ? AND state = ? AND NOT EXISTS (SELECT 1 FROM " + appQ
                + ".rw_sites WHERE schema_name = ?)")) {
            ps.setString(1, to);
            ps.setString(2, schema);
            ps.setString(3, from);
            ps.setString(4, schema);
            return ps.executeUpdate() == 1;
        }
    }

    /** Whether a site maps to the schema. */
    boolean isMapped(Connection c, String schema) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + appQ + ".rw_sites WHERE schema_name = ?")) {
            ps.setString(1, schema);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Forgets a dropped schema. */
    void deleteSchema(Connection c, String schema) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + appQ + ".rw_schemas WHERE schema_name = ?")) {
            ps.setString(1, schema);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + appQ + ".rw_issues WHERE schema_name = ?")) {
            ps.setString(1, schema);
            ps.executeUpdate();
        }
    }

    /** Every schema this install recorded, with the database's own current time for age comparisons. */
    record Schemas(List<SchemaRow> rows, Instant now) {
    }

    Schemas schemas(Connection c) throws SQLException {
        List<SchemaRow> rows = new ArrayList<>();
        Instant now = null;
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT schema_name, site_id, generation, state, changed_at, "
                     + engine.nowSql() + " FROM " + appQ + ".rw_schemas ORDER BY schema_name")) {
            while (rs.next()) {
                rows.add(new SchemaRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        engine.instant(rs, 5)));
                now = engine.instant(rs, 6);
            }
        }
        if (now == null) {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT " + engine.nowSql())) {
                rs.next();
                now = engine.instant(rs, 1);
            }
        }
        return new Schemas(rows, now);
    }

    /** Points a site at a schema (insert or replace), in the caller's transaction. */
    void putSite(Connection c, String siteId, String schema, String token, long generation, String state)
            throws SQLException {
        deleteSite(c, siteId);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + appQ + ".rw_sites (site_id, schema_name, "
                + "token, generation, state, created_at) VALUES (?, ?, ?, ?, ?, " + engine.nowSql() + ")")) {
            ps.setString(1, siteId);
            ps.setString(2, schema);
            ps.setString(3, token);
            ps.setLong(4, generation);
            ps.setString(5, state);
            ps.executeUpdate();
        }
    }

    /** Sets a site row's state. */
    void siteState(Connection c, String siteId, String schema, String state) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + appQ + ".rw_sites SET state = ? "
                + "WHERE site_id = ? AND schema_name = ?")) {
            ps.setString(1, state);
            ps.setString(2, siteId);
            ps.setString(3, schema);
            ps.executeUpdate();
        }
    }

    /** Removes a site's mapping. */
    void deleteSite(Connection c, String siteId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + appQ + ".rw_sites WHERE site_id = ?")) {
            ps.setString(1, siteId);
            ps.executeUpdate();
        }
    }

    // --- plugins --------------------------------------------------------------------------------------------------

    /** Every recorded plugin prefix, by plugin id. */
    Map<String, String> prefixes(Connection c) throws SQLException {
        Map<String, String> map = new LinkedHashMap<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT plugin_id, prefix FROM " + appQ
                     + ".rw_plugins ORDER BY plugin_id")) {
            while (rs.next()) {
                map.put(rs.getString(1), rs.getString(2));
            }
        }
        return map;
    }

    /** Records a plugin's prefix. */
    void insertPrefix(Connection c, String pluginId, String prefix) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + appQ + ".rw_plugins (plugin_id, prefix, "
                + "first_seen) VALUES (?, ?, " + engine.nowSql() + ")")) {
            ps.setString(1, pluginId);
            ps.setString(2, prefix);
            ps.executeUpdate();
        }
    }

    // --- issues ---------------------------------------------------------------------------------------------------

    /** Records (or replaces) the problem a plugin has in a schema. */
    void putIssue(Connection c, String schema, String pluginId, String state, int version, int statement,
                  String detail) throws SQLException {
        clearIssue(c, schema, pluginId);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + appQ + ".rw_issues (schema_name, plugin_id, "
                + "state, version, statement, detail, at) VALUES (?, ?, ?, ?, ?, ?, " + engine.nowSql() + ")")) {
            ps.setString(1, schema);
            ps.setString(2, pluginId);
            ps.setString(3, state);
            ps.setInt(4, version);
            ps.setInt(5, statement);
            ps.setString(6, detail.length() > 1000 ? detail.substring(0, 999) + "…" : detail);
            ps.executeUpdate();
        }
    }

    /** A plugin's recorded problem in a schema, if any. */
    Optional<Issue> issue(Connection c, String schema, String pluginId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT schema_name, plugin_id, state, version, statement, "
                + "detail, at FROM " + appQ + ".rw_issues WHERE schema_name = ? AND plugin_id = ?")) {
            ps.setString(1, schema);
            ps.setString(2, pluginId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Issue(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5), rs.getString(6), engine.instant(rs, 7))) : Optional.empty();
            }
        }
    }

    /** Clears a plugin's problem in a schema. */
    void clearIssue(Connection c, String schema, String pluginId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + appQ + ".rw_issues WHERE schema_name = ? "
                + "AND plugin_id = ?")) {
            ps.setString(1, schema);
            ps.setString(2, pluginId);
            ps.executeUpdate();
        }
    }

    /** Every recorded problem (screens read on the main pool: the setup pool may be busy with a long job). */
    public List<Issue> issues() throws SQLException {
        List<Issue> issues = new ArrayList<>();
        try (Connection c = runtime.borrow(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT schema_name, plugin_id, state, version, statement, detail, at FROM "
                     + appQ + ".rw_issues ORDER BY schema_name, plugin_id")) {
            while (rs.next()) {
                issues.add(new Issue(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                        rs.getString(6), engine.instant(rs, 7)));
            }
        }
        return issues;
    }

    /** The nodes seen within {@code window}. */
    public List<Node> nodes(Duration window) throws SQLException {
        List<Node> nodes = new ArrayList<>();
        Instant now = null;
        try (Connection c = runtime.borrow(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT node_id, host, version, started_at, seen_at, " + engine.nowSql()
                     + " FROM " + appQ + ".rw_nodes ORDER BY seen_at DESC")) {
            while (rs.next()) {
                now = engine.instant(rs, 6);
                Instant seen = engine.instant(rs, 5);
                if (seen != null && seen.isAfter(now.minus(window))) {
                    nodes.add(new Node(rs.getString(1), rs.getString(2), rs.getString(3), engine.instant(rs, 4), seen));
                }
            }
        }
        return nodes;
    }

    /** Counts live site schemas. */
    public int liveSchemaCount() throws SQLException {
        try (Connection c = runtime.borrow(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + appQ + ".rw_sites")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
