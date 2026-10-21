package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import mx.quest.runway.crypto.AesGcmSecretCipher;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.security.LoginFormGuard;
import mx.quest.runway.security.LoginFormVerdict;
import mx.quest.runway.security.ProtectionScope;
import mx.quest.runway.security.RunwaySecurityProperties;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The plugin's {@code @Primary} login form guard: it rejects a filled honeypot, and when a scope's captcha is
 * active it verifies the provider token (rejecting a missing/invalid one) while passing when captcha is inactive.
 * The captcha verifier is stubbed so the guard's policy — not the network — is under test.
 */
class LoginFormPolicyGuardTest {

    private static SecretCipher passthroughCipher() {
        return new SecretCipher() {
            @Override public boolean isAvailable() { return true; }
            @Override public String encrypt(String plaintext) { return "ENC(" + plaintext + ")"; }
            @Override public String decrypt(String value) { return value; }
            @Override public boolean isEncrypted(String value) { return value != null && value.startsWith("ENC("); }
        };
    }

    /**
     * A trusted-device service with the feature OFF, so {@code isTrusted} is always false and the guard's captcha
     * path runs exactly as before. (These requests carry no trusted-device cookie anyway; the dedicated skip is
     * covered by {@link TrustedDeviceServiceTest}.)
     */
    private static TrustedDeviceService td() {
        ProtectionProperties props = new ProtectionProperties();
        props.getRememberDevice().setEnabled(false);
        return new TrustedDeviceService(props, null, passthroughCipher());
    }

    private static StorageLayout layout(Path root) {
        StorageProperties props = new StorageProperties();
        props.setExternal(root.resolve("external"));
        props.setSites(root.resolve("external").resolve("sites"));
        return StorageLayout.from(props);
    }

    private static ProtectionConfigStore store(Path root, boolean appHoneypotEnabled) {
        ProtectionProperties props = new ProtectionProperties();
        props.getLogin().setHoneypotEnabled(appHoneypotEnabled);
        return new ProtectionConfigStore(props, layout(root), new YamlStore(), passthroughCipher());
    }

    /** A verifier stub that records the token/ip it was called with and returns a fixed result. */
    private static final class StubVerifier extends CaptchaVerifier {
        boolean called;
        String seenToken;
        String seenIp;
        private final boolean result;

        StubVerifier(boolean result) {
            super((url, body) -> { throw new AssertionError("the stub must not perform a real exchange"); });
            this.result = result;
        }

        @Override
        public boolean verify(ProtectionSettings.Captcha config, String token, String remoteIp) {
            this.called = true;
            this.seenToken = token;
            this.seenIp = remoteIp;
            return result;
        }
    }

