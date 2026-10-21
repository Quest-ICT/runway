package mx.quest.runway.page;

/**
 * A chrome-fragment write was rejected before touching disk ({@link FragmentTemplates}): the name is not a
 * valid fragment id (unsafe, or an error-page name), the tenancy id is unsafe, or a create collided with an
 * existing fragment. The write is rejected whole — nothing is written — so a bad request can never corrupt
 * or traverse the tenant's {@code templates/} directory.
 *
 * <p>{@link #code()} is a stable, machine-readable key for operator i18n (the editor resolves it to a
 * localized message, convention {@code op.fragments.error.<code>}); {@link #getMessage()} is a plain-English
 * fallback. This is a client/authoring error (a {@code 400}-class condition), not a server fault —
 * controllers surface it as a flash error and re-render, never a {@code 500}.
 */
public class FragmentException extends RuntimeException {

    private final String code;

    public FragmentException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** The stable i18n key for this rejection (e.g. {@code "unsafe-name"}, {@code "already-exists"}). */
    public String code() {
        return code;
    }
}
