package mx.quest.database.spi;

/**
 * A database call failed: the {@link java.sql.SQLException} a consumer's {@link SqlWork} threw, or one the scope met
 * while preparing the connection, as its cause. Unchecked, so a consumer's service code is not forced to declare it;
 * catch it where a failure has a useful answer (a form error, a fallback). Its message never contains a password or a
 * connection URL.
 */
public class DatabaseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what failed, in words an operator can act on
     * @param cause   the underlying failure, or {@code null}
     */
    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
