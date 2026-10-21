package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.security.LoginAttemptGate;
import mx.quest.runway.security.LoginProtectionFilter;
import mx.quest.runway.security.ProtectionScope;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * End-to-end at the filter band: the kernel's {@link LoginProtectionFilter} wired with the plugin's real
 * {@link LoginFormPolicyGuard} (captcha verification stubbed). An active-captcha login POST with no/invalid token
 * is redirected to {@code /app/login?captcha} <b>and records a failed attempt</b> (so it counts toward the IP
 * lockout); a valid token passes through to the chain. Proves the wiring the operator actually gets — the guard's
 * captcha rejection surfaces through the filter's redirect + failure count.
 */
class CaptchaLoginProtectionFilterTest {

    private static SecretCipher passthroughCipher() {
        return new SecretCipher() {
            @Override public boolean isAvailable() { return true; }
            @Override public String encrypt(String plaintext) { return "ENC(" + plaintext + ")"; }
            @Override public String decrypt(String value) { return value; }
            @Override public boolean isEncrypted(String value) { return value != null && value.startsWith("ENC("); }
        };
    }

    /** Trusted-device service with the feature OFF, so the guard's captcha path runs unchanged (no cookie here). */
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

    /** An app-scope store with captcha enabled (both keys present → active) and honeypot off (isolate captcha). */
    private static ProtectionConfigStore activeCaptchaStore(Path root) {
        ProtectionProperties props = new ProtectionProperties();
        props.getLogin().setHoneypotEnabled(false);
        ProtectionProperties.Captcha c = props.getLogin().getCaptcha();
        c.setEnabled(true);
        c.setProvider("recaptcha-v2");
        c.setSiteKey("PUBLIC");
        c.setSecretKey("SECRET");
        return new ProtectionConfigStore(props, layout(root), new YamlStore(), passthroughCipher());
    }

    /** A gate that never locks and counts recorded failures into {@code failures[0]}. */
    private static LoginAttemptGate countingGate(int[] failures) {
        return new LoginAttemptGate() {
            @Override public boolean isLocked(String clientIp) { return false; }
            @Override public void recordFailure(String clientIp) { failures[0]++; }
            @Override public void recordSuccess(String clientIp) { }
        };
    }

    private static CaptchaVerifier stubVerifier(boolean result) {
        return new CaptchaVerifier((url, body) -> { throw new AssertionError("no real exchange"); }) {
            @Override public boolean verify(ProtectionSettings.Captcha config, String token, String remoteIp) {
                return result;
            }
        };
    }

    private static MockHttpServletRequest loginPost() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/login");
        request.setRequestURI("/app/login");
        request.setRemoteAddr("203.0.113.11");
        return request;
    }

    @Test
    void anActiveCaptchaLoginWithNoTokenIsRejectedAndCounted(@TempDir Path root) throws Exception {
        int[] failures = {0};
        LoginFormPolicyGuard guard = new LoginFormPolicyGuard(activeCaptchaStore(root), stubVerifier(false), td());
        LoginProtectionFilter filter = new LoginProtectionFilter(
                "/app/login", countingGate(failures), guard, ProtectionScope.Realm.APPLICATION, null);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(loginPost(), response, chain); // no captcha token param present

        assertNull(chain.getRequest(), "auth must NOT be reached when the captcha check rejects");
        assertEquals("/app/login?captcha", response.getRedirectedUrl(), "captcha rejection uses the ?captcha flag");
        assertEquals(1, failures[0], "a captcha rejection records a failure so it counts toward the IP lockout");
    }

    @Test
    void anActiveCaptchaLoginWithAnInvalidTokenIsRejectedAndCounted(@TempDir Path root) throws Exception {
        int[] failures = {0};
        LoginFormPolicyGuard guard = new LoginFormPolicyGuard(activeCaptchaStore(root), stubVerifier(false), td());
        LoginProtectionFilter filter = new LoginProtectionFilter(
                "/app/login", countingGate(failures), guard, ProtectionScope.Realm.APPLICATION, null);

        MockHttpServletRequest request = loginPost();
        request.addParameter("g-recaptcha-response", "bad-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest(), "an invalid captcha token is rejected before auth");
        assertEquals("/app/login?captcha", response.getRedirectedUrl());
        assertEquals(1, failures[0]);
    }

    @Test
    void anActiveCaptchaLoginWithAValidTokenReachesTheChain(@TempDir Path root) throws Exception {
        int[] failures = {0};
        LoginFormPolicyGuard guard = new LoginFormPolicyGuard(activeCaptchaStore(root), stubVerifier(true), td());
        LoginProtectionFilter filter = new LoginProtectionFilter(
                "/app/login", countingGate(failures), guard, ProtectionScope.Realm.APPLICATION, null);

        MockHttpServletRequest request = loginPost();
        request.addParameter("g-recaptcha-response", "good-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "a verifier-approved token lets the login proceed to auth");
        assertNull(response.getRedirectedUrl());
        assertEquals(0, failures[0], "a passing captcha records no failure");
    }
}
