package mx.quest.database.internal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.quest.database.spi.Dialect;

/**
 * MariaDB 10.11 or later (never MySQL): a database per schema, DDL that commits on its own, {@code GET_LOCK}. Runway's
 * own identifier columns are {@code ascii_bin}, so a site id never matches another by case.
 */
public final class MariaDbEngine implements Engine {

    static final int MIN_MAJOR = 10;
    static final int MIN_MINOR = 11;
    static final String SQL_MODE = "STRICT_ALL_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,ERROR_FOR_DIVISION_BY_ZERO,"
            + "NO_ENGINE_SUBSTITUTION";
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final String ID = "VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin";
    private static final String SCHEMA_NAME = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin";

    private final Dialect dialect = new MariaDbDialect();

    /** The collation used when none is configured: on every supported server (10.10 brought it). */
    private static final String DEFAULT_COLLATION = "utf8mb4_uca1400_ai_ci";

    @Override
    public String id() {
        return "mariadb";
    }

    @Override
    public Dialect dialect() {
        return dialect;
    }

    @Override
    public int identifierMax() {
        return 64;
    }

    @Override
    public int defaultPort() {
        return 3306;
    }

    @Override
    public String quote(String name) {
        return quoteIdent(name);
    }

    @Override
    public String nowSql() {
        return "UTC_TIMESTAMP(6)";
    }

    @Override
    public java.time.Instant instant(ResultSet rs, int column) throws SQLException {
        java.time.LocalDateTime t = rs.getObject(column, java.time.LocalDateTime.class);
        return t == null ? null : t.toInstant(java.time.ZoneOffset.UTC); // the session time zone is pinned to UTC
    }

    static String quoteIdent(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    private static String seconds(Duration d) {
        return Long.toString(Math.max(1, d.toSeconds()));
    }

    @Override
    public String connectionInitSql(Duration statementTimeout, String collation) {
        // explicit_defaults_for_timestamp: on by default since MariaDB 10.10, but a server can still be configured
        // off, and then the first TIMESTAMP NOT NULL column of a table a plugin creates silently gains DEFAULT
        // CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, so a created_at column would change on every UPDATE. Set
        // here, a script means the same on every supported server (a session can set it from 10.10, hence 10.11).
        return "SET time_zone = '+00:00', sql_mode = '" + SQL_MODE + "', default_storage_engine = InnoDB, "
                + "explicit_defaults_for_timestamp = 1, "
                + "max_statement_time = " + seconds(statementTimeout) + ", innodb_lock_wait_timeout = "
                + seconds(statementTimeout);
    }

    @Override
    public String inspectServer(Connection c) throws SQLException {
        String product = c.getMetaData().getDatabaseProductName();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT VERSION(), @@version_comment")) {
            rs.next();
            return checkServer(product, rs.getString(1), rs.getString(2));
        }
    }

    /**
     * MariaDB 10.11 or later, and not MySQL. 10.11 is the floor because before 10.10 a session cannot set
     * {@code explicit_defaults_for_timestamp}, which {@link #connectionInitSql} sets on every connection: on an older
     * server every connection would fail with a message about a read-only variable, not about the version.
     */
    static String checkServer(String product, String version, String comment) throws UnsupportedServerException {
        String note = comment == null ? "" : comment;
        if (!"MariaDB".equalsIgnoreCase(product) || version == null || !version.contains("MariaDB")
                || note.toLowerCase(Locale.ROOT).contains("mysql")) {
            throw new UnsupportedServerException("the server is not MariaDB (" + version + ", " + note
                    + "): MySQL is not supported");
        }
        Matcher m = VERSION.matcher(version);
        if (!m.find()) {
            throw new UnsupportedServerException("cannot read the MariaDB version: " + version);
        }
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        if (major < MIN_MAJOR || (major == MIN_MAJOR && minor < MIN_MINOR)) {
            throw new UnsupportedServerException("MariaDB " + version + " is too old: 10.11 or later is needed");
        }
        return "MariaDB " + m.group();
    }

