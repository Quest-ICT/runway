package mx.quest.database.internal;

import java.sql.SQLException;

/** The server answered, but it is not a product, version or configuration the database plugin supports. */
public class UnsupportedServerException extends SQLException {

    private static final long serialVersionUID = 1L;

    public UnsupportedServerException(String reason) {
        super(reason);
    }
}