    private static MockHttpServletRequest post() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/login");
        request.setRemoteAddr("203.0.113.7");
        return request;
    }

    private static ProtectionSettings.Captcha activeCaptcha(String provider) {
        return new ProtectionSettings.Captcha(true, provider, "SITEKEY", "SECRET", 0.5);
    }

    // ---- honeypot ----------------------------------------------------------

    @Test
    void aFilledTrapIsRejectedAsHoneypot(@TempDir Path root) {
        LoginFormPolicyGuard guard = new LoginFormPolicyGuard(store(root, true), new StubVerifier(true), td());
        MockHttpServletRequest req = post();
        req.addParameter(LoginFormGuard.HONEYPOT_FIELD, "http://spam.example");
        LoginFormVerdict verdict = guard.check(req, ProtectionScope.application());
        assertFalse(verdict.allowed(), "a non-blank trap is a bot");
        assertEquals(LoginFormVerdict.Reason.HONEYPOT, verdict.reason());
        assertEquals("error", verdict.redirectFlag(), "honeypot hides behind the generic ?error flag");
    }

    @Test
    void anEmptyTrapPassesWhenCaptchaInactive(@TempDir Path root) {
        LoginFormPolicyGuard guard = new LoginFormPolicyGuard(store(root, true), new StubVerifier(true), td());
        assertTrue(guard.check(post(), ProtectionScope.application()).allowed(), "blank trap, no captcha → OK");
    }

    @Test
    void theHoneypotShortCircuitsBeforeCaptcha(@TempDir Path root) throws IOException {
        // A filled trap is rejected as a honeypot even with captcha active — the verifier is never consulted.
        ProtectionConfigStore store = store(root, true);
        store.savePerSite("acme", new ProtectionSettings(true, 5, 300, 900, activeCaptcha("recaptcha-v2")));
        StubVerifier verifier = new StubVerifier(true);
        LoginFormPolicyGuard guard = new LoginFormPolicyGuard(store, verifier, td());
        MockHttpServletRequest req = post();
        req.addParameter(LoginFormGuard.HONEYPOT_FIELD, "bot");
        LoginFormVerdict verdict = guard.check(req, ProtectionScope.forSite("acme"));
        assertEquals(LoginFormVerdict.Reason.HONEYPOT, verdict.reason());
        assertFalse(verifier.called, "the honeypot rejects before the captcha check runs");
    }

    // ---- captcha (active) --------------------------------------------------

    @Test
    void anActiveCaptchaWithNoTokenIsRejected(@TempDir Path root) throws IOException {
        ProtectionConfigStore store = store(root, false); // honeypot off — isolate the captcha path
        store.savePerSite("acme", new ProtectionSettings(false, 5, 300, 900, activeCaptcha("recaptcha-v3")));
        StubVerifier verifier = new StubVerifier(false); // provider would reject a missing token
        LoginFormPolicyGuard guard = new LoginFormPolicyGuard(store, verifier, td());

        LoginFormVerdict verdict = guard.check(post(), ProtectionScope.forSite("acme"));
        assertFalse(verdict.allowed(), "captcha active + no valid token → rejected");
        assertEquals(LoginFormVerdict.Reason.CAPTCHA, verdict.reason());
        assertEquals("captcha", verdict.redirectFlag(), "captcha maps to its own ?captcha flag");
    }

    @Test
    void anActiveCaptchaReadsTheProviderTokenParamAndPasses(@TempDir Path root) throws IOException {
        ProtectionConfigStore store = store(root, false);
        store.savePerSite("acme", new ProtectionSettings(false, 5, 300, 900, activeCaptcha("recaptcha-v3")));
        StubVerifier verifier = new StubVerifier(true);
        LoginFormPolicyGuard guard = new LoginFormPolicyGuard(store, verifier, td());

        MockHttpServletRequest req = post();
        req.addParameter("g-recaptcha-response", "the-token"); // reCAPTCHA's token param
        LoginFormVerdict verdict = guard.check(req, ProtectionScope.forSite("acme"));

        assertTrue(verdict.allowed(), "a verifier-approved token passes");
        assertTrue(verifier.called);
        assertEquals("the-token", verifier.seenToken, "the guard reads the provider-specific token param");
        assertEquals("203.0.113.7", verifier.seenIp, "the gated client IP is passed to the verifier");
    }

    @Test
    void anActiveCaptchaWithAnInvalidTokenIsRejected(@TempDir Path root) throws IOException {
        ProtectionConfigStore store = store(root, false);
        store.savePerSite("acme", new ProtectionSettings(false, 5, 300, 900, activeCaptcha("recaptcha-v2")));
        StubVerifier verifier = new StubVerifier(false); // provider rejects it
        LoginFormPolicyGuard guard = new LoginFormPolicyGuard(store, verifier, td());

        MockHttpServletRequest req = post();
        req.addParameter("g-recaptcha-response", "bad-token");
        LoginFormVerdict verdict = guard.check(req, ProtectionScope.forSite("acme"));
        assertFalse(verdict.allowed());
        assertEquals(LoginFormVerdict.Reason.CAPTCHA, verdict.reason());
    }

    // ---- captcha (inactive) ------------------------------------------------

    // ---- trusted-device skip (login only) ----------------------------------

    private static SecretCipher realCipher() {
        RunwaySecurityProperties security = new RunwaySecurityProperties();
        security.setSecretKey("guard-skip-test-master-key-0123456789");
        return new AesGcmSecretCipher(security);
    }

    /** Mints a real trusted-device cookie for {@code scope} via the service's own issue path. */
    private static Cookie mint(TrustedDeviceService svc, ProtectionScope scope, String loginUri) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", loginUri);
        req.addParameter("remember-device", "1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        svc.rememberIfRequested(req, res, scope);
        String header = res.getHeader(HttpHeaders.SET_COOKIE);
        String nameValue = header.substring(0, header.indexOf(';'));
        int eq = nameValue.indexOf('=');
        return new Cookie(nameValue.substring(0, eq), nameValue.substring(eq + 1));
    }

    @Test
    void aTrustedDeviceSkipsTheCaptchaOnLoginButNotOnForgotPassword(@TempDir Path root) throws IOException {
        SecretCipher cipher = realCipher();
        ProtectionConfigStore store = store(root, false); // honeypot off — isolate the captcha path
        store.savePerSite("acme", new ProtectionSettings(false, 5, 300, 900, activeCaptcha("recaptcha-v2")));
        TrustedDeviceService svc = new TrustedDeviceService(new ProtectionProperties(), store, cipher);
        ProtectionScope scope = ProtectionScope.forSite("acme");
        Cookie cookie = mint(svc, scope, "/login");

        // On the LOGIN form: the trusted cookie skips the captcha — a verifier that would reject a missing token
        // is never even consulted, and the login is allowed.
        StubVerifier onLogin = new StubVerifier(false);
        MockHttpServletRequest loginPost = new MockHttpServletRequest("POST", "/login");
        loginPost.setCookies(cookie);
        LoginFormVerdict login = new LoginFormPolicyGuard(store, onLogin, svc).check(loginPost, scope);
        assertTrue(login.allowed(), "a trusted device is not challenged on the login form");
        assertFalse(onLogin.called, "…and the captcha verifier is never called for it");

        // On the FORGOT-PASSWORD form: the SAME cookie does NOT skip the captcha (login-only narrowing).
        StubVerifier onForgot = new StubVerifier(false);
        MockHttpServletRequest forgotPost = new MockHttpServletRequest("POST", "/forgot-password");
        forgotPost.setCookies(cookie);
        LoginFormVerdict forgot = new LoginFormPolicyGuard(store, onForgot, svc).check(forgotPost, scope);
        assertFalse(forgot.allowed(), "the trusted cookie must NOT skip the recovery-form captcha");
        assertTrue(onForgot.called, "…the verifier IS consulted there (no token → rejected)");
    }

    @Test
    void anInactiveCaptchaIsNotChallenged(@TempDir Path root) throws IOException {
        // enabled but keys blank → inactive → the verifier is never consulted and the login passes.
        ProtectionConfigStore store = store(root, false);
        ProtectionSettings.Captcha noKeys = new ProtectionSettings.Captcha(true, "recaptcha-v2", "", "", 0.5);
        store.savePerSite("acme", new ProtectionSettings(false, 5, 300, 900, noKeys));
        StubVerifier verifier = new StubVerifier(false);
        LoginFormPolicyGuard guard = new LoginFormPolicyGuard(store, verifier, td());

        AtomicReference<LoginFormVerdict> verdict = new AtomicReference<>(
                guard.check(post(), ProtectionScope.forSite("acme")));
        assertTrue(verdict.get().allowed(), "captcha with no keys is inactive → no challenge");
        assertFalse(verifier.called, "an inactive captcha never calls the verifier");
        assertNull(verdict.get().reason());
    }

    /** An encoded login path (/app/%6Cogin) is the login path as the dispatcher routes it, so the same rules apply. */
    @Test
    void theRoutedPathIsTheDecodedOne() {
        MockHttpServletRequest encoded = new MockHttpServletRequest("POST", "/app/%6Cogin");
        encoded.setServletPath("/app/login");
        assertEquals("/app/login", LoginFormPolicyGuard.routedPath(encoded));
        MockHttpServletRequest site = new MockHttpServletRequest("GET", "/l%6Fgin");
        site.setServletPath("/login");
        assertEquals("/login", LoginFormPolicyGuard.routedPath(site));
        // A bare request with no servlet path (some test shapes) falls back to the URI.
        assertEquals("/app/login", LoginFormPolicyGuard.routedPath(new MockHttpServletRequest("POST", "/app/login")));
    }
}
