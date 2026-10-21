package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.crypto.AesGcmSecretCipher;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.security.ProtectionScope;
import mx.quest.runway.security.RunwaySecurityProperties;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

/**
 * The login-view contributor sets the generic captcha model flags (the PUBLIC site key + provider flags + script
 * url) when a scope's captcha is active, and only {@code captchaActive=false} when it is not. It must never leak
 * the captcha secret into the model.
 */
class CaptchaLoginViewContributorTest {

    private static SecretCipher passthroughCipher() {
        return new SecretCipher() {
            @Override public boolean isAvailable() { return true; }
            @Override public String encrypt(String plaintext) { return "ENC(" + plaintext + ")"; }
            @Override public String decrypt(String value) { return value; }
            @Override public boolean isEncrypted(String value) { return value != null && value.startsWith("ENC("); }
        };
    }

    /**
     * Trusted-device service with the feature OFF, so {@code isTrusted} is false and {@code rememberDeviceOffered}
     * false — the captcha slot flags render exactly as before. (The trust-based skip is covered by
     * {@link TrustedDeviceServiceTest}; the contributor's offer/skip wiring by dedicated cases below.)
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

    private static ProtectionConfigStore appStore(Path root, ProtectionProperties.Captcha appCaptcha) {
        ProtectionProperties props = new ProtectionProperties();
        if (appCaptcha != null) {
            props.getLogin().setCaptcha(appCaptcha);
        }
        return new ProtectionConfigStore(props, layout(root), new YamlStore(), passthroughCipher());
    }

    /**
     * A real {@link SiteResolver} over the temp external tree. No site is bound by default, so
     * {@link SiteResolver#current()} is empty (app-realm behaviour); a test that needs a bound site calls
     * {@link SiteResolver#bind(Site)} on it. Real stores are cheap here (no site files exist, so lookups are empty).
     */
    private static SiteResolver resolver(Path root) {
        StorageLayout layout = layout(root);
        YamlStore yaml = new YamlStore();
        return new SiteResolver(new SiteStore(layout, yaml), new PlatformSettingsStore(layout, yaml));
    }

    private static Site site(String id) {
        return new Site(id, id + " Ltd", List.of(id + ".example"), Locale.ENGLISH, List.of(),
                "https://" + id + ".example", SiteStatus.ACTIVE);
    }

    private static MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    private static ProtectionProperties.Captcha appCaptcha(boolean enabled, String provider, String site, String secret) {
        ProtectionProperties.Captcha c = new ProtectionProperties.Captcha();
        c.setEnabled(enabled);
        c.setProvider(provider);
        c.setSiteKey(site);
        c.setSecretKey(secret);
        return c;
    }

    /** A real AES-GCM cipher so an enabled trusted-device service can mint + verify a genuine cookie. */
    private static SecretCipher realCipher() {
        RunwaySecurityProperties security = new RunwaySecurityProperties();
        security.setSecretKey("contributor-test-master-key-0123456789");
        return new AesGcmSecretCipher(security);
    }

    private static TrustedDeviceService enabledTd(ProtectionConfigStore store, SecretCipher cipher) {
        return new TrustedDeviceService(new ProtectionProperties(), store, cipher); // remember-device on, 30 days
    }

    // ---- "remember this device" offer + trusted-device skip ---------------------------------------

    @Test
    void theRememberOfferAppearsOnLoginWhenACaptchaIsShown(@TempDir Path root) throws Exception {
        SecretCipher cipher = realCipher();
        ProtectionConfigStore store = appStore(root, appCaptcha(true, "recaptcha-v2", "K", "S"));
        CaptchaLoginViewContributor contributor =
                new CaptchaLoginViewContributor(store, resolver(root), enabledTd(store, cipher));

        ModelAndView mav = new ModelAndView("operator/login-app");
        contributor.postHandle(get("/app/login"), new MockHttpServletResponse(), new Object(), mav);

        assertEquals(Boolean.TRUE, mav.getModel().get("captchaActive"), "no cookie yet ⇒ captcha shown");
        assertEquals(Boolean.TRUE, mav.getModel().get("rememberDeviceOffered"), "…so the remember-device box is offered");
        assertEquals(30, mav.getModel().get("rememberDeviceDays"), "the configured trust window reaches the view");
    }

