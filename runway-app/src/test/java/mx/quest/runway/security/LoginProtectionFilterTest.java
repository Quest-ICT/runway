package mx.quest.runway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The shell blocks a POST to the realm's login path when the IP is locked ({@link LoginAttemptGate}) or the
 * request-aware {@link LoginFormGuard} rejects it (honeypot); everything else passes through. A form-guard
 * rejection also records a failed attempt so a trap-spamming bot walks into the IP lockout.
 */
class LoginProtectionFilterTest {

    private static LoginAttemptGate gate(boolean locked) {
        return gate(locked, new int[1]);
    }

    /** A gate that reports {@code locked} and counts recorded failures into {@code failures[0]}. */
    private static LoginAttemptGate gate(boolean locked, int[] failures) {
        return new LoginAttemptGate() {
            @Override
            public boolean isLocked(String clientIp) {
                return locked;
            }

            @Override
            public void recordFailure(String clientIp) {
                failures[0]++;
            }

            @Override
            public void recordSuccess(String clientIp) {
            }
        };
    }

    private static LoginFormGuard formGuard(LoginFormVerdict verdict, AtomicReference<ProtectionScope> seen) {
        return (request, scope) -> {
            seen.set(scope);
            return verdict;
        };
    }

    private static MockFilterChain run(LoginAttemptGate gate, LoginFormGuard formGuard, String method, String uri,
                                       MockHttpServletResponse response) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        request.setRemoteAddr("203.0.113.5");
        MockFilterChain chain = new MockFilterChain();
        new LoginProtectionFilter("/app/login", gate, formGuard, ProtectionScope.Realm.APPLICATION, null)
                .doFilter(request, response, chain);
        return chain;
    }

    private static LoginFormGuard permissive() {
        return (request, scope) -> LoginFormVerdict.ok();
    }

    @Test
    void anUnlockedLoginThatPassesTheGuardReachesTheChain() throws Exception {
        assertNotNull(run(gate(false), permissive(), "POST", "/app/login", new MockHttpServletResponse()).getRequest());
    }

    @Test
    void aLockedLoginIsRedirectedBeforeTheChain() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = run(gate(true), permissive(), "POST", "/app/login", response);
        assertNull(chain.getRequest(), "auth must NOT be reached when locked");
        assertEquals("/app/login?locked", response.getRedirectedUrl());
    }

    @Test
    void aLockedNonLoginPostStillPasses() throws Exception {
        // The shell only guards its own login path — even when the IP is locked, other POSTs pass.
        assertNotNull(run(gate(true), permissive(), "POST", "/app/something", new MockHttpServletResponse()).getRequest());
    }

    @Test
    void aGetToTheLoginPathIsNotFiltered() throws Exception {
        assertNotNull(run(gate(true), permissive(), "GET", "/app/login", new MockHttpServletResponse()).getRequest());
    }

    @Test
    void aFormGuardRejectionRedirectsWithTheFlagAndRecordsAFailure() throws Exception {
        int[] failures = {0};
        AtomicReference<ProtectionScope> seen = new AtomicReference<>();
        LoginFormGuard guard = formGuard(LoginFormVerdict.reject(LoginFormVerdict.Reason.HONEYPOT), seen);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = run(gate(false, failures), guard, "POST", "/app/login", response);

        assertNull(chain.getRequest(), "auth must NOT be reached when the form guard rejects");
        assertEquals("/app/login?error", response.getRedirectedUrl(), "honeypot maps to the generic ?error flag");
        assertEquals(1, failures[0], "a rejected login records a failure so it counts toward the IP lockout");
        assertEquals(ProtectionScope.Realm.APPLICATION, seen.get().realm(), "the app-realm shell scopes to APPLICATION");
    }

    @Test
    void theGuardIsNotConsultedWhenAlreadyLocked() throws Exception {
        // Lockout short-circuits before the form guard runs (no honeypot check on an already-locked IP).
        AtomicReference<ProtectionScope> seen = new AtomicReference<>();
        LoginFormGuard guard = formGuard(LoginFormVerdict.reject(LoginFormVerdict.Reason.HONEYPOT), seen);
        MockHttpServletResponse response = new MockHttpServletResponse();
        run(gate(true), guard, "POST", "/app/login", response);
        assertNull(seen.get(), "the form guard is never consulted once the IP is locked");
        assertEquals("/app/login?locked", response.getRedirectedUrl());
    }

    @Test
    void aTenantShellScopesToTheResolvedSite() throws Exception {
        // A tenant-realm shell with no SiteResolver yields a site-less TENANT scope (fail-safe default).
        AtomicReference<ProtectionScope> seen = new AtomicReference<>();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRequestURI("/login");
        request.setRemoteAddr("203.0.113.9");
        MockFilterChain chain = new MockFilterChain();
        new LoginProtectionFilter("/login", gate(false),
                formGuard(LoginFormVerdict.ok(), seen), ProtectionScope.Realm.TENANT, null)
                .doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
        assertEquals(ProtectionScope.Realm.TENANT, seen.get().realm());
        assertTrue(seen.get().resolvedSiteId().isEmpty(), "no resolver → site-less tenant scope");
    }

    @Test
    void redirectFlagMapsHoneypotToErrorAndOthersToTheirOwnFlag() {
        assertEquals("error", LoginFormVerdict.reject(LoginFormVerdict.Reason.HONEYPOT).redirectFlag());
        assertEquals("captcha", LoginFormVerdict.reject(LoginFormVerdict.Reason.CAPTCHA).redirectFlag());
    }

    // --- the recovery-request form: honeypot/CAPTCHA guard WITHOUT the per-IP lockout (enforceLockout=false) -----

    private static MockFilterChain runRecovery(LoginAttemptGate gate, LoginFormGuard formGuard,
                                               MockHttpServletResponse response) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/forgot-password");
        request.setRequestURI("/app/forgot-password");
        request.setRemoteAddr("203.0.113.5");
        MockFilterChain chain = new MockFilterChain();
        new LoginProtectionFilter("/app/forgot-password", gate, formGuard, ProtectionScope.Realm.APPLICATION, null, false)
                .doFilter(request, response, chain);
        return chain;
    }

    @Test
    void twoShellsInOneChainBothRun() throws Exception {
        // Regression: the login shell and the recovery shell are the SAME class in one filter chain. Keyed on the
        // class name (OncePerRequestFilter's default), the first instance's "already filtered" flag would suppress
        // doFilterInternal on the second — so the recovery form would silently go unguarded. The path-specific
        // once-key must let BOTH run: here the login shell passes /app/forgot-password through, then the recovery
        // shell's CAPTCHA rejection fires.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/forgot-password");
        request.setRequestURI("/app/forgot-password");
        request.setRemoteAddr("203.0.113.5");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ProtectionScope> seen = new AtomicReference<>();

        LoginProtectionFilter loginShell = new LoginProtectionFilter("/app/login", gate(false), permissive(),
                ProtectionScope.Realm.APPLICATION, null);
        LoginProtectionFilter recoveryShell = new LoginProtectionFilter("/app/forgot-password", gate(false),
                formGuard(LoginFormVerdict.reject(LoginFormVerdict.Reason.CAPTCHA), seen),
                ProtectionScope.Realm.APPLICATION, null, false);
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {}, recoveryShell);
        loginShell.doFilter(request, response, chain);

        assertNotNull(seen.get(), "the recovery shell's guard must be consulted (not suppressed by the login shell)");
        assertEquals("/app/forgot-password?captcha", response.getRedirectedUrl());
    }

    @Test
    void aLockedIpStillReachesTheRecoveryForm() throws Exception {
        // A user locked out of the login must still be able to request a reset — the recovery form has no lockout.
        MockFilterChain chain = runRecovery(gate(true), permissive(), new MockHttpServletResponse());
        assertNotNull(chain.getRequest(), "a locked IP reaches the recovery form (enforceLockout=false)");
    }

    @Test
    void aRecoveryCaptchaRejectionRedirectsWithoutRecordingALockout() throws Exception {
        int[] failures = {0};
        AtomicReference<ProtectionScope> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = runRecovery(gate(false, failures),
                formGuard(LoginFormVerdict.reject(LoginFormVerdict.Reason.CAPTCHA), seen), response);

        assertNull(chain.getRequest(), "a failed challenge blocks the reset-email request");
        assertEquals("/app/forgot-password?captcha", response.getRedirectedUrl());
        assertEquals(0, failures[0], "the recovery form never records a login-lockout failure");
        assertEquals(ProtectionScope.Realm.APPLICATION, seen.get().realm());
    }
}
