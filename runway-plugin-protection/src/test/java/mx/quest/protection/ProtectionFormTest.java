package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;
import org.springframework.validation.support.BindingAwareModelMap;

/**
 * The shared screen form logic ({@link ProtectionForm}): a blank captcha-secret field KEEPS the stored secret, a
 * non-blank one replaces it, the provider is normalized to a known value, non-positive thresholds are floored to
 * sane defaults (so a save can never lock everyone out), and the GET model exposes the secret only as a boolean —
 * never its value.
 */
class ProtectionFormTest {

    private static ProtectionSettings withSecret(String secret) {
        return new ProtectionSettings(true, 5, 300, 900,
                new ProtectionSettings.Captcha(true, "recaptcha-v2", "old-site", secret, 0.5));
    }

    @Test
    void aBlankSecretFieldKeepsTheStoredSecret() {
        ProtectionSettings current = withSecret("stored-secret");
        ProtectionSettings merged = ProtectionForm.merge(current, true, 5, 300, 900, true,
                "recaptcha-v2", "new-site", "   ", 0.5);
        assertEquals("stored-secret", merged.captcha().secretKey(), "blank field preserves the stored secret");
        assertEquals("new-site", merged.captcha().siteKey(), "the public site key still updates");
    }

    @Test
    void aNonBlankSecretFieldReplacesTheStoredSecret() {
        ProtectionSettings merged = ProtectionForm.merge(withSecret("stored-secret"), true, 5, 300, 900, true,
                "recaptcha-v2", "site", "  brand-new-secret  ", 0.5);
        assertEquals("brand-new-secret", merged.captcha().secretKey(), "a non-blank secret replaces (and is trimmed)");
    }

    @Test
    void anUnknownOrLegacyProviderNormalizesToRecaptchaV2() {
        ProtectionSettings merged = ProtectionForm.merge(withSecret(""), true, 5, 300, 900, true,
                "bogus-provider", "site", "", 0.5);
        assertEquals("recaptcha-v2", merged.captcha().provider());
        // A dropped legacy provider (Turnstile / hCaptcha) likewise migrates to the v2 default.
        assertEquals("recaptcha-v2",
                ProtectionForm.merge(withSecret(""), true, 5, 300, 900, true, "turnstile", "s", "", 0.5)
                        .captcha().provider());
        // A supported provider (any case) is preserved lower-cased.
        assertEquals("recaptcha-v3",
                ProtectionForm.merge(withSecret(""), true, 5, 300, 900, true, "reCAPTCHA-V3", "s", "", 0.5)
                        .captcha().provider());
    }

    @Test
    void nonPositiveThresholdsAreFlooredToSaneDefaults() {
        // Zero/negative thresholds must not lock everyone out — ProtectionSettings' constructor floors them.
        ProtectionSettings merged = ProtectionForm.merge(withSecret(""), true, 0, -1, 0, false, "recaptcha-v2", "", "", 0.5);
        assertTrue(merged.maxAttempts() > 0, "maxAttempts floored to a positive default");
        assertTrue(merged.windowSeconds() > 0, "windowSeconds floored to a positive default");
        assertTrue(merged.lockoutSeconds() > 0, "lockoutSeconds floored to a positive default");
    }

    @Test
    void populateMasksTheSecretAsABooleanOnly() {
        Model model = new BindingAwareModelMap();
        ProtectionForm.populate(model, withSecret("s3cr3t"), true);
        assertEquals(Boolean.TRUE, model.getAttribute("captchaSecretConfigured"));
        assertEquals(Boolean.TRUE, model.getAttribute("captchaSecretStorable"));
        assertFalse(model.asMap().containsValue("s3cr3t"), "the plaintext secret is NEVER put into the model");

        Model none = new BindingAwareModelMap();
        ProtectionForm.populate(none, withSecret(""), false);
        assertEquals(Boolean.FALSE, none.getAttribute("captchaSecretConfigured"), "no secret → not configured");
        assertEquals(Boolean.FALSE, none.getAttribute("captchaSecretStorable"), "no cipher → not storable (hint)");
    }
}
