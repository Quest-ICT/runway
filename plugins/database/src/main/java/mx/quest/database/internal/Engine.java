package mx.quest.database.internal;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import mx.quest.database.spi.Dialect;

/**
 * Every statement the database plugin runs, for one product. Providers bring the driver; the SQL lives here, so the
 * checks that keep installs, sites and plugins apart are written once per product and reviewed in one place.
 *
 * <p>Schema and object names reaching these methods have already passed {@link Names}; they are still quoted
 * through {@link #quote}, never concatenated raw.
 */
public interface Engine {

    /** {@code postgresql} or {@code mariadb}. */
    String id();

    /** The consumer-facing helpers. */
    Dialect dialect();

    /** The longest identifier the product accepts, in bytes (all our names are ASCII). */
    int identifierMax();

    /** The product's default port. */
    int defaultPort();

    /** A quoted identifier. */
    String quote(String name);

    /** The SQL for the current instant, stored as UTC. */
    String nowSql();

    /** Reads an instant written with {@link #nowSql()}; {@code null} for SQL NULL. */
    java.time.Instant instant(java.sql.ResultSet rs, int column) throws SQLException;

    /** The SQL pinned on every new connection: time zone, strict modes, the session statement timeout. */
    String connectionInitSql(Duration statementTimeout, String collation);

    /**
     * Checks the server and fails closed.
     *
     * @return the product and version for the health screen
     * @throws UnsupportedServerException when the product, version or encoding is not supported
     */
    String inspectServer(Connection c) throws SQLException;

    // --- binding --------------------------------------------------------------------------------------------

    /**
     * Binds a borrowed connection to a site's schema and sets the statement timeout, checking in the same step
     * that the registry still maps the site to that schema.
     *
     * @return false when the mapping changed (the caller re-resolves); the connection is then unbound
     */
    boolean bindSite(Connection c, String appSchema, String siteId, String schema, Duration timeout)
            throws SQLException;

    /** Binds a connection to a schema and sets the statement timeout, without a registry check (the app schema,
     *  or a setup connection about to migrate or restore a schema it already resolved). */
    void bind(Connection c, String schema, Duration timeout) throws SQLException;

    /** Makes a connection fit for the next borrower: drops temporary objects where the product can. */
    void reset(Connection c) throws SQLException;

    /** Starts a read-only transaction with a consistent snapshot (autocommit is already off). */
    void beginRead(Connection c) throws SQLException;

    /** Whether the connection is still bound exactly as {@link #bindSite} or {@link #bindApp} left it, to detect a
     *  script that moved it. */
    boolean stillBound(Connection c, String schema) throws SQLException;

    // --- schemas --------------------------------------------------------------------------------------------

    /** Creates a schema (a database, in MariaDB) if it does not exist. */
    void createSchema(Connection c, String schema, String collation) throws SQLException;

    /** Drops a schema and everything in it, if it exists. */
    void dropSchema(Connection c, String schema) throws SQLException;

    /** The schemas whose names start with {@code <namespace>_}. */
    List<String> listSchemas(Connection c, String namespace) throws SQLException;

    /** The registry tables in the app schema, created if missing; idempotent. */
    List<String> registryDdl(String appSchema);

    /** The {@code rw_migrations} table in a schema, created if missing; idempotent. */
    String migrationsDdl(String schema);

    /** The next value of the install-wide generation sequence. */
    long nextGeneration(Connection c, String appSchema) throws SQLException;

    // --- locks ------------------------------------------------------------------------------------------------

    /**
     * Takes a session-level lock on a dedicated connection, waiting at most {@code wait}.
     *
     * @return false on timeout
     */
    boolean lock(Connection c, String key, Duration wait) throws SQLException;

    /** Releases a lock taken with {@link #lock}; a failure means the caller must discard the connection. */
    void unlock(Connection c, String key) throws SQLException;

    /** Takes the migration lock for (schema, plugin) on the migration connection, inside its transaction where the
     *  product has one. */
    boolean migrationLock(Connection c, String key, Duration wait) throws SQLException;

    /** Releases the migration lock where it is not released by the transaction's end. */
    void migrationUnlock(Connection c, String key) throws SQLException;

    /** Whether DDL is transactional, so a whole plugin's scripts run in one transaction. */
    boolean transactionalDdl();

    /** Sets the timeout for long work (migrations, backup, restore) on a setup connection. */
    void setLongTimeout(Connection c, Duration timeout) throws SQLException;

    // --- ownership --------------------------------------------------------------------------------------------

    /** The objects in a schema whose names must carry a plugin prefix (see the design's ownership table). */
    Set<DbObject> ownedObjects(Connection c, String schema) throws SQLException;

    /** Base tables in a schema not using the required storage engine; empty where the product has one. */
    List<String> tablesWithWrongEngine(Connection c, String schema) throws SQLException;

    /** Drops one object found by {@link #ownedObjects}; used to undo what a refused statement created. */
    void dropObject(Connection c, String schema, DbObject object) throws SQLException;

    // --- backup and restore ------------------------------------------------------------------------------------

    /**
     * The base tables of a schema with their columns in order, each column's type in the backup's portable form
     * ({@link Portable}); a type outside it is reported as {@code UNSUPPORTED:<native type>}.
     */
    java.util.Map<String, List<Column>> tables(Connection c, String schema) throws SQLException;

    /** The schema's sequences, each with the next value it will issue and its increment. */
    List<Sequence> sequences(Connection c, String schema) throws SQLException;

    /** Makes a sequence issue {@code next} next. */
    void setSequence(Connection c, String schema, String name, long next) throws SQLException;

    /**
     * Gets user triggers and foreign keys out of the way before rows are loaded into {@code tables} (and into any
     * table whose foreign keys point at them), and returns the statements that put them back. The connection is
     * bound to {@code schema}.
     */
    List<String> suspendForLoad(Connection c, String schema, java.util.Collection<String> tables) throws SQLException;

    /** Binds a value read from a backup to an insert parameter, by the target column's portable type. */
    void bindValue(java.sql.PreparedStatement ps, int index, String portableType, Object value) throws SQLException;

    /** Reads a column for a backup, by its portable type: {@code null}, or the value's text or JSON form. */
    Object readValue(java.sql.ResultSet rs, int index, String portableType) throws SQLException;

    /** The bytes a schema's tables and indexes take on disk, as the server reports them. */
    long schemaSize(Connection c, String schema) throws SQLException;

    /** One column of a base table. */
    record Column(String name, String type, boolean nullable, boolean hasDefault) {
    }

    /** One sequence: the next value it issues and its increment. */
    record Sequence(String name, long next, long increment) {
    }

    // --- failure categories (console tests) -------------------------------------------------------------------

    /** Sorts a connection failure into a fixed category the console shows. */
    ConnectFailure classify(SQLException failure);

    /** The fixed failure categories of a connection test. */
    enum ConnectFailure {
        UNREACHABLE, TLS, AUTHENTICATION, DATABASE_MISSING, UNSUPPORTED_SERVER, OTHER
    }

    /** One named object in a schema. {@code table} is set for objects scoped to a table (MariaDB foreign keys). */
    record DbObject(String kind, String name, String table) {
    }
}
