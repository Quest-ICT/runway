package mx.quest.runway.security;

/**
 * A save that would give a principal a login name another account already answers to on the same sign-in
 * screen. Login names are matched case-insensitively and trimmed, so two accounts that differ only in case
 * (or surrounding blanks) are the same name to the login form — and the second one would either be
 * unreachable or, worse, shadow the first (a site user named like the site's superadmin). The stores refuse
 * such a save rather than leave the clash for sign-in to resolve.
 *
 * <p>Thrown only when the save <em>introduces</em> the name (a new principal, or a rename). A clash that is
 * already on disk — an install upgraded from a version that did not check — does not block unrelated edits of
 * either account (password reset, suspend, profile); sign-in resolves it in favour of the superadmin
 * ({@link SiteUserDetailsService}). It is an {@link IllegalArgumentException}, so the console's existing
 * "bad input" handling shows it as a form error rather than a 500.
 */
public class UsernameTakenException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String username;

    public UsernameTakenException(String username) {
        super("the username '" + username + "' is already in use");
        this.username = username;
    }

    /** The login name that was refused, as the caller supplied it (trimmed). */
    public String username() {
        return username;
    }
}
