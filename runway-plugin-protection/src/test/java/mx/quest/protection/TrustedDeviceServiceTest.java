package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import mx.quest.runway.crypto.AesGcmSecretCipher;
import mx.quest.runway.crypto.SecretCipher;
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
 * The "remember this device" policy: a real AES-256-GCM {@link AesGcmSecretCipher} seals the cookie, so this exercises
 * the genuine round-trip AND the adversarial paths — a forged plaintext cookie, a tampered ciphertext, a
 * cross-realm / cross-site cookie, an expired stamp, opt-out, no-captcha, feature-off, and no-key — every one of
 * which must resolve to "not trusted" so the CAPTCHA is shown. Proves the cookie can only ever cause "more
 * CAPTCHA", never a skip it shouldn't.
 */
class TrustedDeviceServiceTest {

    private static final String KEY = "trusted-device-test-master-key-0123456789";

    private static SecretCipher realCipher() {
        RunwaySecurityProperties security = new RunwaySecurityProperties();
        security.setSecretKey(KEY);
        return new AesGcmSecretCipher(security);
    }

    private static SecretCipher noKeyCipher() {
        return new AesGcmSecretCipher(new RunwaySecurityProperties()); // blank key ⇒ isAvailable() false
    }

    private static StorageLayout layout(Path root) {
        StorageProperties props = new StorageProperties();
        props.setExternal(root.resolve("external"));
        props.setSites(root.resolve("external").resolve("sites"));
        return StorageLayout.from(props);
    }

    /** A config store whose app scope AND site "acme"/"other" have an active CAPTCHA (so a cookie may be minted). */
    private static ProtectionConfigStore captchaStore(Path root, SecretCipher cipher) {
        ProtectionProperties props = new ProtectionProperties();
        props.getLogin().getCaptcha().setEnabled(true);
        props.getLogin().getCaptcha().setSiteKey("SITEKEY");
        props.getLogin().getCaptcha().setSecretKey("SECRET");
        ProtectionConfigStore store = new ProtectionConfigStore(props, layout(root), new YamlStore(), cipher);
        ProtectionSettings.Captcha active = new ProtectionSettings.Captcha(true, "recaptcha-v2", "SITEKEY", "SECRET", 0.5);
        try {
            store.savePerSite("acme", new ProtectionSettings(false, 5, 300, 900, active));
            store.savePerSite("other", new ProtectionSettings(false, 5, 300, 900, active));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return store;
    }

    private static TrustedDeviceService service(ProtectionConfigStore store, SecretCipher cipher) {
        ProtectionProperties props = new ProtectionProperties(); // remember-device defaults: enabled, 30 days
        return new TrustedDeviceService(props, store, cipher);
    }

    /** Runs the issue path and returns the Set-Cookie as a {@link Cookie} (name+value), or {@code null} if none. */
    private static Cookie issue(TrustedDeviceService svc, ProtectionScope scope, boolean optIn) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                scope.realm() == ProtectionScope.Realm.APPLICATION ? "/app/login" : "/login");
        if (optIn) {
            req.addParameter(TrustedDeviceService.REMEMBER_PARAM, "1");
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        svc.rememberIfRequested(req, res, scope);
        String header = res.getHeader(HttpHeaders.SET_COOKIE);
        if (header == null) {
            return null;
        }
        String nameValue = header.substring(0, header.indexOf(';'));
        int eq = nameValue.indexOf('=');
        return new Cookie(nameValue.substring(0, eq), nameValue.substring(eq + 1));
    }

