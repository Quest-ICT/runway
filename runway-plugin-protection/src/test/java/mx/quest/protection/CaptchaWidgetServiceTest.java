package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import mx.quest.runway.console.AssetVersion;
import mx.quest.runway.security.ProtectionScope;
import mx.quest.runway.spi.model.CaptchaWidget;
import org.junit.jupiter.api.Test;

/**
 * The reusable site-form CAPTCHA seam: an inactive site yields an inactive widget and always-accept verify; an
 * active Turnstile site yields the visible widget markup + provider script; reCAPTCHA v3 yields the invisible
 * hidden-field + execute script; and verify delegates to {@link CaptchaVerifier} only when a challenge is active.
 */
class CaptchaWidgetServiceTest {

    private static ProtectionSettings withCaptcha(ProtectionSettings.Captcha captcha) {
        return new ProtectionSettings(true, 10, 900, 900, captcha);
    }

    private static ProtectionConfigStore storeReturning(ProtectionSettings.Captcha captcha) {
        ProtectionConfigStore store = mock(ProtectionConfigStore.class);
        when(store.resolve(any(ProtectionScope.class))).thenReturn(withCaptcha(captcha));
        return store;
    }

    @Test
    void anInactiveSiteYieldsAnInactiveWidgetAndAlwaysAcceptsVerify() {
        CaptchaWidgetService svc = new CaptchaWidgetService(
                storeReturning(ProtectionSettings.Captcha.disabled()), mock(CaptchaVerifier.class));

        CaptchaWidget widget = svc.widgetForSite("acme");
        assertFalse(widget.active(), "no configured challenge ⇒ inactive");
        assertTrue(widget.html().isEmpty(), "no markup when inactive");
        assertTrue(svc.verify("acme", null, null), "verify accepts unconditionally when inactive");
    }

    @Test
    void anActiveRecaptchaV2SiteRendersTheVisibleWidget() {
        ProtectionSettings.Captcha v2 =
                new ProtectionSettings.Captcha(true, "recaptcha-v2", "PUBLIC-KEY", "SECRET", 0.5);
        CaptchaWidgetService svc = new CaptchaWidgetService(storeReturning(v2), mock(CaptchaVerifier.class));

        CaptchaWidget widget = svc.widgetForSite("acme");
        assertTrue(widget.active());
        assertEquals("g-recaptcha-response", widget.tokenField());
        assertTrue(widget.html().contains("g-recaptcha"), "the provider widget class is present");
        assertTrue(widget.html().contains("data-sitekey=\"PUBLIC-KEY\""), "the PUBLIC site key is embedded");
        assertTrue(widget.html().contains("www.google.com/recaptcha/api.js"), "the provider script is loaded");
        assertFalse(widget.html().contains("SECRET"), "the secret key is NEVER embedded in the widget");
    }

    @Test
    void anActiveRecaptchaV3SiteRendersTheInvisibleField() {
        ProtectionSettings.Captcha v3 =
                new ProtectionSettings.Captcha(true, "recaptcha-v3", "V3-KEY", "SECRET", 0.7);
        CaptchaWidgetService svc = new CaptchaWidgetService(storeReturning(v3), mock(CaptchaVerifier.class));

        CaptchaWidget widget = svc.widgetForSite("acme");
        assertTrue(widget.active());
        assertEquals("g-recaptcha-response", widget.tokenField());
        assertTrue(widget.html().contains("rw-captcha-v3"), "the invisible v3 hidden field is present");
        // The submit-time execute logic is now the shared, externalized client (no inline <script> — CSP script-src
        // dropped 'unsafe-inline'); the widget references it by src alongside the provider script.
        assertTrue(widget.html().contains("/__assets/captcha-v3.js"), "the v3 client is referenced by src");
        assertFalse(widget.html().contains("<script>"), "no inline <script> — it would violate the tightened CSP");
        assertTrue(widget.html().contains("render=V3-KEY"), "the v3 provider script carries ?render=<siteKey>");
    }

    @Test
    void theV3ClientReferenceCarriesTheKernelAssetToken() {
        ProtectionSettings.Captcha v3 =
                new ProtectionSettings.Captcha(true, "recaptcha-v3", "V3-KEY", "SECRET", 0.7);
        // Wired with the kernel's token (as ProtectionConfig does), the client URL changes with every release, so a
        // day-cached old client can never outlive an upgrade.
        String versioned = new CaptchaWidgetService(storeReturning(v3), mock(CaptchaVerifier.class),
                AssetVersion.fixed("1760000000000")).widgetForSite("acme").html();
        assertTrue(versioned.contains("src=\"/__assets/captcha-v3.js?v=1760000000000\""), versioned);
        // The legacy two-argument constructor stays unversioned (served no-cache by the kernel: correct, slower).
        String legacy = new CaptchaWidgetService(storeReturning(v3), mock(CaptchaVerifier.class))
                .widgetForSite("acme").html();
        assertTrue(legacy.contains("src=\"/__assets/captcha-v3.js\""), legacy);
    }

    @Test
    void verifyDelegatesToTheVerifierWhenActive() {
        ProtectionSettings.Captcha v2 =
                new ProtectionSettings.Captcha(true, "recaptcha-v2", "PUBLIC-KEY", "SECRET", 0.5);
        CaptchaVerifier verifier = mock(CaptchaVerifier.class);
        when(verifier.verify(any(), eq("good-token"), any())).thenReturn(true);
        when(verifier.verify(any(), eq("bad-token"), any())).thenReturn(false);
        CaptchaWidgetService svc = new CaptchaWidgetService(storeReturning(v2), verifier);

        assertTrue(svc.verify("acme", "good-token", "1.2.3.4"));
        assertFalse(svc.verify("acme", "bad-token", "1.2.3.4"));
    }
}
