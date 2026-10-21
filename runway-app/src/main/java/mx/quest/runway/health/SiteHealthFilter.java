package mx.quest.runway.health;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.web.FilterOrder;
import mx.quest.runway.web.RequestPaths;
import mx.quest.runway.web.SiteHealthPaths;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Serves the per-site health probe at {@value mx.quest.runway.web.SiteHealthPaths#PATH} on a tenant host — a
 * cheap, machine-readable answer to "is THIS site serving?", which neither the global {@code /actuator/health}
 * (JVM + storage, no tenant dimension) nor the per-site request metrics (no traffic ⇒ no data ⇒ indistinguishable
 * from down) can give.
 *
 * <h2>Why this is a filter at {@link FilterOrder#CORE_SITE_HEALTH} (55) and not an endpoint</h2>
 * It must REPORT suspension / maintenance / over-quota / a degraded platform floor — so it must run OUTER of the
 * filters that implement them (60–69), while still running INNER of {@code SiteContextFilter} (50), the only place
 * the site is bound from the Host. An {@code EndpointContributor} or an {@code @Controller} dispatches at or
 * inside the security chain (100), i.e. inner of all of them, and would therefore be 503'd in precisely the states
 * worth reporting. A health check that goes down with the thing it monitors is worse than none.
 *
 * <p>Consequently this filter <b>always short-circuits</b> on a path match and never calls
 * {@code chain.doFilter} — delegating inward would hand the request straight to the gates it exists to describe.
 *
 * <h2>Two response shapes</h2>
 * <b>Public</b> (no/!valid token): {@code {"status":…,"time":…}} and nothing else. <b>Detail</b>
 * ({@code Authorization: Bearer <runway.health.token>}): adds {@code state}, {@code site} and {@code checks}. The
 * cause is withheld publicly because {@code suspended} is an operator's business decision about a tenant, and
 * Runway should not publish a stable, scrapeable field asserting it — the same line the platform already draws
 * between public {@code /actuator/health} and role-gated {@code /actuator/prometheus}.
 *
 * <p>Token handling follows {@code PrometheusScrapeAuthFilter} exactly: constant-time compare, inert when the
 * configured token is blank, and <b>additive only</b> — a wrong or absent token silently yields the public shape,
 * never a {@code 401}. So the probe can neither become a credential oracle nor turn a token typo into an outage.
 * <b>HTTP status is identical in both shapes</b>, so a monitor's alerting never depends on whether its token was
 * accepted.
 *
 * <h2>Never a false green</h2>
 * The site is resolved with {@link SiteResolver#resolveExplicit} — NOT {@code resolve}, which falls back to the
 * platform catch-all default site. Under the fallback a probe aimed at a typo'd or de-configured hostname would be
 * answered by a different tenant and report it healthy, which would defeat the endpoint's whole purpose. An
 * unmatched host gets {@code 404 {"status":"unknown"}} — the same thing an install without this feature returns.
 *
 * <p>Also by construction: no session is ever created, no template is rendered, no tenant-authored text reaches
 * the body (so there is no escaping surface), and the response is always {@code no-store} — nothing else stamps it
 * here, since the console cache rule covers only {@code /__app**}/{@code /__admin**}.
 */
public class SiteHealthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final String JSON = "application/json;charset=UTF-8";

    private final SiteResolver siteResolver;
    private final SiteHealthService health;
    private final HealthProperties properties;

    public SiteHealthFilter(SiteResolver siteResolver, SiteHealthService health, HealthProperties properties) {
        this.siteResolver = siteResolver;
        this.health = health;
        this.properties = properties;
    }

    /**
     * Only ever handles the exact health path (decoded, {@link RequestPaths#normalized}: the probe answers
     * {@code /%5F%5Fhealth} as Spring would route it); every other request is untouched by this filter.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SiteHealthPaths.PATH.equals(RequestPaths.normalized(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            response.setHeader("Allow", "GET, HEAD");
            write(request, response, "{\"status\":\"unknown\"}");
            return;
        }
        Optional<Site> site = siteResolver.resolveExplicit(request.getServerName());
        if (site.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            write(request, response, "{\"status\":\"unknown\",\"time\":\"" + now() + "\"}");
            return;
        }
        SiteHealth verdict = health.evaluate(site.get());
        if (verdict.down()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            if (verdict.retryable()) {
                response.setHeader("Retry-After", "3600");
            }
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
        }
        write(request, response, body(verdict, site.get(), detailAuthorized(request)));
    }

    /** The JSON body: always {@code status}+{@code time}; the cause, site id and checks only when authorized. */
    private String body(SiteHealth verdict, Site site, boolean detail) {
        StringBuilder json = new StringBuilder(160);
        json.append("{\"status\":\"").append(token(verdict.status().name())).append('"');
        if (detail) {
            json.append(",\"state\":\"").append(token(verdict.state().name())).append('"')
                    // The site id is PathSafety-validated ([A-Za-z0-9-]), so it needs no JSON escaping.
                    .append(",\"site\":\"").append(site.id()).append('"')
                    .append(",\"checks\":{\"content\":\"").append(token(verdict.content().name()))
                    .append("\",\"admin\":\"").append(token(verdict.admin().name())).append("\"}");
        }
        return json.append(",\"time\":\"").append(now()).append("\"}").toString();
    }

    /** Enum constant to wire token: lower-case, underscores kept ({@code OVER_QUOTA} -> {@code over_quota}). */
    private static String token(String constant) {
        return constant.toLowerCase(Locale.ROOT);
    }

    private static String now() {
        return Instant.now().toString();
    }

    /**
     * Whether this request may see the detail shape. Inert (always false) when no token is configured; a wrong or
     * missing header is silently "no", never an error.
     */
    private boolean detailAuthorized(HttpServletRequest request) {
        if (!properties.detailAvailable()) {
            return false;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return false;
        }
        return MessageDigest.isEqual(header.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8),
                properties.getToken().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes the body, honouring HEAD: no bytes, but the {@code Content-Length} the GET would have carried. The
     * standing rule is that a body-less HEAD must still report the real length — here
     * that is trivially correct because the filter computes the body either way and never buffers a response.
     */
    private void write(HttpServletRequest request, HttpServletResponse response, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        response.setContentType(JSON);
        response.setContentLength(bytes.length);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        if (!"HEAD".equalsIgnoreCase(request.getMethod())) {
            response.getOutputStream().write(bytes);
        }
    }
}
