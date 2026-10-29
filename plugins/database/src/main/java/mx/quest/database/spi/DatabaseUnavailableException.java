package mx.quest.database.spi;

/**
 * There is no database to use right now: none is configured, the configured provider is not installed, the server
 * is unreachable or unsupported, or a site's connection cap stayed full. {@link PluginDatabase#available()} answers
 * {@code false} in the first cases, so check it first where "no database" has a better answer than an error. The
 * database plugin's console screen says which case it is.
 */
public class DatabaseUnavailableException extends DatabaseException {

    private static final long serialVersionUID = 1L;

    /** @param message why, in words an operator can act on */
    public DatabaseUnavailableException(String message) {
        super(message, null);
    }

    /**
     * @param message why, in words an operator can act on
     * @param cause   the underlying failure
     */
    public DatabaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
