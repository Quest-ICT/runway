package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.web.RequestPaths;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The {@code Cache-Control} policy of the self-hosted console assets ({@code /__assets/**}), chosen per request:
 *
 * <ul>
 *   <li><b>Versioned</b> ({@code ?v=<token>}, see {@link AssetVersion}) — {@value #VERSIONED}: the URL changes with
 *       every release, so a day without revalidation can never serve a stale file.</li>
 *   <li><b>Unversioned</b> — {@value #UNVERSIONED}: the browser may keep the file but must revalidate it before
 *       each use ({@code Last-Modified} makes that a cheap {@code 304} while nothing changed). Any reference that
 *       forgot its {@code ?v=} — including one in a plugin's own template — is therefore correct-but-slower after
 *       an upgrade, never stale for a day. (Before this policy every asset was cached for a day regardless, and the
 *       page editor kept running the previous release's script after an upgrade.)</li>
 *   <li><b>Fonts</b> (woff2, woff, ttf, otf, eot) — {@value #VERSIONED} even without {@code ?v=}: a font is reached
 *       through a <em>relative</em> {@code url(../webfonts/…)} inside a stylesheet, which can never carry the token,
 *       so forcing revalidation would make every console page re-ask for every font. This is the long-standing
 *       one-day policy, kept unchanged on purpose.</li>
 * </ul>
 *
 * <p>Runs as an MVC interceptor on the asset handler's mapping, <b>before</b> the handler: the
 * {@code ResourceHttpRequestHandler} is registered with no cache period, so it never overwrites the header set
 * here.
 *
 * <p><b>What counts as versioned:</b> a {@code v} made of digits only — the shape every {@link AssetVersion} token
 * has. Anything else counts as unversioned, notably {@code v=} (blank) and {@code v=null}, which is what a template
 * renders when its model lacks {@code assetVersion}: caching such a URL for a day would pin it to whatever file
 * was current, across every later release, because the URL never changes.
 */
public class AssetCachePolicy implements HandlerInterceptor {

    /** The policy for a versioned request (and for fonts). */
    public static final String VERSIONED = "max-age=86400";
    /** The policy for an unversioned request: store, but revalidate before every use. */
    public static final String UNVERSIONED = "no-cache";

    private static final Set<String> FONT_TYPES = Set.of("woff2", "woff", "ttf", "otf", "eot");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, cacheControlFor(request));
        return true;
    }

    /** The {@code Cache-Control} value for an asset request (see the class contract). */
    static String cacheControlFor(HttpServletRequest request) {
        String version = request.getParameter(AssetVersion.PARAM);
        if (isToken(version)) {
            return VERSIONED;
        }
        return isFont(RequestPaths.normalized(request)) ? VERSIONED : UNVERSIONED;
    }

    /** Whether {@code v} has the shape of an {@link AssetVersion} token: non-empty, ASCII digits only. */
    private static boolean isToken(String v) {
        if (v == null || v.isEmpty()) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isFont(String uri) {
        if (uri == null) {
            return false;
        }
        int dot = uri.lastIndexOf('.');
        return dot >= 0 && dot > uri.lastIndexOf('/')
                && FONT_TYPES.contains(uri.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
