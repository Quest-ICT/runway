package mx.quest.runway.bandwidth;

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
 * Short-circuits a site's <b>public</b> traffic with {@code 503 Service Unavailable} once it has exceeded its
 * configured rolling-30-day outbound bandwidth quota (see {@link BandwidthProperties.Quota}). Runs in the
 * tenant band after site binding; the over-quota decision is an O(1) cached-set lookup, so this adds nothing
 * measurable to the request path.
 *
 * <p>The application realm and the tenant <b>console</b> realm are exempt (same as maintenance mode): a
 * quota only throttles the abusive <em>public</em> load, never the operator or the site manager reaching the
 * console. When no quota is configured the cached set is empty and this filter is a straight pass-through.
 */
public class BandwidthLimitFilter extends OncePerRequestFilter {

    private final SiteResolver siteResolver;
    private final BandwidthService bandwidth;

    public BandwidthLimitFilter(SiteResolver siteResolver, BandwidthService bandwidth) {
        this.siteResolver = siteResolver;
        this.bandwidth = bandwidth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Site site = siteResolver.current().orElse(null);
        if (site != null && bandwidth.isOverQuota(site.id()) && !isExempt(RequestPaths.normalized(request))) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "3600");
            response.setContentType("text/plain;charset=UTF-8");
            response.getOutputStream().write(
                    "This site has reached its bandwidth allowance. Please try again later."
                            .getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Exempt: the application realm and the tenant console (sign-in and recovery included), so nobody is locked out
     * of lifting the block. Asked of the decoded path ({@link RequestPaths#normalized}), like every exemption.
     */
    private static boolean isExempt(String path) {
        return AppRealmPaths.isAppRealm(path) || TenantConsolePaths.isConsole(path);
    }
}
