package mx.quest.runway.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import mx.quest.runway.spi.model.Realm;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The login check order both realm chains use: a suspended account is refused only AFTER its password was verified,
 * so it costs the same hash as any failed login (no timing signal); a wrong password on it is a plain bad-credentials.
 */
class AccountStatusChecksTest {

    private static final PasswordEncoder ENCODER = PasswordEncoders.argon2id(PasswordStrength.STANDARD);
    private static final String PW = "Status-Checks-9";

    private static DaoAuthenticationProvider provider(boolean enabled) {
        User user = new User("u1", "sam", ENCODER.encode(PW), "Sam", "", enabled, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of());
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(username -> new RunwayUserDetails(user));
        provider.setPasswordEncoder(ENCODER);
        AccountStatusChecks.verifyPasswordFirst(provider);
        return provider;
    }

    @Test
    void aSuspendedAccountWithAWrongPasswordIsABadCredentialFailureNotADisabledOne() {
        // With Spring's default order this would be DisabledException — thrown before any hashing.
        assertThrows(BadCredentialsException.class, () -> provider(false).authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("sam", "wrong-password")));
    }

    @Test
    void aSuspendedAccountWithTheRightPasswordIsRefusedAsDisabled() {
        assertThrows(DisabledException.class, () -> provider(false).authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("sam", PW)));
    }

    @Test
    void anActiveAccountStillSignsIn() {
        assertTrue(provider(true).authenticate(UsernamePasswordAuthenticationToken.unauthenticated("sam", PW))
                .isAuthenticated());
    }
}
