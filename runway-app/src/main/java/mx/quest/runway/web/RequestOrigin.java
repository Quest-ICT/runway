package mx.quest.runway.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;

/**
 * The request's <b>origin</b> — {@code scheme://host[:port]} — as the trusted-proxy gate ({@code
 * TrustedForwardedHeaderFilter}) left the request: a configured trusted proxy's forwarded scheme and host applied, the
 * connection's own values otherwise, never a raw {@code X-Forwarded-*} header. The one rule behind
 * {@code RouteRequest.origin()} and {@code HeadContext.origin()}, so a plugin's endpoint and its head contributor
 * agree on the host they build absolute URLs with.
 */
public final class RequestOrigin {

    private RequestOrigin() {
    }

    /**
     * {@code scheme://host[:port]} of {@code request}: lowercase, the scheme's default port omitted, an IPv6 literal
     * bracketed. Empty when the container cannot say (no scheme or host).
     */
    public static String of(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        if (scheme == null || scheme.isBlank() || host == null || host.isBlank()) {
            return "";
        }
        return origin(scheme, host, request.getServerPort());
    }

    /**
     * {@code scheme://host[:port]}: lowercase, the scheme's default port (or a port {@code <= 0}) omitted, an IPv6
     * literal bracketed.
     */
    public static String origin(String scheme, String host, int port) {
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        boolean defaultPort = port <= 0 || ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    /**
     * The origin a page's absolute URLs are built on: the site's base URL when it has one (configured, or derived
     * from its primary host — {@code SiteView.baseUrl()}; trailing {@code /} dropped; the site's own word wins over
     * whatever host the request came in on), else — a host-less site — the request's origin ({@link #of}). Empty when
     * neither is known.
     *
     * @param baseUrl the site view's base URL, or {@code null}/blank when it has none
     * @param request the current request, or {@code null} for a render outside one
     */
    public static String trusted(String baseUrl, HttpServletRequest request) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            String trimmed = baseUrl.trim();
            return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        }
        return request == null ? "" : of(request);
    }
}
