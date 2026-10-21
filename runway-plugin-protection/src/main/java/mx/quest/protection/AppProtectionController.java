package mx.quest.protection;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The <b>save</b> handler for the app-scope login-protection policy ({@link ProtectionSettings}) that governs the
 * <b>operator login</b>: the honeypot switch, the per-IP lockout thresholds, and the external CAPTCHA (provider,
 * public site key, secret, v3 score threshold). It edits <b>only</b> the app scope — never any tenant's per-site
 * config.
 *
 * <p>There is no standalone screen: the controls render as the plugin's own <b>panel</b> ({@code templates/
 * admin-app.html}, bound via {@link AppProtectionPanel}) on the kernel's plugin-settings page
 * ({@code /__app/plugins/protection/settings}) — a plugin's config lives on its own settings page, not a bespoke
 * menu entry. This controller only accepts the panel's POST and redirects back to that page. Gated by the plugin's
 * own {@code protection|settings} control point (app realm; the app master/wildcard admin always passes).
 *
 * <p><b>Immediate apply.</b> A save updates {@link ProtectionConfigStore}'s live app snapshot and persists it, so
 * the login gate and form guard — which read the store fresh on every check — see the change on the next login
 * with no restart (the panel's whole reason for being over the restart-only generic settings form).
 *
 * <p><b>Secret handling.</b> The captcha secret is <b>masked</b> in the panel (a boolean "configured?" flag, never
 * the plaintext or the {@code ENC(...)} blob), and a <b>blank</b> secret field on POST <b>keeps</b> the stored
 * secret (only a non-blank value replaces it, encrypted at rest).
 *
 * <p><b>Fail-safe validation.</b> The lockout thresholds are floored to sane positives before saving (a value
 * {@code ≤ 0} falls back to the {@link ProtectionSettings} defaults through its canonical constructor), so a save
 * can never persist a config that would lock every operator out irrecoverably.
 */
@Controller
public class AppProtectionController {

    /** Where the panel lives — the POST redirects back here so the flash shows on the plugin settings page. */
    static final String SETTINGS_PAGE = "/__app/plugins/protection/settings";

    private final ProtectionConfigStore store;
    private final MessageSource messages;

    public AppProtectionController(ProtectionConfigStore store,
                                   @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.store = store;
        this.messages = messages;
    }

    @PostMapping("/__app/protection")
    @PreAuthorize("@authz.may('protection|settings')")
    public String save(@RequestParam(name = "honeypotEnabled", defaultValue = "false") boolean honeypotEnabled,
                       @RequestParam(defaultValue = "0") int maxAttempts,
                       @RequestParam(defaultValue = "0") int windowSeconds,
                       @RequestParam(defaultValue = "0") int lockoutSeconds,
                       @RequestParam(name = "captchaEnabled", defaultValue = "false") boolean captchaEnabled,
                       @RequestParam(required = false) String provider,
                       @RequestParam(required = false) String siteKey,
                       @RequestParam(required = false) String secretKey,
                       @RequestParam(defaultValue = "0.5") double scoreThreshold,
                       RedirectAttributes redirect) {
        ProtectionSettings current = store.appSettings();
        try {
            ProtectionSettings updated = ProtectionForm.merge(current, honeypotEnabled, maxAttempts, windowSeconds,
                    lockoutSeconds, captchaEnabled, provider, siteKey, secretKey, scoreThreshold);
            store.saveApp(updated);
            redirect.addFlashAttribute("flash", message("protection.screen.saved", "Protection settings saved."));
        } catch (IllegalStateException noKey) {
            redirect.addFlashAttribute("error",
                    message("protection.screen.noCipher", "Cannot store the captcha secret: no encryption key is "
                            + "configured (runway.security.secret-key)."));
        } catch (IOException e) {
            redirect.addFlashAttribute("error",
                    message("protection.screen.failed", "Could not save protection settings."));
        }
        return "redirect:" + SETTINGS_PAGE;
    }

    private String message(String key, String fallback) {
        return messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }
}
