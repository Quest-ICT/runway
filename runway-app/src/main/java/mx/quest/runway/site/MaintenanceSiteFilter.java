package mx.quest.runway.site;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import mx.quest.runway.web.AppRealmPaths;
import mx.quest.runway.web.RequestPaths;
import mx.quest.runway.web.TenantConsolePaths;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Short-circuits <b>public</b> requests to a site the tenant has put into <b>maintenance mode</b> with
 * {@code 503 Service Unavailable} and a small self-contained page (optionally carrying the tenant's message).
 * Runs after {@link SuspendedSiteFilter} — an operator-suspended site is already handled and stays suspended
 * regardless of the tenant's maintenance toggle.
 *
 * <p>Unlike suspension, maintenance is <b>tenant-controlled</b>, so the tenant admin realm is <b>exempt</b>:
 * {@code /__admin} (the console), {@code /login} / {@code /logout} (the tenant login) and {@code /__assets}
 * (login-page styling) always pass through, so the site manager can reach the console and toggle maintenance
 * back off. The application realm is exempt too (an operator is never locked out). Requests with no site bound
 * (unconfigured host) pass straight through.
 */
public class MaintenanceSiteFilter extends OncePerRequestFilter {

    private final SiteResolver resolver;

    public MaintenanceSiteFilter(SiteResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Site site = resolver.current().orElse(null);
        if (site != null && site.maintenance() && !site.suspended() && !isExempt(RequestPaths.normalized(request))) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "3600");
            response.setContentType("text/html;charset=UTF-8");
            response.getOutputStream().write(page(site).getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Exempt: the application realm (operator console never locked out) <b>and</b> the tenant console realm
     * (so the site manager can sign in, recover a password and turn maintenance back off). Asked of the decoded
     * path ({@link RequestPaths#normalized}), like every exemption.
     */
    private static boolean isExempt(String path) {
        return AppRealmPaths.isAppRealm(path) || TenantConsolePaths.isConsole(path);
    }

    /** A minimal self-contained maintenance page — no template engine (this filter runs before MVC). */
    private static String page(Site site) {
        String note = site.maintenanceMessage();
        String message = (note == null || note.isBlank())
                ? "This site is temporarily down for maintenance. Please check back shortly."
                : escape(note);
        return """
                <!DOCTYPE html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Down for maintenance</title>
                <style>
                  body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#f6f8fb;color:#334;
                       display:flex;min-height:100vh;margin:0;align-items:center;justify-content:center}
                  .card{max-width:32rem;padding:2.5rem;text-align:center}
                  h1{font-size:1.5rem;margin:0 0 .75rem}
                  p{line-height:1.6;color:#5a6a7a;margin:0}
                  .icon{font-size:3rem;margin-bottom:1rem}
                </style></head>
                <body><div class="card">
                  <div class="icon">&#128736;</div>
                  <h1>Down for maintenance</h1>
                  <p>%s</p>
                </div></body></html>
                """.formatted(message);
    }

    /** Minimal HTML escaping for the tenant-authored message shown to the public. */
    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
