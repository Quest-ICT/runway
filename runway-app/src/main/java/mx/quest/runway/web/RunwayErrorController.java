package mx.quest.runway.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Replaces Spring Boot's Whitelabel error page for every uncontrolled condition — an exception that bubbles to
 * the container, a method-not-allowed, a 404 on a reserved path. Registering an {@link ErrorController} bean
 * also removes Boot's {@code BasicErrorController} (it is {@code @ConditionalOnMissingBean(ErrorController)}),
 * so the whitelabel page is gone platform-wide. Runway's two surfaces get two treatments:
 *
 * <ul>
 *   <li><b>Public tenant pages</b> — the bound site's own {@code _<code>} error page composed through its
 *       master, with a master-independent static fallback ({@link ErrorPages}); the site stays
 *       self-contained even on errors (the {@code No fallback chrome} guarantee).</li>
 *   <li><b>Console / reserved paths</b> ({@code /__app}, {@code /__admin}, {@code /__plugin}, {@code /app/*},
 *       {@code /actuator}) — a self-contained, dependency-free styled error page: no template render and no
 *       external asset, so the error path can never recurse into the very failure it is reporting.</li>
 * </ul>
 *
 * <p>One exception: a console path that {@link ConsoleHostFilter} refused on this host gets the public treatment,
 * the same 404 as an unknown page there, so a refused host gives no hint that a console exists.
 *
 * <p>Runs on the container's {@code /error} dispatch, after the request filters have unwound, so it
 * re-resolves the site from the host (the per-request {@link SiteResolver} binding is already cleared) and
 * reads the original status/path from the {@code jakarta.servlet.RequestDispatcher.ERROR_*} attributes.
 */
@Controller
public class RunwayErrorController implements ErrorController {

    private final SiteResolver siteResolver;
    private final ErrorPages errorPages;

    public RunwayErrorController(SiteResolver siteResolver, ErrorPages errorPages) {
        this.siteResolver = Objects.requireNonNull(siteResolver, "siteResolver");
        this.errorPages = Objects.requireNonNull(errorPages, "errorPages");
    }

    @RequestMapping("/error")
    public ResponseEntity<String> handle(HttpServletRequest request) {
        HttpStatus status = statusOf(request);
        // The original URI as sent, decoded the way the dispatcher routed it (RequestPaths.decode): an error on
        // /%5F%5Fapp/… happened on the console, and gets the console's error page like one on /__app/….
        String path = RequestPaths.decode(originalPath(request));

        // A console path refused on this host (ConsoleHostFilter) must look like any unknown page of the site that
        // answers here: the site's own 404, never the console error page with its "Back to the console" link.
        boolean refusedConsole = request.getAttribute(ConsoleHostFilter.REFUSED_ATTRIBUTE) != null;
        if (!refusedConsole && (AppRealmPaths.isAppRealm(path) || path.startsWith("/__"))) {
            String home = AppRealmPaths.isAppRealm(path) ? "/__app"
                    : path.startsWith("/__admin") ? "/__admin" : "/";
            return consoleError(status, home);
        }
        // A public tenant page: the site's own error page composed through its master, else the kernel's
        // master-independent static fallback. Re-resolve by host (the per-request binding is gone here).
        Optional<Site> site = siteResolver.resolve(request.getServerName());
        Locale locale = site.map(Site::defaultLocale).orElse(Locale.ENGLISH);
        return errorPages.render(site.orElse(null), locale, status);
    }

    /**
     * A self-contained, dependency-free error page for the operator console / reserved paths — inline CSS
     * only (no {@code /__assets}, no template), so it renders even when the console chrome or a template is
     * the cause of the error.
     */
    private static ResponseEntity<String> consoleError(HttpStatus status, String home) {
        String code = String.valueOf(status.value());
        String reason = escape(status.getReasonPhrase());
        String html = "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex\"><title>" + code + " " + reason + "</title>"
                + "<style>"
                + "*{box-sizing:border-box}html,body{height:100%;margin:0}"
                + "body{display:flex;align-items:center;justify-content:center;min-height:100%;padding:1.5rem;"
                + "font-family:system-ui,-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;"
                + "background:#f6f8fb;color:#1d273b}"
                + ".card{max-width:32rem;width:100%;background:#fff;border:1px solid #e6e9f0;border-radius:.75rem;"
                + "box-shadow:0 1px 3px rgba(29,39,59,.08);padding:2.5rem;text-align:center}"
                + ".code{font-size:3.5rem;font-weight:700;line-height:1;color:#4263eb;letter-spacing:-.02em}"
                + ".reason{margin:.5rem 0 0;font-size:1.25rem;font-weight:600}"
                + ".msg{margin:.75rem 0 1.5rem;color:#667082}"
                + ".back{display:inline-block;padding:.5rem 1rem;border-radius:.5rem;background:#4263eb;color:#fff;"
                + "text-decoration:none;font-weight:600}.back:hover{background:#364fc7}"
                + "</style></head><body><div class=\"card\">"
                + "<div class=\"code\">" + code + "</div>"
                + "<p class=\"reason\">" + reason + "</p>"
                + "<p class=\"msg\">The request could not be completed.</p>"
                + "<a class=\"back\" href=\"" + escape(home) + "\">Back to the console</a>"
                + "</div></body></html>";
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(html);
    }

    private static HttpStatus statusOf(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer i) {
            HttpStatus resolved = HttpStatus.resolve(i);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String originalPath(HttpServletRequest request) {
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return uri instanceof String s ? s : request.getRequestURI();
    }

    /** Escapes for HTML text AND attribute context (both quote styles), so it is safe in the {@code href} too. */
    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