    @Test
    void theRememberOfferIsNotShownOnTheForgotPasswordForm(@TempDir Path root) throws Exception {
        SecretCipher cipher = realCipher();
        ProtectionConfigStore store = appStore(root, appCaptcha(true, "recaptcha-v2", "K", "S"));
        CaptchaLoginViewContributor contributor =
                new CaptchaLoginViewContributor(store, resolver(root), enabledTd(store, cipher));

        ModelAndView mav = new ModelAndView("operator/recovery-request");
        contributor.postHandle(get("/app/forgot-password"), new MockHttpServletResponse(), new Object(), mav);

        assertEquals(Boolean.TRUE, mav.getModel().get("captchaActive"), "the recovery form still shows a captcha");
        assertEquals(Boolean.FALSE, mav.getModel().get("rememberDeviceOffered"),
                "but 'remember this device' only makes sense on the login form");
    }

    @Test
    void aTrustedDeviceHidesTheCaptchaAndTheOffer(@TempDir Path root) throws Exception {
        SecretCipher cipher = realCipher();
        ProtectionConfigStore store = appStore(root, appCaptcha(true, "recaptcha-v2", "K", "S"));
        TrustedDeviceService svc = enabledTd(store, cipher);

        // Mint a real cookie via the issue path (opt-in login POST with an active captcha).
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/app/login");
        post.addParameter(TrustedDeviceService.REMEMBER_PARAM, "1");
        MockHttpServletResponse minted = new MockHttpServletResponse();
        svc.rememberIfRequested(post, minted, ProtectionScope.application());
        String header = minted.getHeader(org.springframework.http.HttpHeaders.SET_COOKIE);
        String nameValue = header.substring(0, header.indexOf(';'));
        int eq = nameValue.indexOf('=');

        MockHttpServletRequest getReq = get("/app/login");
        getReq.setCookies(new Cookie(nameValue.substring(0, eq), nameValue.substring(eq + 1)));
        CaptchaLoginViewContributor contributor = new CaptchaLoginViewContributor(store, resolver(root), svc);

        ModelAndView mav = new ModelAndView("operator/login-app");
        contributor.postHandle(getReq, new MockHttpServletResponse(), new Object(), mav);

        assertEquals(Boolean.FALSE, mav.getModel().get("captchaActive"), "a trusted device sees no captcha widget");
        assertEquals(Boolean.FALSE, mav.getModel().get("rememberDeviceOffered"), "…and no remember-device offer");
    }

    @Test
    void anActiveCaptchaSetsPublicFlagsAndNeverTheSecret(@TempDir Path root) throws Exception {
        ProtectionConfigStore store = appStore(root, appCaptcha(true, "recaptcha-v2", "PUBLIC-SITE-KEY", "SECRET-XYZ"));
        CaptchaLoginViewContributor contributor = new CaptchaLoginViewContributor(store, resolver(root), td());

        ModelAndView mav = new ModelAndView("operator/login-app");
        contributor.postHandle(get("/app/login"), new MockHttpServletResponse(), new Object(), mav);
        Map<String, Object> model = mav.getModel();

        assertEquals(Boolean.TRUE, model.get("captchaActive"));
        assertEquals("recaptcha-v2", model.get("captchaProvider"));
        assertEquals(Boolean.TRUE, model.get("captchaRecaptchaV2"));
        assertEquals(Boolean.FALSE, model.get("captchaRecaptchaV3"));
        assertEquals("PUBLIC-SITE-KEY", model.get("captchaSiteKey"), "the PUBLIC site key reaches the view");
        assertEquals("g-recaptcha", model.get("captchaWidgetClass"));
        assertEquals("g-recaptcha-response", model.get("captchaTokenField"));
        assertTrue(((String) model.get("captchaScriptUrl")).startsWith("https://www.google.com/recaptcha/"));

        // The secret must never appear anywhere in the model — not as a value, not spliced into a url.
        for (Object v : model.values()) {
            if (v instanceof String s) {
                assertFalse(s.contains("SECRET-XYZ"), "the captcha secret must never reach the view");
            }
        }
    }

    @Test
    void theRecoveryRequestFormAlsoGetsTheScopeCaptcha(@TempDir Path root) throws Exception {
        // The forgot-password REQUEST form renders the same app-scope challenge as /app/login, so a configured
        // CAPTCHA also guards the reset-email request.
        ProtectionConfigStore store = appStore(root, appCaptcha(true, "recaptcha-v2", "PUBLIC-SITE-KEY", "SECRET-XYZ"));
        CaptchaLoginViewContributor contributor = new CaptchaLoginViewContributor(store, resolver(root), td());

        ModelAndView mav = new ModelAndView("operator/recovery-forgot");
        contributor.postHandle(get("/app/forgot-password"), new MockHttpServletResponse(), new Object(), mav);

        assertEquals(Boolean.TRUE, mav.getModel().get("captchaActive"), "the forgot form gets the app-scope captcha");
        assertEquals("PUBLIC-SITE-KEY", mav.getModel().get("captchaSiteKey"));
    }

