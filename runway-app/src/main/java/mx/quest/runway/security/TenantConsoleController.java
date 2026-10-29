package mx.quest.runway.security;

import java.util.Objects;
import mx.quest.runway.console.AssetVersion;
import mx.quest.runway.console.OperatorProperties;
import mx.quest.runway.mail.PlatformMailer;
import mx.quest.runway.recovery.RecoveryLinks;
import mx.quest.runway.site.SiteResolver;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The tenant realm's {@code /login} form, served per-tenancy on the site's own host (the bound site scopes the
 * login). Renders the styled {@code operator/login-tenant} template — the tenant counterpart of the app realm's
 * {@code operator/login-app}, sharing the same self-hosted Tabler chrome (logo, card, CSRF, password toggle)
 * but with the tenant realm's lime accent and the bound site's name as the brand.
 *
 * <p>The {@code ?error} / {@code ?logout} query flags are read by the template (via {@code ${param.*}}); the
 * form POSTs back to {@code /login} (the tenant chain's login-processing URL). The site is already bound by the
 * {@code SiteContextFilter} (host → site) before this runs, so {@link SiteResolver#current()} names the tenancy
 * being signed into; it falls back to the platform brand when no site is bound.
 */
@Controller
public class TenantConsoleController {

    private final SiteResolver siteResolver;
    private final PlatformMailer mailer;
    private final OperatorProperties operator;
    private final AssetVersion assetVersion;
    private final RecoveryLinks links;

    public TenantConsoleController(SiteResolver siteResolver, PlatformMailer mailer, OperatorProperties operator,
                                   AssetVersion assetVersion, RecoveryLinks links) {
        this.links = Objects.requireNonNull(links, "links");
        this.siteResolver = Objects.requireNonNull(siteResolver, "siteResolver");
        this.mailer = Objects.requireNonNull(mailer, "mailer");
        this.operator = Objects.requireNonNull(operator, "operator");
        this.assetVersion = Objects.requireNonNull(assetVersion, "assetVersion");
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("siteName", siteResolver.current().map(site -> site.name()).orElse("Runway"));
        // "Forgot your password?" is shown only when the platform can actually email a reset link: mail is configured
        // and the site has a host name for the link to point at.
        model.addAttribute("recoveryEnabled", mailer.isConfigured()
                && siteResolver.current().flatMap(links::siteBase).isPresent());
        // Pre-auth language switcher (this controller is outside the console-package OperatorModelAdvice that
        // supplies these on other operator pages): the offered languages, the one in force, and the return path.
        model.addAttribute("operatorLanguages", operator.getUiLanguages());
        model.addAttribute("uiLocale", LocaleContextHolder.getLocale().getLanguage());
        model.addAttribute("currentPath", "/login");
        // The ?v= token every /__assets reference on the page carries (the console advice supplies it elsewhere).
        model.addAttribute("assetVersion", assetVersion.value());
        return "operator/login-tenant";
    }
}
