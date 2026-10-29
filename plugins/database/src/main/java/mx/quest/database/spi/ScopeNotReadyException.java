package mx.quest.database.spi;

/**
 * The database is there, but this plugin's tables in this schema are not usable. Only this plugin in this schema is
 * affected: other plugins, and this plugin's other schemas, carry on.
 */
public class ScopeNotReadyException extends DatabaseException {

    private static final long serialVersionUID = 1L;

    /** Why the scope is not ready. May gain constants in a minor version. */
    public enum Reason {
        /** A migration script failed part-way; an operator retries or marks it applied on the database screen. */
        MIGRATION_FAILED,
        /** An applied script no longer matches the one the plugin ships; released migrations must never change. */
        CHECKSUM_MISMATCH,
        /** The schema is at a version newer than any script the installed plugin ships (after a downgrade). */
        NEWER_THAN_INSTALLED,
        /** The schema is being created, migrated or restored; retry shortly. */
        PREPARING,
        /** The plugin's object prefix clashes with another plugin's, or its scripts cannot be read; nothing ran. */
        PLUGIN_REFUSED
    }

    private final Reason reason;

    /**
     * @param reason  why
     * @param message what to do about it, in words an operator can act on
     */
    public ScopeNotReadyException(Reason reason, String message) {
        super(message, null);
        this.reason = reason;
    }

    /** @return why the scope is not ready */
    public Reason reason() {
        return reason;
    }
}