    // --- binding --------------------------------------------------------------------------------------------

    @Override
    public boolean bindSite(Connection c, String appSchema, String siteId, String schema, Duration timeout)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + quoteIdent(appSchema)
                + ".rw_sites WHERE site_id = ? AND schema_name = ? AND state = 'live'")) {
            ps.setString(1, siteId);
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
            }
        }
        use(c, schema, timeout);
        return true;
    }

    @Override
    public void bind(Connection c, String schema, Duration timeout) throws SQLException {
        use(c, schema, timeout);
    }

    /** An explicit USE, never setCatalog: the driver skips that when it believes it is already there. */
    private static void use(Connection c, String schema, Duration timeout) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("USE " + quoteIdent(schema));
            s.execute("SET max_statement_time = " + seconds(timeout) + ", innodb_lock_wait_timeout = "
                    + seconds(timeout));
        }
    }

    @Override
    public void reset(Connection c) {
        // MariaDB has no DISCARD: a consumer drops its temporary tables in finally (the authoring guide says so).
    }

    @Override
    public void beginRead(Connection c) throws SQLException {
        // setReadOnly does nothing on a single-host URL, so start the read-only snapshot explicitly.
        execute(c, "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");
    }

    @Override
    public boolean stillBound(Connection c, String schema) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT DATABASE()")) {
            rs.next();
            return schema.equals(rs.getString(1));
        }
    }

    // --- schemas --------------------------------------------------------------------------------------------

    /** The collation for consumer tables: the configured one, else the server's best. */
    String collationOr(String configured) {
        return configured == null ? DEFAULT_COLLATION : configured;
    }

    @Override
    public void createSchema(Connection c, String schema, String collation) throws SQLException {
        execute(c, "CREATE DATABASE IF NOT EXISTS " + quoteIdent(schema) + " CHARACTER SET utf8mb4 COLLATE "
                + Failures.requireSimpleName(collationOr(collation)));
    }

    @Override
    public void dropSchema(Connection c, String schema) throws SQLException {
        execute(c, "DROP DATABASE IF EXISTS " + quoteIdent(schema));
    }

    @Override
    public List<String> listSchemas(Connection c, String namespace) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA "
                + "WHERE SCHEMA_NAME LIKE ? ORDER BY SCHEMA_NAME")) {
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
        String table = " ENGINE=InnoDB";
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + a + ".rw_install (`key` VARCHAR(64) CHARACTER SET ascii COLLATE "
                        + "ascii_bin PRIMARY KEY, value VARCHAR(255) NOT NULL)" + table,
                "CREATE SEQUENCE IF NOT EXISTS " + a + ".rw_generation",
                "CREATE TABLE IF NOT EXISTS " + a + ".rw_sites (site_id " + ID + " PRIMARY KEY, schema_name "
                        + SCHEMA_NAME + " NOT NULL UNIQUE, token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin "
                        + "NOT NULL, generation BIGINT NOT NULL, state VARCHAR(16) CHARACTER SET ascii COLLATE "
                        + "ascii_bin NOT NULL, created_at DATETIME(6) NOT NULL)" + table,
                "CREATE TABLE IF NOT EXISTS " + a + ".rw_schemas (schema_name " + SCHEMA_NAME + " PRIMARY KEY, "
                        + "site_id " + ID + " NOT NULL, generation BIGINT NOT NULL, state VARCHAR(16) CHARACTER SET "
                        + "ascii COLLATE ascii_bin NOT NULL, changed_at DATETIME(6) NOT NULL)" + table,
                "CREATE TABLE IF NOT EXISTS " + a + ".rw_plugins (plugin_id " + ID + " PRIMARY KEY, prefix "
                        + "VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE, "
                        + "first_seen DATETIME(6) NOT NULL)" + table,
                "CREATE TABLE IF NOT EXISTS " + a + ".rw_nodes (node_id VARCHAR(64) CHARACTER SET ascii COLLATE "
                        + "ascii_bin PRIMARY KEY, host VARCHAR(255) NOT NULL, version VARCHAR(32) NOT NULL, "
                        + "started_at DATETIME(6) NOT NULL, seen_at DATETIME(6) NOT NULL)" + table,
                "CREATE TABLE IF NOT EXISTS " + a + ".rw_issues (schema_name " + SCHEMA_NAME + " NOT NULL, "
                        + "plugin_id " + ID + " NOT NULL, state VARCHAR(16) NOT NULL, version INT NOT NULL, "
                        + "statement INT NOT NULL, detail VARCHAR(1000) NOT NULL, at DATETIME(6) NOT NULL, "
                        + "PRIMARY KEY (schema_name, plugin_id))" + table);
    }

    @Override
    public String migrationsDdl(String schema) {
        return "CREATE TABLE IF NOT EXISTS " + quoteIdent(schema) + ".rw_migrations (plugin_id " + ID + " NOT NULL, "
                + "version INT NOT NULL, name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, "
                + "checksum CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, state VARCHAR(16) CHARACTER "
                + "SET ascii COLLATE ascii_bin NOT NULL, last_ok_statement INT NOT NULL, applied_at DATETIME(6), "
                + "PRIMARY KEY (plugin_id, version)) ENGINE=InnoDB";
    }

    @Override
    public long nextGeneration(Connection c, String appSchema) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT NEXTVAL(" + quoteIdent(appSchema) + ".rw_generation)")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // --- locks ------------------------------------------------------------------------------------------------

    /** GET_LOCK names are at most 64 characters: the namespace part stays readable, the rest is hashed. */
    static String lockName(String key) {
        int colon = key.indexOf(':');
        String head = colon < 0 ? "" : key.substring(0, Math.min(colon, 12)) + ":";
        return head + Names.sha256Hex(key).substring(0, 40);
    }

    @Override
    public boolean lock(Connection c, String key, Duration wait) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            ps.setString(1, lockName(key));
            ps.setDouble(2, Math.max(0.001, wait.toMillis() / 1000.0));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int got = rs.getInt(1);
                if (rs.wasNull()) {
                    throw new SQLException("GET_LOCK failed for " + key);
                }
                return got == 1;
            }
        }
    }

    @Override
    public void unlock(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, lockName(key));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(1) != 1) {
                    throw new SQLException("lock was not held: " + key);
                }
            }
        }
    }

    @Override
    public boolean migrationLock(Connection c, String key, Duration wait) throws SQLException {
        return lock(c, key, wait);
    }

    @Override
    public void migrationUnlock(Connection c, String key) throws SQLException {
        unlock(c, key);
    }

    @Override
    public boolean transactionalDdl() {
        return false;
    }

    @Override
    public void setLongTimeout(Connection c, Duration timeout) throws SQLException {
        execute(c, "SET max_statement_time = " + seconds(timeout) + ", innodb_lock_wait_timeout = "
                + seconds(timeout));
    }

    // --- ownership --------------------------------------------------------------------------------------------

    @Override
    public Set<DbObject> ownedObjects(Connection c, String schema) throws SQLException {
        Set<DbObject> objects = new LinkedHashSet<>();
        // Tables, views and sequences share one name space; the type decides how to drop one.
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString(2);
                    String kind = "VIEW".equals(type) ? "view" : "SEQUENCE".equals(type) ? "sequence" : "table";
                    objects.add(new DbObject(kind, rs.getString(1), null));
                }
            }
        }
        collect(c, objects, "SELECT 'trigger', TRIGGER_NAME, NULL FROM information_schema.TRIGGERS "
                + "WHERE TRIGGER_SCHEMA = ?", schema);
        collect(c, objects, "SELECT LOWER(ROUTINE_TYPE), ROUTINE_NAME, NULL FROM information_schema.ROUTINES "
                + "WHERE ROUTINE_SCHEMA = ?", schema);
        collect(c, objects, "SELECT 'event', EVENT_NAME, NULL FROM information_schema.EVENTS "
                + "WHERE EVENT_SCHEMA = ?", schema);
        collect(c, objects, "SELECT 'fk', CONSTRAINT_NAME, TABLE_NAME FROM information_schema.REFERENTIAL_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA = ?", schema);
        return objects;
    }

    private static void collect(Connection c, Set<DbObject> into, String sql, String schema) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    into.add(new DbObject(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
    }

    @Override
    public List<String> tablesWithWrongEngine(Connection c, String schema) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' AND (ENGINE IS NULL OR ENGINE <> 'InnoDB')")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        return tables;
    }

    @Override
    public void dropObject(Connection c, String schema, DbObject object) throws SQLException {
        String s = quoteIdent(schema) + ".";
        String n = quoteIdent(object.name());
        String sql = switch (object.kind()) {
            case "table" -> "DROP TABLE IF EXISTS " + s + n;
            case "view" -> "DROP VIEW IF EXISTS " + s + n;
            case "sequence" -> "DROP SEQUENCE IF EXISTS " + s + n;
            case "trigger" -> "DROP TRIGGER IF EXISTS " + s + n;
            case "procedure" -> "DROP PROCEDURE IF EXISTS " + s + n;
            case "function" -> "DROP FUNCTION IF EXISTS " + s + n;
            case "event" -> "DROP EVENT IF EXISTS " + s + n;
            case "fk" -> "ALTER TABLE " + s + quoteIdent(object.table()) + " DROP FOREIGN KEY " + n;
            default -> throw new SQLException("cannot drop an object of kind " + object.kind());
        };
        execute(c, sql);
    }

    // --- backup and restore ------------------------------------------------------------------------------------

    @Override
    public java.util.Map<String, List<Column>> tables(Connection c, String schema) throws SQLException {
        java.util.Map<String, List<Column>> tables = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT c.TABLE_NAME, c.COLUMN_NAME, c.DATA_TYPE, "
                + "c.COLUMN_TYPE, c.CHARACTER_MAXIMUM_LENGTH, c.NUMERIC_PRECISION, c.NUMERIC_SCALE, c.IS_NULLABLE, "
                + "c.COLUMN_DEFAULT, c.EXTRA FROM information_schema.COLUMNS c JOIN information_schema.TABLES t "
                + "ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME "
                + "WHERE c.TABLE_SCHEMA = ? AND t.TABLE_TYPE = 'BASE TABLE' "
                + "ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String extra = rs.getString(10) == null ? "" : rs.getString(10).toLowerCase(Locale.ROOT);
                    if (extra.contains("generated")) {
                        continue; // a generated column is derived: never backed up, never loaded
                    }
                    Long length = toLong(rs.getObject(5));
                    Long precision = toLong(rs.getObject(6));
                    Long scale = toLong(rs.getObject(7));
                    String type = portableType(rs.getString(3), rs.getString(4), length, precision, scale);
                    String def = rs.getString(9);
                    boolean hasDefault = (def != null && !def.equalsIgnoreCase("NULL"))
                            || extra.contains("auto_increment");
                    tables.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new Column(rs.getString(2),
                            type, "YES".equals(rs.getString(8)), hasDefault));
                }
            }
        }
        return tables;
    }

    private static Long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    static String portableType(String dataType, String columnType, Long length, Long precision, Long scale) {
        String type = dataType.toLowerCase(Locale.ROOT);
        String column = columnType == null ? "" : columnType.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "bigint" -> "BIGINT";
            case "int", "mediumint" -> "INTEGER";
            case "smallint" -> "SMALLINT";
            case "tinyint" -> column.startsWith("tinyint(1)") ? "BOOLEAN" : "SMALLINT";
            case "decimal" -> "DECIMAL(" + precision + "," + (scale == null ? 0 : scale) + ")";
            case "double", "float" -> "DOUBLE";
            case "varchar", "char" -> "VARCHAR(" + length + ")";
            case "text", "tinytext", "mediumtext", "longtext" -> "TEXT";
            case "date" -> "DATE";
            case "time" -> "TIME";
            case "datetime" -> "TIMESTAMP";
            case "timestamp" -> "TIMESTAMPTZ";
            case "blob", "tinyblob", "mediumblob", "longblob", "varbinary", "binary" -> "BYTES";
            default -> Portable.UNSUPPORTED + type;
        };
    }

    @Override
    public List<Sequence> sequences(Connection c, String schema) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'SEQUENCE' ORDER BY TABLE_NAME")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        List<Sequence> out = new ArrayList<>();
        for (String name : names) {
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT next_not_cached_value, increment FROM " + quoteIdent(schema)
                         + "." + quoteIdent(name))) {
                rs.next();
                out.add(new Sequence(name, rs.getLong(1), rs.getLong(2)));
            }
        }
        return out;
    }

    @Override
    public void setSequence(Connection c, String schema, String name, long next) throws SQLException {
        execute(c, "ALTER SEQUENCE " + quoteIdent(schema) + "." + quoteIdent(name) + " RESTART WITH " + next);
    }

    @Override
    public List<String> suspendForLoad(Connection c, String schema, java.util.Collection<String> tables)
            throws SQLException {
        List<String> restore = new ArrayList<>();
        restore.add("SET FOREIGN_KEY_CHECKS = 1");
        if (!tables.isEmpty()) {
            String marks = String.join(", ", java.util.Collections.nCopies(tables.size(), "?"));
            List<String> triggers = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS "
                    + "WHERE TRIGGER_SCHEMA = ? AND EVENT_OBJECT_TABLE IN (" + marks + ") ORDER BY TRIGGER_NAME")) {
                ps.setString(1, schema);
                int i = 2;
                for (String t : tables) {
                    ps.setString(i++, t);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        triggers.add(rs.getString(1));
                    }
                }
            }
            for (String trigger : triggers) {
                String qualified = quoteIdent(schema) + "." + quoteIdent(trigger);
                try (Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SHOW CREATE TRIGGER " + qualified)) {
                    rs.next();
                    restore.add(rs.getString("SQL Original Statement"));
                }
                execute(c, "DROP TRIGGER " + qualified);
            }
        }
        execute(c, "SET FOREIGN_KEY_CHECKS = 0");
        return restore;
    }

    @Override
    public void bindValue(PreparedStatement ps, int index, String portableType, Object value) throws SQLException {
        Portable.bind(ps, index, portableType, value, false);
    }

    @Override
    public Object readValue(ResultSet rs, int index, String portableType) throws SQLException {
        return Portable.read(rs, index, portableType, false);
    }

    @Override
    public long schemaSize(Connection c, String schema) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ?")) {
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
        int code = failure.getErrorCode();
        String state = failure.getSQLState() == null ? "" : failure.getSQLState();
        if (code == 1045 || state.equals("28000")) {
            return ConnectFailure.AUTHENTICATION;
        }
        // 1049 unknown database; 1044 no access to it, which is also what a missing one looks like to an account
        // without global rights.
        if (code == 1049 || code == 1044) {
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

    /** {@code NEXTVAL}/{@code LASTVAL} on a sequence in the bound database. */
    static final class MariaDbDialect implements Dialect {

        @Override
        public String id() {
            return "mariadb";
        }

        @Override
        public String nextValue(String sequence) {
            return "NEXTVAL(" + Failures.requireSimpleName(sequence) + ")";
        }

        @Override
        public long lastValue(Connection connection, String sequence) throws SQLException {
            try (Statement s = connection.createStatement();
                 ResultSet rs = s.executeQuery("SELECT LASTVAL(" + Failures.requireSimpleName(sequence) + ")")) {
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
