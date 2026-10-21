package mx.quest.runway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import mx.quest.runway.web.AppRealmPaths;
import mx.quest.runway.web.RequestPaths;
import mx.quest.runway.web.TenantConsolePaths;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy;

/**
 * The document-level security-header baseline applied identically to <b>every</b> realm filter chain
 * (application, tenant, wizard) via {@code http.headers(SecurityHeaders.baseline())}, so the three can never
 * drift out of sync. Spring Security already emits {@code X-Content-Type-Options: nosniff} by default (no
 * explicit call needed); this customizer sets the two headers we tune:
 *
 * <ul>
 *   <li><b>{@code X-Frame-Options: SAMEORIGIN}</b> — a page may be framed only by a same-origin document. The
 *       console's own editor live-preview relies on this, and the CSP {@code frame-ancestors 'self'} mirrors it.</li>
 *   <li><b>{@code Cross-Origin-Opener-Policy: same-origin}</b> — severs the {@code window.opener} link to a
 *       cross-origin opener, hardening against cross-origin leaks / tab-nabbing (a PageSpeed "Trust &amp; Safety"
 *       item). It does <b>not</b> affect iframes, and a document keeps its connection to <b>same-origin</b> popups
 *       it opens — so the page editor's same-origin pop-out preview window keeps working. {@code same-origin}
 *       (rather than {@code same-origin-allow-popups}) is safe because no realm opens a <i>cross-origin</i> popup
 *       it needs a handle to (reCAPTCHA challenges are iframes, not popups).</li>
 * </ul>
 *
 * <p>Add any future document-level security header here so all realms inherit it in lockstep, rather than
 * repeating it per chain.
 */
public final class SecurityHeaders {

    private SecurityHeaders() {
    }

    /**
     * The shared {@code .headers(...)} customizer: {@code X-Frame-Options: SAMEORIGIN} plus
     * {@code Cross-Origin-Opener-Policy: same-origin}. Applied identically by every realm chain.
     */
    public static Customizer<HeadersConfigurer<HttpSecurity>> baseline() {
        return headers -> headers
                .frameOptions(frame -> frame.sameOrigin())
                .crossOriginOpenerPolicy(coop -> coop.policy(CrossOriginOpenerPolicy.SAME_ORIGIN))
                .addHeaderWriter(CONSOLE_NO_STORE);
    }

    /**
     * Guarantees the authenticated console areas are never stored by a cache — closing a gap Spring Security's own
     * {@code CacheControlHeadersWriter} leaves open.
     *
     * <p><b>The gap.</b> That writer <b>bails out entirely</b> when the response already carries an
     * {@code Expires} or {@code Pragma} header. A request arriving with <b>no session cookie</b> makes the
     * container mint a {@code JSESSIONID} and stamp a legacy {@code Expires: Thu, 01 Jan 1970 …} — so on exactly
     * that response nothing writes {@code Cache-Control} at all. It is the cold, first-visit shape of every
     * console entry point ({@code /__app}, {@code /__app/users}, {@code /__admin}), each a 302 to the login that
     * also carries the new {@code Set-Cookie}. Reusing a session restores the header, which is what makes the
     * defect invisible to any test that reuses a client session. (Found on a live deployment of {@code 0.5.0-beta2}.)
     *
     * <p>Running <b>after</b> the default writer, this fills in {@code no-store} whenever the effective value does
     * not already forbid storing — so Spring Security's own stronger
     * {@code no-cache, no-store, max-age=0, must-revalidate} is never downgraded when it did run.
     *
     * <p><b>Severity, so nobody later re-derives it as scarier than it was:</b> the affected responses are
     * {@code 302}s with an empty body whose {@code Location} leaks only the login path, and {@code 302} is
     * <b>not cacheable-by-default</b> under RFC 7231 §6.1 (it is absent from the cacheable-by-default status
     * list), so absent explicit headers a conformant shared cache would not have stored them anyway. This is
     * belt-and-braces for non-conformant intermediaries and misconfigured corporate proxies — defense in depth,
     * never an exposure.
     *
     * <p><b>Scoped to console documents on purpose.</b> Only {@code /__app**} and {@code /__admin**} — never
     * {@code /__assets/**} (the self-hosted console chrome, long-cached behind a {@code ?v=} buster), never
     * {@code /static/**}, and never public tenant pages, whose caching is deliberate and load-bearing. Login /
     * recovery pages need no entry here: they render HTML and are already covered by the nonced-page {@code
     * no-store} invariant in {@code CspFilter}.
     */
    private static final HeaderWriter CONSOLE_NO_STORE = new HeaderWriter() {

        @Override
        public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
            if (!RequestPaths.eitherMatches(request, SecurityHeaders::isConsoleDocument)) {
                return;
            }
            String cacheControl = response.getHeader("Cache-Control");
            if (cacheControl == null || !cacheControl.toLowerCase(Locale.ROOT).contains("no-store")) {
                response.setHeader("Cache-Control", "no-store");
            }
        }

        @Override
        public String toString() {
            return "ConsoleNoStoreHeaderWriter";
        }
    };

    /**
     * Whether {@code path} is an authenticated <b>console</b> path — the operator app console or the tenant admin
     * console. Exact/prefix matched (so a public page at {@code /__adminx} is not swept in), and deliberately
     * narrower than {@code TenantConsolePaths.isConsole}, which also covers {@code /__assets/**} and the login
     * routes — neither of which should be forced {@code no-store}. A protection, so the writer asks it of both
     * spellings of the request path ({@link RequestPaths#eitherMatches}): {@code /%5F%5Fapp/users} is the same console
     * page as {@code /__app/users} and must not become storable by being spelled differently.
     */
    private static boolean isConsoleDocument(String path) {
        if (path == null) {
            return false;
        }
        return AppRealmPaths.inNamespace(path, AppRealmPaths.CONSOLE_PREFIX)
                || AppRealmPaths.inNamespace(path, TenantConsolePaths.ADMIN_PREFIX);
    }
}
