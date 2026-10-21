package mx.quest.runway.site;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import mx.quest.runway.web.AppRealmPaths;
import mx.quest.runway.web.RequestPaths;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Short-circuits public requests to a suspended site with {@code 503 Service Unavailable}. Runs just
 * after {@link SiteContextFilter} (so the site is already bound). Requests with no site bound
 * (unconfigured host) pass straight through.
 *
 * <p>Application-realm and infrastructure paths are <b>exempt</b>: suspending a tenant must never lock
 * an operator out of the platform console or break the health probe, even when reached via the
 * suspended tenant's host (the application realm is path-based, not host-based).
 */
public class SuspendedSiteFilter extends OncePerRequestFilter {

    private final SiteResolver resolver;

    public SuspendedSiteFilter(SiteResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Site site = resolver.current().orElse(null);
        if (site != null && site.suspended() && !isExempt(RequestPaths.normalized(request))) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("text/plain;charset=UTF-8");
            response.getOutputStream().write("This site is temporarily unavailable.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * The application realm ({@link AppRealmPaths}: the console, its sign-in and recovery, {@code /actuator}) is
     * never suspended. Decided on the decoded path the dispatcher routes on ({@link RequestPaths#normalized}), never
     * the raw URI: an exemption must match what is actually served, so {@code /%5F%5Fapp} is exempt like
     * {@code /__app}, and {@code /__app/../page} is not.
     */
    private static boolean isExempt(String path) {
        return AppRealmPaths.isAppRealm(path);
    }
}
