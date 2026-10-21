package mx.quest.runway.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteContact;
import mx.quest.runway.site.SiteContactService;
import mx.quest.runway.site.SiteParamsService;
import mx.quest.runway.site.SiteViews;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.storage.StorageLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Renders an error response (404, 500, …) as the tenant's own {@code _<code>} page composed through its
 * master — and, crucially, <b>fails loudly through a master-independent path</b> when that is not
 * possible. This is the {@code No fallback chrome} guarantee: a site is self-contained,
 * so the platform never silently substitutes another site's or an app-level chrome; if a site's master
 * is missing or broken, the error page is served as a minimal, master-independent static document and
 * the failure is logged, rather than disappearing or rendering with the wrong chrome.
 *
 * <p>Resolution per request: if the site provides {@code templates/_<code>.html}, render it through the
 * master (a normal page-as-entry render with a minimal, non-indexable model); on any render failure —
 * the master itself being broken is the important case — log and fall back. With no site, no such
 * template, or a render failure, serve the kernel's static page for that status. Error responses are
 * always {@code no-store} (never cached) and never carry SEO head injection.
 */
@Component
public class ErrorPages {

    private static final Logger log = LoggerFactory.getLogger(ErrorPages.class);

    private static final String TEMPLATES_DIR = "templates";

    private final StorageLayout layout;
    private final PageRenderer renderer;
    private final SiteParamsService siteParams;
    private final SiteContactService contact;

    /** The form without site parameters or contact details (bare tests; both are then empty). */
    public ErrorPages(StorageLayout layout, PageRenderer renderer) {
        this(layout, renderer, null, null);
    }

    /**
     * @param siteParams the site's template parameters, or {@code null} for none
     * @param contact    the site's contact details, or {@code null} for none
     */
    @Autowired
    public ErrorPages(StorageLayout layout, PageRenderer renderer, SiteParamsService siteParams,
                      SiteContactService contact) {
        this.layout = layout;
        this.renderer = renderer;
        this.siteParams = siteParams;
        this.contact = contact;
    }

    /**
     * Renders the error response for {@code status}, preferring the site's {@code _<code>} page and
     * falling back to the kernel static page.
     *
     * @param site   the current site, or {@code null} when no tenant matched (then always static)
     * @param locale the locale to render the error page in (never {@code null})
     * @param status the HTTP status (its code names the template, e.g. {@code _404})
     */
    public ResponseEntity<String> render(Site site, Locale locale, HttpStatus status) {
        if (site != null) {
            String templateLeaf = "_" + status.value();
            Path file = layout.siteDir(site.id()).resolve(TEMPLATES_DIR).resolve(templateLeaf + ".html");
            if (Files.isRegularFile(file)) {
                try {
                    String name = SiteTemplateResolver.PREFIX + site.id() + "/" + templateLeaf;
                    String html = renderer.render(site.id(), name, locale, model(site, locale, status, siteParams, contact));
                    return body(status, html);
                } catch (RuntimeException brokenMaster) {
                    // The master (or the error template) is broken — fail loudly, master-independently.
                    log.error("error page {} for site '{}' failed to render; serving the static fallback",
                            templateLeaf, site.id(), brokenMaster);
                }
            }
        }
        return body(status, staticHtml(status));
    }

    /**
     * A minimal, non-indexable render model carrying the keys a master's chrome may bind against. The site's
     * {@code params} and {@code contact} are included because a master commonly shows them (a phone number in the
     * footer): without them such a master failed on every error page and the site always got the plain fallback.
     */
    private static Map<String, Object> model(Site site, Locale locale, HttpStatus status,
                                             SiteParamsService siteParams, SiteContactService contact) {
        SiteView view = SiteViews.toView(site);
        String reason = status.getReasonPhrase();
        Map<String, Object> model = new HashMap<>();
        model.put("site", view);
        model.put("page", new PageMeta("_" + status.value(), reason, "", List.of(locale), locale, true, true));
        model.put("locale", locale);
        model.put("master", SiteTemplateResolver.PREFIX + site.id() + "/_master");
        model.put("pageTitle", status.value() + " " + reason);
        model.put("pageDescription", "");
        model.put("canonical", "");
        model.put("xDefault", "");
        model.put("activeNav", "");
        model.put("year", String.valueOf(Year.now().getValue())); // string, not int — see RenderModelBuilder
        model.put("alternates", List.of());
        model.put("siteLocales", List.of());
        model.put("widgets", Map.of());
        model.put("urls", Map.of());
        model.put("noindex", true);
        model.put("params", siteParams == null ? Map.of() : siteParams.get(site.id()));
        model.put("contact", contact == null ? SiteContact.EMPTY : contact.get(site.id()));
        return model;
    }

    private static ResponseEntity<String> body(HttpStatus status, String html) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .body(html);
    }

    /** The kernel's master-independent static error document — used when no tenant page can render. */
    private static String staticHtml(HttpStatus status) {
        String safeReason = escape(status.getReasonPhrase());
        return "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"robots\" content=\"noindex\"><title>" + status.value() + " " + safeReason + "</title></head>"
                + "<body><h1>" + status.value() + " " + safeReason + "</h1></body></html>";
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
