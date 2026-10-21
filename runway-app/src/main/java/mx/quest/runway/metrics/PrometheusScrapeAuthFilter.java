package mx.quest.runway.metrics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import mx.quest.runway.web.RequestPaths;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a monitoring system scrape {@code /actuator/prometheus} with a bearer token instead of an operator login.
 * When {@code runway.metrics.scrape-token} is configured and the request's {@code Authorization: Bearer <token>}
 * matches it (constant-time), this filter establishes an {@code Authentication} carrying the
 * {@code ROLE_METRICS_SCRAPE} authority; the app security chain permits that authority on the Prometheus
 * endpoint (and nothing else). With no token configured the filter is inert — the endpoint stays operator-only.
 *
 * <p>Scoped tightly: it only ever acts on the exact {@code /actuator/prometheus} path (the decoded path the
 * chain's matcher sees, {@link RequestPaths#normalized}, so the grant and the rule that honours it name one
 * request), only reads (never mutates) other requests, and never <em>rejects</em> — a bad/missing token simply leaves the request
 * unauthenticated for the normal chain to handle (redirect to login / 401), so it cannot weaken the endpoint.
 *
 * <p><b>Renaming this class is a breaking monitoring change.</b> Spring Security names one meter per chain
 * filter after the filter class, so this name is published as
 * {@code spring_security_filterchains_PrometheusScrapeAuthFilter_{before,after}_total}. A rename silently renames those
 * production metrics — dashboards lose their series and alert rules stop matching, with no compiler error and
 * nothing in review to catch it. {@code SecurityFilterMetricNamesTest} pins the name; if you rename this,
 * update that literal and tell whoever owns the monitoring stack.
 */
public class PrometheusScrapeAuthFilter extends OncePerRequestFilter {

    /** The role the security chain grants a valid token holder — reserved to this endpoint. */
    public static final String SCRAPE_ROLE = "METRICS_SCRAPE";

    private static final String PATH = "/actuator/prometheus";
    private static final String BEARER = "Bearer ";

    private final MetricsProperties properties;

    public PrometheusScrapeAuthFilter(MetricsProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (properties.hasScrapeToken()
                && PATH.equals(RequestPaths.normalized(request))
                && SecurityContextHolder.getContext().getAuthentication() == null
                && tokenMatches(request.getHeader("Authorization"))) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new ScrapeAuthentication());
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private boolean tokenMatches(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER)) {
            return false;
        }
        String presented = authorizationHeader.substring(BEARER.length()).trim();
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                properties.scrapeToken().getBytes(StandardCharsets.UTF_8));
    }

    /** A minimal, always-authenticated token whose single authority is {@code ROLE_METRICS_SCRAPE}. */
    private static final class ScrapeAuthentication extends AbstractAuthenticationToken {

        ScrapeAuthentication() {
            super(List.of(new SimpleGrantedAuthority("ROLE_" + SCRAPE_ROLE)));
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getPrincipal() {
            return "prometheus-scrape";
        }
    }
}
