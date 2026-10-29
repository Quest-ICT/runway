package mx.quest.database.internal;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Plain JDBC helpers. */
final class Jdbc {

    private Jdbc() {
    }

    /** Runs one statement that returns no rows the caller needs. */
    static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
