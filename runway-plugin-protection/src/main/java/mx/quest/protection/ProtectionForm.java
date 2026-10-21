package mx.quest.protection;

import java.util.List;
import org.springframework.ui.Model;

/**
 * Shared form logic for the two dedicated protection screens ({@link AppProtectionController} and
 * {@link SiteProtectionController}): it maps posted fields onto a {@link ProtectionSettings} (preserving the
 * stored captcha secret when the field is left blank) and populates the view model for the GET render (masking
 * the secret so it is never sent to the client). Keeping it in one place means the app and per-site screens can
 * never drift on the secret-preserve-on-blank rule.
 */
final class ProtectionForm {

    /**
     * The captcha modes offered by the screens' provider select — the two Google reCAPTCHA modes
     * {@link CaptchaVerifier} supports: {@code recaptcha-v2} (visible checkbox) and {@code recaptcha-v3}
     * (invisible, score-based). Turnstile and hCaptcha were dropped in 0.4.0 (see {@link CaptchaCspContributor}).
     */
    static final List<String> PROVIDERS = List.of("recaptcha-v2", "recaptcha-v3");

    private ProtectionForm() {
    }

    /**
     * Builds the new settings from the posted fields, layered over {@code current} so a <b>blank</b> secret field
     * <b>keeps</b> the currently-stored secret (only a non-blank value replaces it). Thresholds are passed through
     * as-is; {@link ProtectionSettings}'s canonical constructor floors any non-positive value to a sane default,
     * so a save can never persist a lock-everyone-out config. The provider is normalized to a known value.
     *
     * @param current        the settings currently in force (its captcha secret is the fallback for a blank field)
     * @param honeypotEnabled the honeypot switch
     * @param maxAttempts     failed logins in the window that trip the lockout (floored if {@code ≤ 0})
     * @param windowSeconds   the sliding failure window (floored if {@code ≤ 0})
     * @param lockoutSeconds  the lockout duration (floored if {@code ≤ 0})
     * @param captchaEnabled  whether the CAPTCHA challenge is required
     * @param provider        the reCAPTCHA mode (blank/unknown/legacy ⇒ recaptcha-v2)
     * @param siteKey         the public site key (rendered into the widget)
     * @param secretKey       the new secret, or blank to KEEP the stored one
     * @param scoreThreshold  the reCAPTCHA v3 minimum score
     * @return the merged, normalized settings ready to persist
     */
    static ProtectionSettings merge(ProtectionSettings current, boolean honeypotEnabled, int maxAttempts,
                                    int windowSeconds, int lockoutSeconds, boolean captchaEnabled, String provider,
                                    String siteKey, String secretKey, double scoreThreshold) {
        // Blank secret field => keep the existing stored secret (never overwrite a configured secret with blank).
        String resolvedSecret = (secretKey == null || secretKey.isBlank())
                ? current.captcha().secretKey()
                : secretKey.strip();
        ProtectionSettings.Captcha captcha = new ProtectionSettings.Captcha(
                captchaEnabled, normalizeProvider(provider), trimToEmpty(siteKey), resolvedSecret, scoreThreshold);
        return new ProtectionSettings(honeypotEnabled, maxAttempts, windowSeconds, lockoutSeconds, captcha);
    }

    /**
     * Adds the view-model attributes the screen templates read. The captcha secret is exposed <b>only</b> as a
     * boolean {@code captchaSecretConfigured} — never the plaintext or the {@code ENC(...)} blob — so a secret is
     * never rendered into an input. {@code captchaSecretStorable} drives the "no encryption key" hint.
     */
    static void populate(Model model, ProtectionSettings settings, boolean secretStorable) {
        model.addAttribute("settings", settings);
        model.addAttribute("providers", PROVIDERS);
        // Secret masking: the template shows "configured"/placeholder, and a blank submit keeps the stored value.
        model.addAttribute("captchaSecretConfigured",
                settings.captcha().secretKey() != null && !settings.captcha().secretKey().isBlank());
        model.addAttribute("captchaSecretStorable", secretStorable);
    }

    private static String normalizeProvider(String provider) {
        if (provider == null) {
            return "recaptcha-v2";
        }
        String p = provider.strip().toLowerCase(java.util.Locale.ROOT);
        return PROVIDERS.contains(p) ? p : "recaptcha-v2";
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.strip();
    }
}
