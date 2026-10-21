package mx.quest.runway.bandwidth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.web.RequestPaths;
import mx.quest.runway.web.UnmeteredPaths;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Counts each request's on-the-wire bytes and attributes them to the host's site. It sits at
 * {@code FilterOrder.CORE_BANDWIDTH} — outside compression (so it counts the real transmitted size) and
 * inside the forwarded-header gate (so the host is settled). Because it wraps the site-context filter, the
 * request binding is already cleared in its post phase, so it re-resolves the site from the host directly.
 *
 * <p>App-realm and health traffic ({@code /__app}, {@code /app/…}, {@code /actuator}) is excluded — it is not
 * a tenant's bandwidth. Everything that resolves to a site (public pages, the tenant console, plugin assets)
 * is metered as aggregate byte counts only.
 */
public class BandwidthFilter extends OncePerRequestFilter {

    private final SiteResolver siteResolver;
    private final BandwidthService bandwidth;

    public BandwidthFilter(SiteResolver siteResolver, BandwidthService bandwidth) {
        this.siteResolver = siteResolver;
        this.bandwidth = bandwidth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isExcluded(RequestPaths.normalized(request))) {
            chain.doFilter(request, response);
            return;
        }
        CountingResponseWrapper counting = new CountingResponseWrapper(response);
        try {
            chain.doFilter(request, counting);
        } finally {
            counting.flushBuffer();
            Site site = siteResolver.resolve(request.getServerName()).orElse(null);
            if (site != null) {
                long in = Math.max(0, request.getContentLengthLong());
                bandwidth.record(site.id(), in, counting.bytesWritten());
            }
        }
    }

    /**
     * Platform traffic, never charged to a tenant — the one shared authority (see {@link UnmeteredPaths}), asked of the
     * decoded path ({@link RequestPaths#normalized}) so an encoded console path is not charged to the host's site.
     */
    private static boolean isExcluded(String path) {
        return UnmeteredPaths.isUnmetered(path);
    }
}
