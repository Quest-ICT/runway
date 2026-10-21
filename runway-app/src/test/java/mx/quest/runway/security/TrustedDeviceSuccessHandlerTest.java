package mx.quest.runway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * The realm login-success handler: it offers the {@link TrustedDeviceIssuer} the scope for this realm, then
 * performs the always-redirect (mirroring {@code defaultSuccessUrl(url, true)}). A failing issuer must never break
 * the redirect (fail-safe), and the kernel no-op default must be a harmless no-op.
 */
class TrustedDeviceSuccessHandlerTest {

    /** Records the scope it was offered, and can be told to blow up to prove the redirect still happens. */
    private static final class RecordingIssuer implements TrustedDeviceIssuer {
        ProtectionScope seen;
        boolean explode;

        @Override
        public void rememberIfRequested(HttpServletRequest request, HttpServletResponse response,
                                        ProtectionScope scope) {
            this.seen = scope;
            if (explode) {
                throw new RuntimeException("cookie fault");
            }
        }
    }

    private static Authentication auth() {
        return UsernamePasswordAuthenticationToken.authenticated("operator", "x", List.of());
    }

    @Test
    void appSuccessOffersTheApplicationScopeThenRedirects() throws Exception {
        RecordingIssuer issuer = new RecordingIssuer();
        TrustedDeviceSuccessHandler handler = new TrustedDeviceSuccessHandler(
                issuer, ProtectionScope.Realm.APPLICATION, null, "/__app");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest("POST", "/app/login"), response, auth());

        assertNotNull(issuer.seen, "the issuer was offered a scope");
        assertEquals(ProtectionScope.Realm.APPLICATION, issuer.seen.realm(), "app realm scope, no site");
        assertEquals("/__app", response.getRedirectedUrl(), "always redirects to the app console");
    }

    @Test
    void aFailingIssuerStillRedirects() throws Exception {
        RecordingIssuer issuer = new RecordingIssuer();
        issuer.explode = true;
        TrustedDeviceSuccessHandler handler = new TrustedDeviceSuccessHandler(
                issuer, ProtectionScope.Realm.APPLICATION, null, "/__app");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest("POST", "/app/login"), response, auth());

        assertEquals("/__app", response.getRedirectedUrl(), "a cookie fault never breaks the login redirect");
    }

    @Test
    void theNoOpDefaultDoesNothing() {
        // NoTrustedDeviceIssuer never issues a cookie — the CAPTCHA-always baseline with no plugin.
        MockHttpServletResponse response = new MockHttpServletResponse();
        new NoTrustedDeviceIssuer().rememberIfRequested(
                new MockHttpServletRequest("POST", "/app/login"), response, ProtectionScope.application());
        assertEquals(0, response.getCookies().length, "the default issues no cookie");
    }
}
