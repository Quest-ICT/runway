package mx.quest.runway.security;

import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;

/**
 * Login check ordering shared by both realm chains: the account-status check (a <b>suspended</b> — disabled —
 * account) runs <em>after</em> the password is verified, not before as Spring's default does.
 *
 * <p>Why: Spring's default rejects a disabled account before hashing the submitted password, so a suspended
 * account answers measurably faster than an active one with a wrong password — a timing signal that tells an
 * outsider which usernames exist and are suspended. Verifying first makes every failed login cost one Argon2id
 * verification. Both failures redirect to the same generic {@code ?error} page, so the login screen never says
 * "suspended" either; the operator who suspended the account knows, and that is enough.
 */
final class AccountStatusChecks {

    private AccountStatusChecks() {
    }

    /** Moves the locked/disabled/expired checks behind the password verification on {@code provider}. */
    static void verifyPasswordFirst(DaoAuthenticationProvider provider) {
        provider.setPreAuthenticationChecks(user -> { });
        provider.setPostAuthenticationChecks(new AccountStatusUserDetailsChecker());
    }
}
