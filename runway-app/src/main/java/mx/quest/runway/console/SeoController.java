package mx.quest.runway.console;

import java.io.IOException;
import mx.quest.runway.seo.SeoConfigService;
import mx.quest.runway.seo.SiteSeoConfig;
import mx.quest.runway.site.SiteResolver;
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
 * The tenant console's SEO screen ({@code /__admin/seo}): a site manager edits the site's core social-card
 * hints, search-engine verification tokens, and a verbatim extra-{@code <head>} block. These are the lean
 * kernel fields — the platform already emits canonical URLs, {@code hreflang} alternates and {@code robots}
 * for free, and richer concerns (analytics, consent, GEO) are contributed by plugins. Gated by the grantable
 * {@code CONFIGURE_SITE} permission; the bound tenancy is taken from the host, never a parameter.
 */
@Controller
public class SeoController {

    private final SiteResolver siteResolver;
    private final SeoConfigService seo;
    private final MessageSource messages;

    public SeoController(SiteResolver siteResolver, SeoConfigService seo,
                         @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.siteResolver = siteResolver;
        this.seo = seo;
        this.messages = messages;
    }

    @GetMapping("/__admin/seo")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String form(Model model) {
        model.addAttribute("seo", seo.get(currentSiteId()));
        model.addAttribute("contentTemplate", "operator/admin/seo");
        model.addAttribute("pageTitleKey", "op.seo.title");
        return "operator/op-layout";
    }

    @PostMapping("/__admin/seo")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String save(@RequestParam(required = false) String ogImage,
                       @RequestParam(required = false) String ogType,
                       @RequestParam(required = false) String twitterCard,
                       @RequestParam(required = false) String twitterSite,
                       @RequestParam(required = false) String googleVerification,
                       @RequestParam(required = false) String bingVerification,
                       @RequestParam(required = false) String extraHead,
                       @RequestParam(defaultValue = "false") boolean documentsNoindex,
                       RedirectAttributes redirect) {
        try {
            seo.save(currentSiteId(), new SiteSeoConfig(blankToNull(ogImage), HeadFieldRules.ogType(ogType),
                    HeadFieldRules.twitterCard(twitterCard), blankToNull(twitterSite), blankToNull(googleVerification),
                    blankToNull(bingVerification), blankToNull(extraHead), documentsNoindex));
            redirect.addFlashAttribute("flash", message("op.seo.saved", "SEO settings saved."));
        } catch (SiteConfigInput.Invalid invalid) {
            redirect.addFlashAttribute("error", message(invalid.messageKey(), invalid.getMessage()));
        } catch (IOException e) {
            redirect.addFlashAttribute("error", message("op.seo.failed", "Could not save SEO settings."));
        }
        return "redirect:/__admin/seo";
    }

    private String currentSiteId() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"))
                .id();
    }

    private String message(String key, String fallback) {
        return messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }
}
