package mx.quest.database.spi;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Work done on one connection inside {@link Scope#transaction} or {@link Scope#read}.
 *
 * <p>The connection is lent for the duration of the call only: do not keep it, close it, commit it, roll it back,
 * change its auto-commit, read-only or isolation settings, or switch its schema ({@code USE}, {@code SET
 * search_path}). The scope does all of that, and resets the connection before the pool hands it to anyone else. Close
 * the statements and result sets you open (try-with-resources).
 *
 * @param <T> what the work returns
 */
@FunctionalInterface
public interface SqlWork<T> {

    /**
     * Does the work.
     *
     * @param connection a connection bound to the scope's schema, inside the scope's transaction
     * @return the result, which may be {@code null}
     * @throws SQLException to roll the transaction back; the scope rethrows it wrapped in a
     *                      {@link DatabaseException}
     */
    T run(Connection connection) throws SQLException;
}