    @Test
    void recaptchaV3SetsTheRenderScriptAndNoVisibleWidgetClass(@TempDir Path root) throws Exception {
        ProtectionConfigStore store = appStore(root, appCaptcha(true, "recaptcha-v3", "V3KEY", "SECRET"));
        CaptchaLoginViewContributor contributor = new CaptchaLoginViewContributor(store, resolver(root), td());

        ModelAndView mav = new ModelAndView("operator/login-app");
        contributor.postHandle(get("/app/login"), new MockHttpServletResponse(), new Object(), mav);
        Map<String, Object> model = mav.getModel();

        assertEquals(Boolean.TRUE, model.get("captchaRecaptchaV3"));
        assertEquals("", model.get("captchaWidgetClass"), "v3 is invisible — no widget class");
        assertEquals("g-recaptcha-response", model.get("captchaTokenField"));
        assertTrue(((String) model.get("captchaScriptUrl")).contains("render=V3KEY"),
                "v3 splices the public site key into ?render=");
    }

    @Test
    void anInactiveCaptchaSetsOnlyTheFalseFlag(@TempDir Path root) throws Exception {
        ProtectionConfigStore store = appStore(root, appCaptcha(false, "recaptcha-v2", "", "")); // disabled
        CaptchaLoginViewContributor contributor = new CaptchaLoginViewContributor(store, resolver(root), td());

        ModelAndView mav = new ModelAndView("operator/login-app");
        contributor.postHandle(get("/app/login"), new MockHttpServletResponse(), new Object(), mav);
        Map<String, Object> model = mav.getModel();

        assertEquals(Boolean.FALSE, model.get("captchaActive"));
        assertNull(model.get("captchaSiteKey"), "no key, no widget when inactive");
        assertNull(model.get("captchaProvider"));
        assertNull(model.get("captchaScriptUrl"));
    }

    @Test
    void aNonLoginPathIsUntouched(@TempDir Path root) throws Exception {
        ProtectionConfigStore store = appStore(root, appCaptcha(true, "recaptcha-v2", "K", "S"));
        CaptchaLoginViewContributor contributor = new CaptchaLoginViewContributor(store, resolver(root), td());

        ModelAndView mav = new ModelAndView("operator/dashboard");
        contributor.postHandle(get("/__app/dashboard"), new MockHttpServletResponse(), new Object(), mav);
        assertTrue(mav.getModel().isEmpty(), "a non-login view gets no captcha attributes");
    }

    @Test
    void aNullModelAndViewIsIgnored(@TempDir Path root) throws Exception {
        ProtectionConfigStore store = appStore(root, appCaptcha(true, "recaptcha-v2", "K", "S"));
        CaptchaLoginViewContributor contributor = new CaptchaLoginViewContributor(store, resolver(root), td());
        // A redirect with no view — must not throw.
        contributor.postHandle(get("/app/login"), new MockHttpServletResponse(), new Object(), null);
    }

    @Test
    void theTenantLoginResolvesTheBoundSitesPerSiteCaptcha(@TempDir Path root) throws Exception {
        // The app scope has NO captcha; the bound site does — proving the /login path reads the per-site config,
        // not the app's, via SiteResolver.current().
        ProtectionConfigStore store = appStore(root, appCaptcha(false, "recaptcha-v2", "", ""));
        store.savePerSite("acme", new ProtectionSettings(true, 5, 300, 900,
                new ProtectionSettings.Captcha(true, "recaptcha-v3", "SITE-ACME", "SECRET-ACME", 0.5)));

        SiteResolver resolver = resolver(root);
        resolver.bind(site("acme"));
        try {
            CaptchaLoginViewContributor contributor = new CaptchaLoginViewContributor(store, resolver, td());
            ModelAndView mav = new ModelAndView("operator/login-tenant");
            contributor.postHandle(get("/login"), new MockHttpServletResponse(), new Object(), mav);
            Map<String, Object> model = mav.getModel();

            assertEquals(Boolean.TRUE, model.get("captchaActive"), "the bound site's captcha governs the tenant login");
            assertEquals("recaptcha-v3", model.get("captchaProvider"));
            assertEquals("SITE-ACME", model.get("captchaSiteKey"));
            for (Object v : model.values()) {
                if (v instanceof String s) {
                    assertFalse(s.contains("SECRET-ACME"), "the per-site captcha secret must never reach the view");
                }
            }
        } finally {
            resolver.clear();
        }
    }
}
