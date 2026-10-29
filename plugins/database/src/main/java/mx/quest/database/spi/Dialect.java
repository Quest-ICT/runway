package mx.quest.database.spi;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * What a consumer needs to know about the running database product to write the few statements that differ between
 * them. Everything else (schema binding, locks, backups) is the database plugin's job.
 *
 * <p>Write migration scripts for each dialect you support, under {@code db/<plugin-id>/<id()>/}. Both trees must
 * produce the same tables, columns and nullability at every version, with lower-case unquoted names: a site backup
 * taken on one product restores on the other only then.
 */
public interface Dialect {

    /**
     * The dialect id, also the migration folder name.
     *
     * @return {@code "postgresql"} or {@code "mariadb"}, or another provider's id
     */
    String id();

    /**
     * An SQL expression that takes the next value of {@code sequence}: {@code nextval('s')} on PostgreSQL,
     * {@code NEXTVAL(s)} on MariaDB.
     *
     * @param sequence an unqualified sequence name from your own scripts (it must carry your prefix)
     * @return the expression
     */
    String nextValue(String sequence);

    /**
     * The value {@code sequence} last handed out on this connection — read it right after the insert that used it,
     * inside the same {@link Scope#transaction} call.
     *
     * @param connection the connection the insert ran on
     * @param sequence   an unqualified sequence name from your own scripts
     * @return the value
     * @throws SQLException when the sequence has not been used on this connection
     */
    long lastValue(Connection connection, String sequence) throws SQLException;

    /**
     * {@code name} quoted as an identifier for this product.
     *
     * @param name an identifier
     * @return it quoted, with embedded quote characters doubled
     */
    String quote(String name);

    /**
     * A clause that limits a query to {@code limit} rows after skipping {@code offset}: {@code LIMIT n OFFSET m} on
     * both supplied products, so this exists for providers that differ.
     *
     * @param limit  rows to return, at least 0
     * @param offset rows to skip, at least 0
     * @return the clause, without leading or trailing space
     */
    default String limitOffset(int limit, int offset) {
        if (limit < 0 || offset < 0) {
            throw new IllegalArgumentException("limit and offset must not be negative");
        }
        return "LIMIT " + limit + " OFFSET " + offset;
    }
}
