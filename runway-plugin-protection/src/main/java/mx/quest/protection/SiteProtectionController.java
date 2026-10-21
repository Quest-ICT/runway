package mx.quest.protection;

import java.io.IOException;
import mx.quest.runway.spi.service.ConsoleScreen;
import mx.quest.runway.spi.service.RequestContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The tenant-realm <b>Protection</b> screen ({@code /__admin/protection}) — a dedicated custom editor for the
 * <b>bound site's</b> per-site login-protection policy, stored in {@code sites/<id>/plugins/protection.yml}: the
 * honeypot switch and the external CAPTCHA (provider, public site key, secret, v3 score threshold).
 *
 * <p><b>The per-IP lockout is deliberately NOT here.</b> The lockout ({@link SlidingWindowLoginGate}) reads the
 * <b>application-scope</b> thresholds and is keyed by IP platform-wide, so a per-site value would never take effect;
 * offering it per-site was misleading, so the screen (and {@link StoredSiteProtection}) omit it. Per-site protection
 * is honeypot + CAPTCHA only; the app screen owns the lockout floor.
 *
 * <p><b>Isolation.</b> The tenancy is taken from {@link RequestContext#site()} (the host-resolved bound site),
 * <b>never</b> a path or query parameter, so this screen can only ever read and write the tenant of the request —
 * site A's screen can never touch site B's config. Gated by the kernel {@code CONFIGURE_SITE} permission (mirrors
 * the SEO / Icons tenant screens); the plugin's app-scope config is untouched from here.
 *
 * <p>Secret masking, blank-preserves-the-secret and immediate apply are the same as the app screen (shared
 * {@link ProtectionForm} logic). It renders inside {@code op-layout} as a {@link ConsoleScreen}.
 */
@Controller
@ConsoleScreen
public class SiteProtectionController {

    private final RequestContext requestContext;
    private final ProtectionConfigStore store;
    private final MessageSource messages;

    public SiteProtectionController(RequestContext requestContext, ProtectionConfigStore store,
                                    @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.requestContext = requestContext;
        this.store = store;
        this.messages = messages;
    }

    @GetMapping("/__admin/protection")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String form(Model model) {
        ProtectionForm.populate(model, store.perSiteSettings(currentSiteId()), store.secretStorable());
        model.addAttribute(ConsoleScreen.CONTENT_TEMPLATE, "__plugin__/protection/site-protection");
        model.addAttribute(ConsoleScreen.PAGE_TITLE_KEY, "protection.screen.site.title");
        return ConsoleScreen.LAYOUT_VIEW;
    }

    @PostMapping("/__admin/protection")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String save(@RequestParam(name = "honeypotEnabled", defaultValue = "false") boolean honeypotEnabled,
                       @RequestParam(name = "captchaEnabled", defaultValue = "false") boolean captchaEnabled,
                       @RequestParam(required = false) String provider,
                       @RequestParam(required = false) String siteKey,
                       @RequestParam(required = false) String secretKey,
                       @RequestParam(defaultValue = "0.5") double scoreThreshold,
                       RedirectAttributes redirect) {
        String siteId = currentSiteId();
        ProtectionSettings current = store.perSiteSettings(siteId);
        try {
            // The per-IP lockout is app-scope (not editable per-site): its thresholds are passed as 0 — the canonical
            // constructor defaults them and savePerSite drops them from the site file. Only honeypot + captcha persist.
            ProtectionSettings updated = ProtectionForm.merge(current, honeypotEnabled, 0, 0, 0,
                    captchaEnabled, provider, siteKey, secretKey, scoreThreshold);
            store.savePerSite(siteId, updated);
            redirect.addFlashAttribute("flash", message("protection.screen.saved", "Protection settings saved."));
        } catch (IllegalStateException noKey) {
            redirect.addFlashAttribute("error",
                    message("protection.screen.noCipher", "Cannot store the captcha secret: no encryption key is "
                            + "configured (runway.security.secret-key)."));
        } catch (IOException e) {
            redirect.addFlashAttribute("error",
                    message("protection.screen.failed", "Could not save protection settings."));
        }
        return "redirect:/__admin/protection";
    }

    /** The bound tenancy's id, from the host resolution — never a request parameter (isolation). */
    private String currentSiteId() {
        return requestContext.site()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"))
                .id();
    }

    private String message(String key, String fallback) {
        return messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }
}
