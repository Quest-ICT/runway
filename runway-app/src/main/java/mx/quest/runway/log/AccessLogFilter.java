package mx.quest.runway.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.web.ClientIp;
import mx.quest.runway.web.LogSafe;
import mx.quest.runway.web.RequestPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The innermost filter ({@code FilterOrder.CORE_ACCESS_LOG}): it logs one event per <b>public</b> request from
 * its {@code post} phase, so it observes the <b>final</b> status set by every inner filter and the handler.
 * Being innermost is the whole point — an outer position would log a not-yet-decided status.
 *
 * <p>It is a <b>public-traffic</b> log, not an audit trail: it skips the internal surfaces (anything under
 * {@code /__…} — the app/admin/preview/assets/CSP-report consoles — plus {@code /app/…} and {@code /actuator}),
 * whose access is an operator/audit concern handled elsewhere. The client IP comes from the gated
 * {@link ClientIp#of} (never a raw forwarded header).
 *
 * <p>It emits to two sinks: the operator's {@code runway.access} SLF4J logger (the whole-platform stream), and —
 * when a site is bound (a public tenant request) — that site's own {@link SiteLogService#access per-site access
 * log}. This filter runs <em>inside</em> the site-context filter, so the tenant binding is still live in the
 * post phase.
 */
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("runway.access");

    private final SiteResolver siteResolver;
    private final SiteLogService siteLog;
    private final boolean logUserAgent;

    public AccessLogFilter(SiteResolver siteResolver, SiteLogService siteLog, boolean logUserAgent) {
        this.siteResolver = siteResolver;
        this.siteLog = siteLog;
        this.logUserAgent = logUserAgent;
    }

    /** Pre-{@code logUserAgent} form, kept so existing call sites (and tests) compile unchanged: agent off. */
    public AccessLogFilter(SiteResolver siteResolver, SiteLogService siteLog) {
        this(siteResolver, siteLog, false);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isInternal(RequestPaths.normalized(request))) {
            chain.doFilter(request, response);
            return;
        }
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - startNanos) / 1_000_000L;
            String ip = ClientIp.of(request);
            if (logUserAgent) {
                log.info("{} {} {} -> {} ({}ms) ua={}", ip, request.getMethod(),
                        request.getRequestURI(), response.getStatus(), millis,
                        LogSafe.header(request.getHeader("User-Agent")));
            } else {
                log.info("{} {} {} -> {} ({}ms)", ip, request.getMethod(),
                        request.getRequestURI(), response.getStatus(), millis);
            }
            Site site = siteResolver.current().orElse(null);
            if (site != null) {
                siteLog.access(site.id(), ip, request.getMethod(), request.getRequestURI(), response.getStatus());
            }
        }
    }

    /**
     * Internal surfaces (operator/admin/preview/assets/health/CSP-report) are not part of the public access log.
     * Asked of the decoded path ({@link RequestPaths#normalized}), so an operator who opens {@code /%5F%5Fapp} is not
     * written into the site's access log; the line itself still shows the URI as sent.
     */
    static boolean isInternal(String path) {
        return path != null
                && (path.startsWith("/__") || path.startsWith("/app/") || path.startsWith("/actuator"));
    }
}
