package mx.quest.runway.console;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import mx.quest.runway.recovery.PasswordRecoveryService;
import mx.quest.runway.recovery.RecoveryLinks;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <b>tenant-realm</b> password-recovery flow — {@code /forgot-password} and {@code /reset}, served on a
 * site's own host (the tenant counterpart to {@code /login}). The bound site is resolved from the host by the
 * {@code SiteContextFilter} before this runs, so recovery is automatically scoped to that tenancy: it can only
 * find/reset that site's own sub-users and the tenant-superadmins assigned to it, and a reset link is honoured
 * only on the host it was issued for (the grant's site must equal the resolved site).
 *
 * <p>Like the login page, the routes are public; the flow is non-enumerating (a POST always shows the same
 * neutral confirmation). All logic lives in {@link PasswordRecoveryService}.
 *
 * <p>The emailed link points at the site's own base URL ({@link RecoveryLinks}), never at the host the request named:
 * an unknown host reaches the default site, and a link built from it would hand the token to whoever sent it.
 */
@Controller
public class TenantRecoveryController {

    private static final Logger log = LoggerFactory.getLogger(TenantRecoveryController.class);
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final PasswordRecoveryService recovery;
    private final SiteResolver siteResolver;
    private final RecoveryLinks links;

    public TenantRecoveryController(PasswordRecoveryService recovery, SiteResolver siteResolver,
                                    RecoveryLinks links) {
        this.recovery = recovery;
        this.siteResolver = siteResolver;
        this.links = links;
    }

    @GetMapping("/forgot-password")
    public String forgot(@RequestParam(required = false) String sent, Model model) {
        chrome(model, "/forgot-password");
        model.addAttribute("sent", sent != null);
        return "operator/recovery-forgot";
    }

    @PostMapping("/forgot-password")
    public String requestLink(@RequestParam(required = false) String email, Locale locale) {
        Optional<Site> site = siteResolver.current();
        Optional<String> base = site.flatMap(links::siteBase);
        if (base.isPresent()) {
            Function<String, String> linkFor =
                    token -> base.get() + "/reset?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
            log.info("password recovery requested for site '{}'; a link, if mailed, points at {}", site.get().id(),
                    base.get());
            recovery.requestTenant(site.get().id(), email, brand(), linkFor, locale);
        } else {
            log.warn("password recovery requested for site '{}', which has no public address to link to (no host "
                    + "name, or only localhost and no base URL); no link sent", site.map(Site::id).orElse("?"));
        }
        return "redirect:/forgot-password?sent";
    }

    @GetMapping("/reset")
    public String resetForm(@RequestParam(required = false) String token, Model model) {
        chrome(model, "/reset");
        String siteId = siteResolver.current().map(Site::id).orElse(null);
        recovery.validateTenant(token, siteId).ifPresentOrElse(
                grant -> {
                    model.addAttribute("valid", true);
                    model.addAttribute("token", token);
                    model.addAttribute("username", grant.username());
                },
                () -> model.addAttribute("valid", false));
        return "operator/recovery-reset";
    }

    @PostMapping("/reset")
    public String doReset(@RequestParam(required = false) String token,
                          @RequestParam(required = false) String password,
                          @RequestParam(required = false) String confirm, Model model) {
        String error = passwordError(password, confirm);
        if (error != null) {
            chrome(model, "/reset");
            model.addAttribute("valid", true);
            model.addAttribute("token", token);
            model.addAttribute("error", error);
            return "operator/recovery-reset";
        }
        String siteId = siteResolver.current().map(Site::id).orElse(null);
        if (recovery.resetTenant(token, siteId, password) == PasswordRecoveryService.ResetOutcome.OK) {
            return "redirect:/login?reset";
        }
        chrome(model, "/reset");
        model.addAttribute("valid", false);
        return "operator/recovery-reset";
    }

    /** The bound site's display name, or the platform brand when no site is bound (an unmatched host). */
    private String brand() {
        return siteResolver.current().map(Site::name).orElse("Runway");
    }

    /** The realm-specific chrome the shared recovery templates bind (green accent, tenant URLs). */
    private void chrome(Model model, String postUrl) {
        model.addAttribute("brand", brand());
        model.addAttribute("accentBorder", "border-success");
        model.addAttribute("accentBtn", "btn-success");
        model.addAttribute("loginUrl", "/login");
        model.addAttribute("postUrl", postUrl);
    }

    private static String passwordError(String password, String confirm) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return "weak";
        }
        if (!password.equals(confirm)) {
            return "mismatch";
        }
        return null;
    }
}
