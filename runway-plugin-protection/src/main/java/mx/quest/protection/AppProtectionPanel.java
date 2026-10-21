package mx.quest.protection;

import java.util.List;

/**
 * The read-model the app-level Essential Protection panel ({@code templates/admin-app.html}) binds to. The panel
 * renders on the plugin's OWN settings page ({@code /__app/plugins/protection/settings}) — a kernel-owned page that
 * does not populate the plugin's model — so the fragment pulls the current app-scope login-protection settings
 * through this bean via {@code ${@appProtectionPanel.*}} rather than controller-set model attributes.
 *
 * <p>Every getter reads the live app snapshot ({@link ProtectionConfigStore#appSettings()}), so the panel reflects
 * the last {@linkplain AppProtectionController immediate save} with no restart. The captcha secret is exposed
 * <b>only</b> as a boolean ({@link #secretConfigured()}) — never the plaintext or the {@code ENC(...)} blob — so a
 * secret is never rendered into an input; {@link #secretStorable()} drives the "no encryption key" hint.
 */
public class AppProtectionPanel {

    private final ProtectionConfigStore store;

    public AppProtectionPanel(ProtectionConfigStore store) {
        this.store = store;
    }

    private ProtectionSettings current() {
        return store.appSettings();
    }

    public boolean honeypotEnabled() {
        return current().honeypotEnabled();
    }

    public int maxAttempts() {
        return current().maxAttempts();
    }

    public int windowSeconds() {
        return current().windowSeconds();
    }

    public int lockoutSeconds() {
        return current().lockoutSeconds();
    }

    public boolean captchaEnabled() {
        return current().captcha().enabled();
    }

    public String provider() {
        return current().captcha().provider();
    }

    public String siteKey() {
        return current().captcha().siteKey();
    }

    public double scoreThreshold() {
        return current().captcha().scoreThreshold();
    }

    /** The captcha providers the select offers (the four {@link CaptchaVerifier} knows). */
    public List<String> providers() {
        return ProtectionForm.PROVIDERS;
    }

    /** Whether a captcha secret is currently stored — the panel shows a "configured" hint, never the value. */
    public boolean secretConfigured() {
        String secret = current().captcha().secretKey();
        return secret != null && !secret.isBlank();
    }

    /** Whether a secret can be stored at all (an encryption key is configured); false ⇒ the panel warns. */
    public boolean secretStorable() {
        return store.secretStorable();
    }
}
