package mx.quest.database.internal;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import mx.quest.database.spi.Dialect;

/** PostgreSQL 14 or later: schemas in the configured database, transactional DDL, advisory locks. */
public final class PostgresEngine implements Engine {

    static final int MIN_VERSION_NUM = 140000;
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final Dialect dialect = new PostgresDialect();

    @Override
    public String id() {
        return "postgresql";
    }

    @Override
    public Dialect dialect() {
        return dialect;
    }

    @Override
    public int identifierMax() {
        return 63;
    }

    @Override
    public int defaultPort() {
        return 5432;
    }

    @Override
    public String quote(String name) {
        return quoteIdent(name);
    }

    @Override
    public String nowSql() {
        return "now()";
    }

    @Override
    public java.time.Instant instant(ResultSet rs, int column) throws SQLException {
        java.time.OffsetDateTime t = rs.getObject(column, java.time.OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    static String quoteIdent(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String connectionInitSql(Duration statementTimeout, String collation) {
        long ms = statementTimeout.toMillis();
        return "SET TIME ZONE 'UTC'; SET statement_timeout = " + ms + "; SET lock_timeout = " + ms;
    }

    @Override
    public String inspectServer(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT current_setting('server_version_num')::int, "
                     + "current_setting('server_version'), current_setting('server_encoding')")) {
            rs.next();
            int num = rs.getInt(1);
            String version = rs.getString(2);
            String encoding = rs.getString(3);
            if (num < MIN_VERSION_NUM) {
                throw new UnsupportedServerException("PostgreSQL " + version + " is too old: 14 or later is needed");
            }
            if (!"UTF8".equalsIgnoreCase(encoding)) {
                throw new UnsupportedServerException("the database's encoding is " + encoding + ": UTF8 is needed");
            }
            return "PostgreSQL " + version;
        }
    }

    // --- binding --------------------------------------------------------------------------------------------

    private static String searchPath(String schema) {
        return quoteIdent(schema) + ", pg_temp";
    }

    @Override
    public boolean bindSite(Connection c, String appSchema, String siteId, String schema, Duration timeout)
            throws SQLException {
        String sql = "SELECT set_config('search_path', ?, false), set_config('statement_timeout', ?, false) FROM "
                + quoteIdent(appSchema) + ".rw_sites WHERE site_id = ? AND schema_name = ? AND state = 'live'";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, searchPath(schema));
            ps.setString(2, Long.toString(timeout.toMillis()));
            ps.setString(3, siteId);
            ps.setString(4, schema);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public void bind(Connection c, String schema, Duration timeout) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT set_config('search_path', ?, false), set_config('statement_timeout', ?, false)")) {
            ps.setString(1, searchPath(schema));
            ps.setString(2, Long.toString(timeout.toMillis()));
            ps.executeQuery().close();
        }
    }

    @Override
    public void reset(Connection c) throws SQLException {
        // Never DISCARD ALL: it would drop the driver's prepared statements behind its back.
        try (Statement s = c.createStatement()) {
            s.execute("DISCARD TEMP");
        }
    }

    @Override
    public void beginRead(Connection c) throws SQLException {
        // The driver opens the transaction READ ONLY at the REPEATABLE READ level the caller set.
    }

    @Override
    public boolean stillBound(Connection c, String schema) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT current_setting('search_path')")) {
            rs.next();
            return searchPath(schema).equals(rs.getString(1));
        }
    }

    // --- schemas --------------------------------------------------------------------------------------------

    @Override
    public void createSchema(Connection c, String schema, String collation) throws SQLException {
        execute(c, "CREATE SCHEMA IF NOT EXISTS " + quoteIdent(schema));
    }

    @Override
    public void dropSchema(Connection c, String schema) throws SQLException {
        execute(c, "DROP SCHEMA IF EXISTS " + quoteIdent(schema) + " CASCADE");
    }

    @Override
    public List<String> listSchemas(Connection c, String namespace) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT nspname FROM pg_namespace WHERE nspname LIKE ? ESCAPE '\\' ORDER BY nspname")) {
            ps.setString(1, namespace + "\\_%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    @Override
    public List<String> registryDdl(String appSchema) {
        String a = quoteIdent(appSchema);
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + a + ".rw_install (key varchar(64) PRIMARY KEY, "
                        + "value varchar(255) NOT NULL)",
                "CREATE SEQUENCE IF NOT EXISTS " + a + ".rw_generation",
                "CREATE TABLE IF NOT EXISTS " + a + ".rw_sites (site_id varchar(255) PRIMARY KEY, "
                        + "schema_name varchar(64) NOT NULL UNIQUE, token char(32) NOT NULL, "
                        + "generation bigint NOT NULL, state varchar(16) NOT NULL, created_at timestamptz NOT NULL)",
                "CREATE TABLE IF NOT EXISTS " + a + ".rw_schemas (schema_name varchar(64) PRIMARY KEY, "
                        + "site_id varchar(255) NOT NULL, generation bigint NOT NULL, state varchar(16) NOT NULL, "
                        + "changed_at timestamptz NOT NULL)",
                "CREATE TABLE IF NOT EXISTS " + a + ".rw_plugins (plugin_id varchar(255) PRIMARY KEY, "
                        + "prefix varchar(24) NOT NULL UNIQUE, first_seen timestamptz NOT NULL)",
                "CREATE TABLE IF NOT EXISTS " + a + ".rw_nodes (node_id varchar(64) PRIMARY KEY, "
                        + "host varchar(255) NOT NULL, version varchar(32) NOT NULL, "
                        + "started_at timestamptz NOT NULL, seen_at timestamptz NOT NULL)",
                "CREATE TABLE IF NOT EXISTS " + a + ".rw_issues (schema_name varchar(64) NOT NULL, "
                        + "plugin_id varchar(255) NOT NULL, state varchar(16) NOT NULL, version integer NOT NULL, "
                        + "statement integer NOT NULL, detail varchar(1000) NOT NULL, at timestamptz NOT NULL, "
                        + "PRIMARY KEY (schema_name, plugin_id))");
    }

    @Override
    public String migrationsDdl(String schema) {
        return "CREATE TABLE IF NOT EXISTS " + quoteIdent(schema) + ".rw_migrations (plugin_id varchar(255) NOT NULL, "
                + "version integer NOT NULL, name varchar(128) NOT NULL, checksum char(64) NOT NULL, "
                + "state varchar(16) NOT NULL, last_ok_statement integer NOT NULL, applied_at timestamptz, "
                + "PRIMARY KEY (plugin_id, version))";
    }

    @Override
    public long nextGeneration(Connection c, String appSchema) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT nextval(?::regclass)")) {
            ps.setString(1, quoteIdent(appSchema) + ".rw_generation");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // --- locks ------------------------------------------------------------------------------------------------

    /** A 64-bit advisory lock key from the lock name. */
    static long lockKey(String key) {
        return ByteBuffer.wrap(HexFormat.of().parseHex(Names.sha256Hex(key)), 0, 8).getLong();
    }

    @Override
    public boolean lock(Connection c, String key, Duration wait) throws SQLException {
        execute(c, "SET lock_timeout = " + Math.max(1, wait.toMillis()));
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_lock(?)")) {
            ps.setLong(1, lockKey(key));
            ps.executeQuery().close();
            return true;
        } catch (SQLException e) {
            if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void unlock(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, lockKey(key));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !rs.getBoolean(1)) {
                    throw new SQLException("advisory lock was not held: " + key);
                }
            }
        }
    }

    @Override
    public boolean migrationLock(Connection c, String key, Duration wait) throws SQLException {
        execute(c, "SET LOCAL lock_timeout = " + Math.max(1, wait.toMillis()));
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            ps.setLong(1, lockKey(key));
            ps.executeQuery().close();
            return true;
        } catch (SQLException e) {
            if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void migrationUnlock(Connection c, String key) {
        // Released by the transaction's end.
    }

    @Override
    public boolean transactionalDdl() {
        return true;
    }

    @Override
    public void setLongTimeout(Connection c, Duration timeout) throws SQLException {
        long ms = timeout.toMillis();
        execute(c, "SET statement_timeout = " + ms + "; SET lock_timeout = " + ms);
    }

    // --- ownership --------------------------------------------------------------------------------------------

    private static final String NOT_FROM_EXTENSION = " AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = "
            + "'%s'::regclass AND d.objid = %s AND d.deptype = 'e')";

    @Override
    public Set<DbObject> ownedObjects(Connection c, String schema) throws SQLException {
        Set<DbObject> objects = new LinkedHashSet<>();
        // Relations: tables, partitioned tables, indexes (so every constraint that creates one), sequences, views,
        // materialized views, foreign tables and composite types.
        collect(c, schema, objects, "relation", "SELECT c.relname FROM pg_class c JOIN pg_namespace n "
                + "ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relkind IN ('r','p','i','I','S','v','m','f','c')"
                + NOT_FROM_EXTENSION.formatted("pg_class", "c.oid"));
        collect(c, schema, objects, "routine", "SELECT p.proname FROM pg_proc p JOIN pg_namespace n "
                + "ON n.oid = p.pronamespace WHERE n.nspname = ?" + NOT_FROM_EXTENSION.formatted("pg_proc", "p.oid"));
        // Types other than a relation's row type and array types: domains, enums, ranges (and their multiranges).
        collect(c, schema, objects, "type", "SELECT t.typname FROM pg_type t JOIN pg_namespace n "
                + "ON n.oid = t.typnamespace WHERE n.nspname = ? AND t.typrelid = 0 AND t.typcategory <> 'A'"
                + NOT_FROM_EXTENSION.formatted("pg_type", "t.oid"));
        return objects;
    }

    private static void collect(Connection c, String schema, Set<DbObject> into, String kind, String sql)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    into.add(new DbObject(kind, rs.getString(1), null));
                }
            }
        }
    }

    @Override
    public List<String> tablesWithWrongEngine(Connection c, String schema) {
        return List.of();
    }

    @Override
    public void dropObject(Connection c, String schema, DbObject object) {
        throw new UnsupportedOperationException("PostgreSQL migrations roll back instead");
    }

    // --- backup and restore ------------------------------------------------------------------------------------

    @Override
    public java.util.Map<String, List<Column>> tables(Connection c, String schema) throws SQLException {
        java.util.Map<String, List<Column>> tables = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT c.table_name, c.column_name, c.data_type, "
                + "c.character_maximum_length, c.numeric_precision, c.numeric_scale, c.is_nullable, c.column_default, "
                + "c.is_identity, c.is_generated FROM information_schema.columns c JOIN information_schema.tables t "
                + "ON t.table_schema = c.table_schema AND t.table_name = c.table_name "
                + "WHERE c.table_schema = ? AND t.table_type = 'BASE TABLE' "
                + "ORDER BY c.table_name, c.ordinal_position")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if ("ALWAYS".equals(rs.getString(10))) {
                        continue; // a generated column is derived: never backed up, never loaded
                    }
                    String type = portableType(rs.getString(3), (Integer) rs.getObject(4), (Integer) rs.getObject(5),
                            (Integer) rs.getObject(6));
                    boolean hasDefault = rs.getString(8) != null || "YES".equals(rs.getString(9));
                    tables.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new Column(rs.getString(2),
                            type, "YES".equals(rs.getString(7)), hasDefault));
                }
            }
        }
        return tables;
    }

    static String portableType(String dataType, Integer length, Integer precision, Integer scale) {
        return switch (dataType) {
            case "bigint" -> "BIGINT";
            case "integer" -> "INTEGER";
            case "smallint" -> "SMALLINT";
            case "numeric" -> precision == null ? "DECIMAL" : "DECIMAL(" + precision + "," + (scale == null ? 0 : scale)
                    + ")";
            case "double precision", "real" -> "DOUBLE";
            case "boolean" -> "BOOLEAN";
            case "character varying", "character" -> length == null ? "TEXT" : "VARCHAR(" + length + ")";
            case "text" -> "TEXT";
            case "date" -> "DATE";
            case "time without time zone" -> "TIME";
            case "timestamp without time zone" -> "TIMESTAMP";
            case "timestamp with time zone" -> "TIMESTAMPTZ";
            case "bytea" -> "BYTES";
            case "json", "jsonb" -> "JSON";
            default -> Portable.UNSUPPORTED + dataType;
        };
    }

    @Override
    public List<Sequence> sequences(Connection c, String schema) throws SQLException {
        List<Sequence> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT sequencename, last_value, start_value, increment_by "
                + "FROM pg_sequences WHERE schemaname = ? ORDER BY sequencename")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long last = rs.getLong(2);
                    boolean called = !rs.wasNull();
                    long increment = rs.getLong(4);
                    out.add(new Sequence(rs.getString(1), called ? last + increment : rs.getLong(3), increment));
                }
            }
        }
        return out;
    }

    @Override
    public void setSequence(Connection c, String schema, String name, long next) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT setval(?::regclass, ?, false)")) {
            ps.setString(1, quoteIdent(schema) + "." + quoteIdent(name));
            ps.setLong(2, next);
            ps.executeQuery().close();
        }
    }

    @Override
    public List<String> suspendForLoad(Connection c, String schema, java.util.Collection<String> tables)
            throws SQLException {
        List<String> restore = new ArrayList<>();
        String s = quoteIdent(schema) + ".";
        java.sql.Array names = c.createArrayOf("text", tables.toArray());
        try (PreparedStatement ps = c.prepareStatement("SELECT t.relname, con.conname, pg_get_constraintdef(con.oid) "
                + "FROM pg_constraint con JOIN pg_class t ON t.oid = con.conrelid "
                + "JOIN pg_class r ON r.oid = con.confrelid JOIN pg_namespace n ON n.oid = con.connamespace "
                + "WHERE con.contype = 'f' AND n.nspname = ? AND (t.relname = ANY(?) OR r.relname = ANY(?)) "
                + "ORDER BY t.relname, con.conname")) {
            ps.setString(1, schema);
            ps.setArray(2, names);
            ps.setArray(3, names);
            List<String[]> fks = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    fks.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
                }
            }
            for (String[] fk : fks) {
                execute(c, "ALTER TABLE " + s + quoteIdent(fk[0]) + " DROP CONSTRAINT " + quoteIdent(fk[1]));
                restore.add("ALTER TABLE " + s + quoteIdent(fk[0]) + " ADD CONSTRAINT " + quoteIdent(fk[1]) + " "
                        + fk[2]);
            }
        }
        for (String table : tables) {
            execute(c, "ALTER TABLE " + s + quoteIdent(table) + " DISABLE TRIGGER USER");
            restore.add("ALTER TABLE " + s + quoteIdent(table) + " ENABLE TRIGGER USER");
        }
        return restore;
    }

    @Override
    public void bindValue(PreparedStatement ps, int index, String portableType, Object value) throws SQLException {
        Portable.bind(ps, index, portableType, value, true);
    }

    @Override
    public Object readValue(ResultSet rs, int index, String portableType) throws SQLException {
        return Portable.read(rs, index, portableType, true);
    }

    @Override
    public long schemaSize(Connection c, String schema) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(pg_total_relation_size(c.oid)), 0) FROM pg_class c JOIN pg_namespace n "
                + "ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relkind IN ('r', 'p', 'm')")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // --- failure categories -----------------------------------------------------------------------------------

    @Override
    public ConnectFailure classify(SQLException failure) {
        if (failure instanceof UnsupportedServerException) {
            return ConnectFailure.UNSUPPORTED_SERVER;
        }
        if (Failures.hasTlsCause(failure)) {
            return ConnectFailure.TLS;
        }
        String state = failure.getSQLState() == null ? "" : failure.getSQLState();
        // 08004 also when TLS was asked of a server that has it off: pgjdbc says "The server does not support SSL."
        // (translated with the JVM's locale, but "SSL" stays), which is a TLS problem, not a refused password.
        if (state.equals("08004") && failure.getMessage() != null && failure.getMessage().contains("SSL")) {
            return ConnectFailure.TLS;
        }
        // 08004: the driver itself refuses, e.g. an empty password when the server asks for one.
        if (state.equals("28P01") || state.equals("28000") || state.equals("08004")) {
            return ConnectFailure.AUTHENTICATION;
        }
        if (state.equals("3D000")) {
            return ConnectFailure.DATABASE_MISSING;
        }
        if (state.startsWith("08") || Failures.hasNetworkCause(failure)) {
            return ConnectFailure.UNREACHABLE;
        }
        return ConnectFailure.OTHER;
    }

    static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** {@code nextval}/{@code currval} on a sequence in the bound schema. */
    static final class PostgresDialect implements Dialect {

        @Override
        public String id() {
            return "postgresql";
        }

        @Override
        public String nextValue(String sequence) {
            return "nextval('" + Failures.requireSimpleName(sequence) + "')";
        }

        @Override
        public long lastValue(Connection connection, String sequence) throws SQLException {
            try (Statement s = connection.createStatement();
                 ResultSet rs = s.executeQuery("SELECT currval('" + Failures.requireSimpleName(sequence) + "')")) {
                rs.next();
                return rs.getLong(1);
            }
        }

        @Override
        public String quote(String name) {
            return quoteIdent(name);
        }
    }
}
