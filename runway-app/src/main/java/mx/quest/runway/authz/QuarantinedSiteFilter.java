package mx.quest.runway.authz;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.web.AppRealmPaths;
import mx.quest.runway.web.RequestPaths;
import mx.quest.runway.web.TenantConsolePaths;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Short-circuits the <b>tenant admin surface</b> ({@code /__admin/**}) of a site whose role store failed
 * startup validation (see {@link AuthzStoreIntegrity}) with {@code 503 Service Unavailable}. Runs just after
 * the site is bound. The quarantine is surgical on purpose: a corrupt {@code roles.yml} compromises only the
 * tenant's <em>authorization</em> surface, so the admin console must not be served (authorization cannot be
 * evaluated — fail-closed), but the site's <b>public, anonymous pages</b> (which never consult authorization)
 * keep serving. The application realm is likewise untouched, so an operator can still reach the platform
 * console to repair the tenancy.
 */
public class QuarantinedSiteFilter extends OncePerRequestFilter {

    private final SiteResolver resolver;
    private final AuthzStoreIntegrity integrity;

    public QuarantinedSiteFilter(SiteResolver resolver, AuthzStoreIntegrity integrity) {
        this.resolver = resolver;
        this.integrity = integrity;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Site site = resolver.current().orElse(null);
        if (site != null && integrity.isQuarantined(site.id())
                && RequestPaths.eitherMatches(request, QuarantinedSiteFilter::isTenantAdmin)) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("text/plain;charset=UTF-8");
            response.getOutputStream().write(
                    "This site's administration is temporarily unavailable.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Only the tenant admin surface is held; public pages (no authorization) and the app realm pass. A hold is a
     * protection, so it applies when either spelling of the path is the admin surface ({@link RequestPaths}): an
     * encoded {@code /%5F%5Fadmin} reaches the admin console's handlers just like {@code /__admin}.
     */
    private static boolean isTenantAdmin(String path) {
        return AppRealmPaths.inNamespace(path, TenantConsolePaths.ADMIN_PREFIX);
    }
}
