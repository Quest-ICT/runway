package mx.quest.database.spi;

/**
 * One schema a consumer plugin works in: a site's, or the installation-wide one. Obtained from
 * {@link PluginDatabase}; bound to that plugin, so its migrations are checked before the first call.
 *
 * <h2>Calls</h2>
 * Each outer call borrows one pooled connection, binds it to the schema, runs the work and returns the connection.
 * <ul>
 *   <li><b>Joining.</b> A {@code transaction} or {@code read} made inside another call on <b>this same scope</b>, on
 *       the same thread, joins it: the same connection, no commit of its own, and an exception rolls back the outer
 *       call too. A {@code read} inside a {@code transaction} joins it; a {@code transaction} inside a {@code read}
 *       throws {@link IllegalStateException}.</li>
 *   <li><b>No nesting across scopes.</b> A call on another scope (another site's, or the app scope) from inside a
 *       call throws {@link IllegalStateException}: it would hold two pooled connections at once, which is how a pool
 *       deadlocks. Make such calls one after the other.</li>
 *   <li><b>One connection per outer call</b> is what makes reading a new id back reliable: {@code INSERT … RETURNING}
 *       on PostgreSQL, {@code LASTVAL(seq)} on MariaDB, both on the same connection as the insert
 *       ({@link Dialect#lastValue}).</li>
 *   <li><b>Timeouts.</b> Every statement runs under the installation's statement timeout. A site has a cap on the
 *       connections it may hold at once, so one busy site cannot take the pool from the others; over it, the call
 *       waits briefly, then throws {@link DatabaseUnavailableException}.</li>
 * </ul>
 *
 * <h2>Failures</h2>
 * {@link DatabaseUnavailableException} when the database is not configured or not reachable;
 * {@link ScopeNotReadyException} when this plugin's tables in this schema are not usable (a failed, refused or newer
 * migration, or data being prepared); {@link DatabaseException} wrapping the {@link java.sql.SQLException} your work
 * threw. All three are unchecked.
 *
 * <p>Thread-safe.
 */
public interface Scope {

    /** The two kinds of scope a consumer can declare ({@link DatabaseConsumer#scopes()}). */
    enum Kind {
        /** Data that belongs to one site: it lives in that site's schema and travels in that site's backups. */
        SITE,
        /** Data that belongs to no site. It is in no site backup, and does not move when the database changes. */
        APP
    }

    /**
     * The kind of this scope.
     *
     * @return {@link Kind#SITE} or {@link Kind#APP}
     */
    Kind kind();

    /**
     * The site this scope belongs to.
     *
     * @return the site id, or {@code null} for the app scope
     */
    String siteId();

    /**
     * Runs {@code work} in one read-write transaction: committed when it returns, rolled back when it throws.
     *
     * @param work the work
     * @param <T>  what it returns
     * @return what {@code work} returned
     */
    <T> T transaction(SqlWork<T> work);

    /**
     * Runs {@code work} in one read-only transaction over a consistent snapshot: every query in it sees the data as
     * it was when the first one ran, and a write fails.
     *
     * @param work the work
     * @param <T>  what it returns
     * @return what {@code work} returned
     */
    <T> T read(SqlWork<T> work);
}
