package mx.quest.runway.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import mx.quest.runway.site.SiteContextFilter;
import mx.quest.runway.web.ClientIp;
import mx.quest.runway.web.LogSafe;
import mx.quest.runway.web.RequestPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The access log: one event per <b>public</b> request, written from its {@code post} phase, once every inner filter
 * and the handler have finished, so the status it records is the one the request was answered with. (The exception:
 * an exception escaping the chain, which only a plugin's own MVC controller can let through, is logged with the
 * status set before it, while the container answers {@code 500}.) It sits at {@code FilterOrder.CORE_ACCESS_LOG}, just inside the abuse floor and outside every
 * other filter, so it sees every request the floor lets through however it is answered: by a page, an endpoint
 * route, Spring Security (a login attempt, a CSRF {@code 403}), a tenant gate (a suspended or over-quota site's
 * {@code 503}), a refused slow body ({@code 408}) or an oversized form refused unread ({@code 413}). A filter that answers without calling the chain is invisible
 * to every filter inside it, which is why this one is not innermost: there, until 1.1.2, a password-guessing run
 * against a site's login left no line at all. Rejections by the floor itself are reported, throttled, by
 * {@code EdgeRejectionRecorder} instead.
 *
 * <p>It is a <b>public-traffic</b> log, not an audit trail: it skips the internal surfaces (anything under
 * {@code /__…} — the app/admin/preview/assets/CSP-report consoles — plus {@code /app/…} and {@code /actuator}),
 * whose access is an operator/audit concern handled elsewhere. The client IP comes from the gated
 * {@link ClientIp#of} (never a raw forwarded header).
 *
 * <p>It emits to two sinks: the operator's {@code runway.access} SLF4J logger (the whole-platform stream), and —
 * when a site answered (a public tenant request) — that site's own {@link SiteLogService#access per-site access
 * log}. The site is read from the {@link SiteContextFilter#SITE_ID_ATTRIBUTE request attribute} the site-context
 * filter leaves, not from {@code SiteResolver.current()}: that filter is inner of this one and has cleared its
 * thread binding by the time this {@code post} runs. A request refused before the site is resolved has no site and
 * goes to the platform log only.
 */
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("runway.access");

    private final SiteLogService siteLog;
    private final boolean logUserAgent;

    public AccessLogFilter(SiteLogService siteLog, boolean logUserAgent) {
        this.siteLog = siteLog;
        this.logUserAgent = logUserAgent;
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
            if (request.getAttribute(SiteContextFilter.SITE_ID_ATTRIBUTE) instanceof String siteId) {
                siteLog.access(siteId, ip, request.getMethod(), request.getRequestURI(), response.getStatus());
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
