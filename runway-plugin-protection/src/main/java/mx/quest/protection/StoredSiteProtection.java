package mx.quest.protection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.function.UnaryOperator;

/**
 * The on-disk YAML shape of a <b>site's</b> protection config ({@code sites/<id>/plugins/protection.yml}) — the
 * honeypot switch and the CAPTCHA section <b>only</b>. The per-IP lockout thresholds (max attempts / window /
 * lockout) are deliberately <b>not</b> here: the lockout is an <b>application-scope platform floor</b>
 * ({@link SlidingWindowLoginGate} reads the app settings and is keyed by IP platform-wide), so a per-site value
 * would never take effect — exposing it per-site was misleading. This mirrors the honeypot/CAPTCHA half of
 * {@link StoredProtection}, keeping the captcha secret in its stored {@code ENC(...)} form.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} so a pre-existing per-site file that still carries the
 * (now-inert) {@code maxAttempts}/{@code windowSeconds}/{@code lockoutSeconds} keys loads cleanly; the next
 * {@code savePerSite} rewrites the file without them, so the stale keys drop out on first edit. A mutable class with
 * a no-arg constructor (not a record) so Jackson can bind a partial/hand-edited file field-by-field.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoredSiteProtection {

    private boolean honeypotEnabled = true;
    private StoredProtection.StoredCaptcha captcha = new StoredProtection.StoredCaptcha();

    public StoredSiteProtection() {
    }

    public StoredSiteProtection(boolean honeypotEnabled, StoredProtection.StoredCaptcha captcha) {
        this.honeypotEnabled = honeypotEnabled;
        this.captcha = captcha != null ? captcha : new StoredProtection.StoredCaptcha();
    }

    /**
     * Converts to usable settings, decrypting the stored captcha secret with {@code decrypt}. The lockout thresholds
     * are passed as {@code 0}, which {@link ProtectionSettings}'s canonical constructor floors to its defaults — they
     * are inert for a site (the app-scope gate governs the lockout), so the value is never read.
     */
    public ProtectionSettings toSettings(UnaryOperator<String> decrypt) {
        StoredProtection.StoredCaptcha c = captcha != null ? captcha : new StoredProtection.StoredCaptcha();
        ProtectionSettings.Captcha resolved = new ProtectionSettings.Captcha(
                c.isEnabled(), c.getProvider(), c.getSiteKey(), decrypt.apply(c.getSecretKey()), c.getScoreThreshold());
        return new ProtectionSettings(honeypotEnabled, 0, 0, 0, resolved);
    }

    public boolean isHoneypotEnabled() {
        return honeypotEnabled;
    }

    public void setHoneypotEnabled(boolean honeypotEnabled) {
        this.honeypotEnabled = honeypotEnabled;
    }

    public StoredProtection.StoredCaptcha getCaptcha() {
        return captcha;
    }

    public void setCaptcha(StoredProtection.StoredCaptcha captcha) {
        this.captcha = captcha;
    }
}
