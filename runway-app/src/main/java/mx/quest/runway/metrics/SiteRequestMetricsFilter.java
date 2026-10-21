package mx.quest.runway.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.web.RequestPaths;
import mx.quest.runway.web.UnmeteredPaths;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Records a per-<b>site</b> request timer so an operator can see request volume, latency and error rate broken
 * down by tenant — signal the generic {@code http.server.requests} timer (tagged by route, not tenant) does not
 * carry. It sits just inside {@code CORE_BANDWIDTH}, outer than the security chain and the handler, so it times
 * essentially the whole inner processing; like {@link mx.quest.runway.bandwidth.BandwidthFilter} it wraps the
 * site-context filter and so re-resolves the site from the host in its post phase.
 *
 * <p>The meter is {@code runway.http.server.requests} (a {@link Timer}, so it carries both a count and a latency
 * distribution) tagged {@code site} and {@code outcome} ({@code informational|success|redirection|client_error|
 * server_error}). Cardinality is bounded: sites × five outcomes. App-realm, health and metrics traffic
 * ({@code /__app}, {@code /app/…}, {@code /actuator}) is excluded — it is not a tenant's traffic. A request that
 * resolves to no site is not recorded (no unbounded {@code site="unknown"} bucket).
 */
public class SiteRequestMetricsFilter extends OncePerRequestFilter {

    private static final String METER = "runway.http.server.requests";

    private final SiteResolver siteResolver;
    private final MeterRegistry registry;

    public SiteRequestMetricsFilter(SiteResolver siteResolver, MeterRegistry registry) {
        this.siteResolver = siteResolver;
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isExcluded(RequestPaths.normalized(request))) {
            chain.doFilter(request, response);
            return;
        }
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            Site site = siteResolver.resolve(request.getServerName()).orElse(null);
            if (site != null) {
                Timer.builder(METER)
                        .description("Per-site HTTP request count and latency")
                        .tag("site", site.id())
                        .tag("outcome", outcome(response.getStatus()))
                        .register(registry)
                        .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
        }
    }

    /** The Spring-style outcome family for a status code (bounded tag values — never the raw code). */
    private static String outcome(int status) {
        return switch (status / 100) {
            case 1 -> "informational";
            case 2 -> "success";
            case 3 -> "redirection";
            case 4 -> "client_error";
            case 5 -> "server_error";
            default -> "unknown";
        };
    }

    /**
     * Platform traffic, never charged to a tenant — the one shared authority (see {@link UnmeteredPaths}), asked of the
     * decoded path ({@link RequestPaths#normalized}) so an encoded console path is not charged to the host's site.
     */
    private static boolean isExcluded(String path) {
        return UnmeteredPaths.isUnmetered(path);
    }
}