    private static boolean trustedWith(TrustedDeviceService svc, ProtectionScope scope, Cookie cookie) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/login");
        if (cookie != null) {
            req.setCookies(cookie);
        }
        return svc.isTrusted(req, scope);
    }

    // ---- the happy path ---------------------------------------------------------------------------

    @Test
    void anIssuedCookieIsTrustedForTheSameScope(@TempDir Path root) {
        SecretCipher cipher = realCipher();
        TrustedDeviceService svc = service(captchaStore(root, cipher), cipher);
        Cookie app = issue(svc, ProtectionScope.application(), true);
        assertNotNull(app, "an opted-in login with an active captcha mints a cookie");
        assertTrue(app.getName().equals("runway_td_app"), "the app-realm cookie is named per realm");
        assertTrue(trustedWith(svc, ProtectionScope.application(), app), "the same device is trusted next time");

        Cookie site = issue(svc, ProtectionScope.forSite("acme"), true);
        assertNotNull(site);
        assertTrue(site.getName().equals("runway_td_site_acme"), "the tenant cookie is named per site (site_ namespace)");
        assertTrue(trustedWith(svc, ProtectionScope.forSite("acme"), site));
    }

    // ---- opt-in / gating: no cookie when it shouldn't be minted -----------------------------------

    @Test
    void noCookieWithoutTheOptIn(@TempDir Path root) {
        SecretCipher cipher = realCipher();
        TrustedDeviceService svc = service(captchaStore(root, cipher), cipher);
        assertNull(issue(svc, ProtectionScope.application(), false), "no cookie unless the box was ticked");
    }

    @Test
    void noCookieWhenTheScopeHasNoActiveCaptcha(@TempDir Path root) {
        SecretCipher cipher = realCipher();
        // A store with NO captcha configured anywhere → nothing to skip → no cookie even if opted in.
        ProtectionConfigStore bare = new ProtectionConfigStore(new ProtectionProperties(), layout(root),
                new YamlStore(), cipher);
        TrustedDeviceService svc = service(bare, cipher);
        assertNull(issue(svc, ProtectionScope.application(), true), "no captcha to skip ⇒ no cookie minted");
    }

    @Test
    void featureDisabledMintsNothingAndTrustsNothing(@TempDir Path root) {
        SecretCipher cipher = realCipher();
        ProtectionProperties props = new ProtectionProperties();
        props.getRememberDevice().setEnabled(false);
        TrustedDeviceService svc = new TrustedDeviceService(props, captchaStore(root, cipher), cipher);
        assertNull(issue(svc, ProtectionScope.application(), true), "disabled ⇒ never mint");
        // Even a cookie that WOULD be valid isn't honored while the feature is off.
        TrustedDeviceService on = service(captchaStore(root, cipher), cipher);
        Cookie valid = issue(on, ProtectionScope.application(), true);
        assertFalse(trustedWith(svc, ProtectionScope.application(), valid), "disabled ⇒ never trust");
    }

    @Test
    void noKeyMintsNothingAndTrustsNothing(@TempDir Path root) {
        SecretCipher noKey = noKeyCipher();
        // With no key the feature is off, so the store is never consulted — a bare one suffices.
        ProtectionConfigStore bare = new ProtectionConfigStore(new ProtectionProperties(), layout(root),
                new YamlStore(), noKey);
        TrustedDeviceService svc = service(bare, noKey);
        assertNull(issue(svc, ProtectionScope.application(), true), "no master key ⇒ can't sign ⇒ no cookie");
        assertFalse(svc.isEnabled(), "the feature reports itself off when the cipher is unavailable");
        assertFalse(trustedWith(svc, ProtectionScope.application(),
                new Cookie("runway_td_app", "anything")), "no key ⇒ never trust");
    }

    @Test
    void anUnscopedTenantIsNeverIssuedOrTrusted(@TempDir Path root) {
        SecretCipher cipher = realCipher();
        TrustedDeviceService svc = service(captchaStore(root, cipher), cipher);
        ProtectionScope unscoped = ProtectionScope.forSite(null);
        assertNull(issue(svc, unscoped, true), "a tenant login with no resolved site can't be keyed");
        assertFalse(trustedWith(svc, unscoped, new Cookie("runway_td_site_x", "anything")),
                "…and isTrusted short-circuits before cookie keying for an unscoped tenant");
    }

    @Test
    void theTrustWindowIsClampedToASaneRange(@TempDir Path root) {
        SecretCipher cipher = realCipher();
        assertEquals(1, svcWithDays(cipher, 0).rememberDays(), "0 clamps up to 1");
        assertEquals(1, svcWithDays(cipher, -5).rememberDays(), "negative clamps up to 1");
        assertEquals(365, svcWithDays(cipher, 10_000).rememberDays(), "an absurd value clamps down to 365");
        assertEquals(30, svcWithDays(cipher, 30).rememberDays(), "an in-range value passes through");
    }

    @Test
    void anEncTaggedButUnsealedCookieIsRejected(@TempDir Path root) {
        SecretCipher cipher = realCipher();
        TrustedDeviceService svc = service(captchaStore(root, cipher), cipher);
        // ENC(...)-tagged so it passes the isEncrypted guard, but the inner text is not a real GCM ciphertext →
        // decrypt throws → not trusted. Complements the plaintext-forgery case (both crypto forgeries fail closed).
        Cookie fake = new Cookie("runway_td_app",
                "ENC(APPLICATION||" + (System.currentTimeMillis() + 99_999_999L) + ")");
        assertFalse(trustedWith(svc, ProtectionScope.application(), fake),
                "an ENC-tagged but not-truly-sealed value fails to decrypt → not trusted");
    }

    private static TrustedDeviceService svcWithDays(SecretCipher cipher, int days) {
        ProtectionProperties props = new ProtectionProperties();
        props.getRememberDevice().setDays(days);
        return new TrustedDeviceService(props, null, cipher);
    }

    // ---- adversarial: every forgery/tamper/mismatch resolves to NOT trusted -----------------------

    @Test
    void aForgedPlaintextCookieIsRejected(@TempDir Path root) {
        SecretCipher cipher = realCipher();
        TrustedDeviceService svc = service(captchaStore(root, cipher), cipher);
        // A client hand-crafts an un-encrypted value naming the right realm + a far-future expiry. SecretCipher
        // passes a non-ENC value through, so WITHOUT the isEncrypted() guard this would be trusted.
        Cookie forged = new Cookie("runway_td_app", "APPLICATION||" + (System.currentTimeMillis() + 99_999_999L));
        assertFalse(trustedWith(svc, ProtectionScope.application(), forged), "a plaintext (unsigned) cookie is not trusted");
    }

    @Test
    void aTamperedCiphertextIsRejected(@TempDir Path root) {
        SecretCipher cipher = realCipher();
        TrustedDeviceService svc = service(captchaStore(root, cipher), cipher);
        Cookie good = issue(svc, ProtectionScope.application(), true);
        // Flip a character in the middle of the sealed value → the GCM auth tag no longer matches → decrypt throws.
        String v = good.getValue();
        int mid = v.length() / 2;
        char flipped = v.charAt(mid) == 'A' ? 'B' : 'A';
        String tampered = v.substring(0, mid) + flipped + v.substring(mid + 1);
        assertFalse(trustedWith(svc, ProtectionScope.application(), new Cookie("runway_td_app", tampered)),
                "a modified ciphertext fails the GCM auth check → not trusted");
    }

    @Test
    void anAppCookiePresentedToATenantScopeIsRejected(@TempDir Path root) {
        SecretCipher cipher = realCipher();
        TrustedDeviceService svc = service(captchaStore(root, cipher), cipher);
        Cookie app = issue(svc, ProtectionScope.application(), true);
        // Re-label the app cookie under the tenant cookie name so the NAME scoping can't be what rejects it —
        // the payload's realm binding must. (Defense in depth beyond host-only + per-site name.)
        Cookie relabelled = new Cookie("runway_td_site_acme", app.getValue());
        assertFalse(trustedWith(svc, ProtectionScope.forSite("acme"), relabelled),
                "an APPLICATION-sealed cookie can't skip a TENANT captcha (realm bound in the payload)");
    }

    @Test
    void aSiteCookiePresentedToAnotherSiteIsRejected(@TempDir Path root) {
        SecretCipher cipher = realCipher();
        TrustedDeviceService svc = service(captchaStore(root, cipher), cipher);
        Cookie acme = issue(svc, ProtectionScope.forSite("acme"), true);
        // Present acme's sealed value under another site's cookie name → the payload's site binding must reject it.
        Cookie relabelled = new Cookie("runway_td_site_other", acme.getValue());
        assertFalse(trustedWith(svc, ProtectionScope.forSite("other"), relabelled),
                "site A's cookie can't skip site B's captcha (site bound in the payload)");
    }

    @Test
    void anExpiredCookieIsRejected(@TempDir Path root) {
        SecretCipher cipher = realCipher();
        TrustedDeviceService svc = service(captchaStore(root, cipher), cipher);
        // Seal a well-formed payload whose expiry is already in the past (same cipher the service trusts).
        String expired = cipher.encrypt("APPLICATION||" + (System.currentTimeMillis() - 1_000L));
        assertFalse(trustedWith(svc, ProtectionScope.application(), new Cookie("runway_td_app", expired)),
                "a past-expiry stamp is not trusted");
    }

    @Test
    void noCookieAtAllIsNotTrusted(@TempDir Path root) {
        SecretCipher cipher = realCipher();
        TrustedDeviceService svc = service(captchaStore(root, cipher), cipher);
        assertFalse(trustedWith(svc, ProtectionScope.application(), null), "no cookie ⇒ not trusted");
    }
}
