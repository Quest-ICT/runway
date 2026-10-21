package mx.quest.runway.reload;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.web.FilterOrder;
import mx.quest.runway.web.RequestPaths;
import mx.quest.runway.web.SiteReloadPaths;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Serves the site-reload endpoint: {@code POST /__reload/site/<id>} and {@code POST /__reload/sites}.
 *
 * <h2>Shape chosen for an operator with no console</h2>
 * A bearer token in the external {@code application.yml} rather than a CLI verb or a signal, because an
 * operator's privileged access may be <b>temporary and repeatedly re-locked</b>: a steady state of a few scoped
 * wrappers (control, upgrade, path-confined file edit). Anything requiring a new wrapper verb would need root
 * re-opened every time the wrapper changed, i.e. it would not work in the state such an operator is meant to be in.
 * A token they can write with the file tools they already hold needs nothing from their privilege model — one
 * restart to arm it, then reloads are restart-free forever.
 *
 * <h2>Three gates, in this order</h2>
 * <ol>
 *   <li><b>Inert unless configured</b> — a blank {@code runway.reload.token} answers {@code 404}: the feature is
 *       absent, not merely locked, so an install that never enables it exposes nothing.</li>
 *   <li><b>Loopback only</b> — a non-loopback peer gets {@code 404}, never a hint that the route exists. The
 *       trusted-proxy filter runs outermost and rewrites the remote address for genuinely-forwarded traffic, so a
 *       request arriving through the edge presents its real client IP here and is refused; only something already
 *       on the box can reach this. That also means no firewall rule, no ACL, and no exposure decision.</li>
 *   <li><b>Constant-time token compare</b> — a wrong token is {@code 403}. Unlike the public health probe (where a
 *       bad token silently degrades so it can never be an oracle), this endpoint MUTATES, so a wrong token must
 *       fail loudly rather than appear to succeed. It is only reachable from loopback by then, so saying "your
 *       token is wrong" costs nothing and saves an operator a debugging session.</li>
 * </ol>
 *
 * <p>Runs at {@link FilterOrder#CORE_SITE_RELOAD}, outer of the tenant gates, so a suspended or maintenance-mode
 * catch-all site cannot 503 the very request that would fix its configuration. It always short-circuits.
 */
public class SiteReloadFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final String JSON = "application/json;charset=UTF-8";

    private final SiteReloadService reloads;
    private final ReloadProperties properties;

    public SiteReloadFilter(SiteReloadService reloads, ReloadProperties properties) {
        this.reloads = reloads;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = RequestPaths.normalized(request);
        return path == null || !path.startsWith(SiteReloadPaths.PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.active() || !isLoopback(request.getRemoteAddr())) {
            send(response, HttpServletResponse.SC_NOT_FOUND, "{\"error\":\"not found\"}");
            return;
        }
        if (!tokenMatches(request.getHeader("Authorization"))) {
            send(response, HttpServletResponse.SC_FORBIDDEN, "{\"error\":\"invalid token\"}");
            return;
        }
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            response.setHeader("Allow", "POST");
            send(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "{\"error\":\"use POST\"}");
            return;
        }

        String path = RequestPaths.normalized(request);
        if (SiteReloadPaths.ALL.equals(path)) {
            List<String> ids = reloads.reloadAll();
            StringBuilder json = new StringBuilder("{\"reloaded\":[");
            for (int i = 0; i < ids.size(); i++) {
                json.append(i == 0 ? "" : ",").append('"').append(ids.get(i)).append('"');
            }
            send(response, HttpServletResponse.SC_OK,
                    json.append("],\"count\":").append(ids.size()).append(",\"time\":\"").append(now()).append("\"}").toString());
            return;
        }
        String siteId = SiteReloadPaths.siteIdOf(path);
        // An unsafe id is refused before it reaches any store — the same rule the rest of the platform applies.
        if (siteId == null || !PathSafety.isSafeId(siteId)) {
            send(response, HttpServletResponse.SC_NOT_FOUND, "{\"error\":\"no such site\"}");
            return;
        }
        if (!reloads.reload(siteId)) {
            send(response, HttpServletResponse.SC_NOT_FOUND, "{\"error\":\"no such site\"}");
            return;
        }
        send(response, HttpServletResponse.SC_OK,
                "{\"reloaded\":\"" + siteId + "\",\"time\":\"" + now() + "\"}");
    }

    /** Whether {@code address} is a loopback peer; an unparseable address is treated as remote (fail closed). */
    private static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException | SecurityException notLoopback) {
            return false;
        }
    }

    private boolean tokenMatches(String header) {
        if (header == null || !header.startsWith(BEARER)) {
            return false;
        }
        return MessageDigest.isEqual(header.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8),
                properties.getToken().getBytes(StandardCharsets.UTF_8));
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static void send(HttpServletResponse response, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType(JSON);
        response.setContentLength(bytes.length);
        response.setHeader("Cache-Control", "no-store");
        response.getOutputStream().write(bytes);
    }
}
