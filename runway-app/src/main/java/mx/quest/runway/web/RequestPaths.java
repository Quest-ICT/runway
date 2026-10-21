package mx.quest.runway.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.web.util.UriUtils;

/**
 * The one place a filter, interceptor or listener gets the path it classifies a request by.
 *
 * <h2>Why not {@code getRequestURI()}</h2>
 * {@link HttpServletRequest#getRequestURI()} is the path <b>as the client spelled it</b>: still percent-encoded,
 * with any {@code ;param} and any {@code .}/{@code ..} segment left in. Spring MVC and Spring Security route on the
 * <b>decoded</b> path. The two differ whenever a client encodes a character it did not need to: {@code /%5F%5Fapp}
 * and {@code /app/%6Cogin} reach the operator console's handlers exactly as {@code /__app} and {@code /app/login}
 * do, but a check on the raw URI does not see a console path at all. Measured on this stack (Jetty 12, Spring
 * Security's default firewall), against a real server:
 * <ul>
 *   <li>Jetty refuses with {@code 400} before any filter runs: an encoded {@code /} ({@code %2F}), an encoded
 *       {@code .} ({@code %2E}), an encoded {@code %} ({@code %25}, so no double encoding), {@code //}, a backslash,
 *       {@code %00}.</li>
 *   <li>Reaches every filter, and is routed decoded: any other percent-encoded character ({@code %5F}, {@code %5f},
 *       {@code %6C} …).</li>
 *   <li>Reaches every filter outer of Spring Security (order {@code < 100}), which then refuses it with
 *       {@code 400}: a {@code ;param} ({@code ;jsessionid=…}, or {@code %3B}), a {@code /./} or {@code /../}
 *       segment. A filter outer of security can still act on these first (answer {@code 503}, meter bytes).</li>
 * </ul>
 *
 * <h2>The two spellings</h2>
 * {@link #normalized} is the path the dispatcher routes on: the container's decoded {@code servletPath} plus
 * {@code pathInfo} — percent-decoded, {@code ;params} removed, dot segments resolved, relative to the context path.
 * Classify by it wherever the answer must agree with the handler that will serve the request: an <b>exemption</b>
 * (the app realm passes a suspended site's {@code 503}), metering, the realm a page renders in.
 *
 * <p>{@link #raw} is the URI as sent, minus the context path. {@link #eitherMatches} asks a question of both, for
 * a check where matching <b>more</b> is the safe side: a protection that applies to a path (the console-host
 * refusal, a quarantine hold, the login lockout, {@code no-store}) or an answer that withholds something (no bound
 * site for the app realm). Never use it for an exemption: {@code /__app/../page} is an app-realm path raw but a
 * public page decoded, so "either" would exempt the page.
 */
public final class RequestPaths {

    private RequestPaths() {
    }

    /**
     * The decoded path the dispatcher routes this request on, relative to the context path ({@code /} for the
     * root, never {@code null} for a real request). A request whose container left both {@code servletPath} and
     * {@code pathInfo} empty (a hand-built test request) falls back to {@link #decode} of {@link #raw}.
     */
    public static String normalized(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        String path = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        return path.isEmpty() ? decode(raw(request)) : path;
    }

    /** The request URI exactly as the client sent it (still encoded), without the context path; may be null. */
    public static String raw(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (uri != null && context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }

    /**
     * Whether {@code test} holds for the {@linkplain #normalized decoded} path <b>or</b> the {@linkplain #raw raw}
     * one. For protective checks only (see the class Javadoc); an exemption must use {@link #normalized}.
     */
    public static boolean eitherMatches(HttpServletRequest request, Predicate<String> test) {
        return test.test(normalized(request)) || test.test(raw(request));
    }

    /**
     * Decodes a raw request path the way the container does for {@code servletPath}: drops a {@code ;param} from
     * each segment, percent-decodes each segment as UTF-8 (a malformed escape is kept as written), and resolves
     * {@code .} and {@code ..} segments (never above the root). For a path that did not come from a live dispatch,
     * such as the original URI an error page receives. {@code null} stays {@code null}.
     */
    public static String decode(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String[] segments = rawPath.split("/", -1);
        List<String> out = new ArrayList<>(segments.length);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            int semicolon = segment.indexOf(';');
            if (semicolon >= 0) {
                segment = segment.substring(0, semicolon);
            }
            segment = percentDecode(segment);
            boolean last = i == segments.length - 1;
            if (i > 0 && (segment.equals(".") || segment.equals(".."))) {
                if (segment.equals("..") && out.size() > 1) {
                    out.remove(out.size() - 1);
                }
                if (last) {
                    out.add("");
                }
                continue;
            }
            out.add(segment);
        }
        return String.join("/", out);
    }

    private static String percentDecode(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        try {
            return UriUtils.decode(segment, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return segment;
        }
    }
}
