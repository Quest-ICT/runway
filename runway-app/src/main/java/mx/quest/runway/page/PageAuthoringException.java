package mx.quest.runway.page;

/**
 * A page failed pre-write authoring validation ({@link PageAuthoringValidator}): an unsafe slug or
 * public path, a reserved id, or a routing collision with a sibling page. The write is rejected
 * whole — nothing is written — so a bad edit can never corrupt the tenant's routable page set.
 *
 * <p>{@link #code()} is a stable, machine-readable key for operator i18n (the editor resolves it to a
 * localized message, convention {@code op.pages.error.<code>}); {@link #getMessage()} is a plain
 * English fallback. This is a client/authoring error (a {@code 400}-class condition), not a server
 * fault — controllers surface it as a flash error and re-render the form, never a {@code 500}.
 */
public class PageAuthoringException extends RuntimeException {

    private final String code;

    public PageAuthoringException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** The stable i18n key for this rejection (e.g. {@code "unsafe-slug"}, {@code "path-collision"}). */
    public String code() {
        return code;
    }
}
