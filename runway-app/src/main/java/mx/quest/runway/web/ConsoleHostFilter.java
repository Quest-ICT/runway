package mx.quest.runway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps the operator console off the host names of tenant sites.
 *
 * <h2>Why</h2>
 * The application realm is matched by path ({@link AppRealmPaths}), so before this filter {@code /__app} and
 * {@code /app/login} answered on every host. A site manager controls the scripts on their own site's pages (a
 * same-origin {@code .js} file is enough under {@code 'strict-dynamic'}). If an operator signed in to the console
 * through that site's host name, the site's scripts shared the origin with the operator's session and could act as
 * the operator: read the CSRF token from a console page and post forms with the operator's cookie. No header on
 * the console's own responses can prevent that, because the attacking code runs in the site's page. Keeping the
 * console on a different origin is what works.
 *
 * <h2>The rule</h2>
 * For every operator-console path ({@link AppRealmPaths#isOperatorConsole}: {@code /__app/**}, {@code /app/login},
 * {@code /app/logout}, {@code /app/forgot-password}, {@code /app/reset}):
 * <ul>
 *   <li>{@code runway.console.hosts} set: only those host names (case-insensitive, port ignored).</li>
 *   <li>Unset (the default): any host name <b>except</b> one bound to a site that is not the platform's default
 *       site. The default site's host names and host names bound to no site (an IP address, {@code localhost}, any
 *       unbound name) still work, so the console can always be reached by the server's address or an SSH tunnel.
 *       With no default site, only unbound host names.</li>
 * </ul>
 * A refused request gets the same 404 an unknown page on that host gets: the filter marks the request with
 * {@link #REFUSED_ATTRIBUTE} and sends a 404, and {@link RunwayErrorController} renders it as the site's own
 * "not found" page rather than the console's error page, so nothing hints that a console exists.
 *
 * <p>Not affected: {@code /__admin} (a site's own console belongs on its own host), public pages,
 * {@code /actuator/**}, and the first-run wizard (the filter is inert until the platform is set up).
 *
 * <h2>Placement</h2>
 * {@link FilterOrder#CORE_CONSOLE_HOST}: before the security chains, so the sign-in page, recovery and every
 * console route are refused before any session, CSRF token or login attempt is touched. It looks the host up
 * itself ({@link SiteResolver#resolveExplicit}), with no fallback to the default site, so it does not depend on the
 * binding made by {@code SiteContextFilter}.
 *
 * <h2>Logging</h2>
 * One INFO line per refused host name, the first time it is refused: the host, the site it serves and how to allow
 * it. Never a line per request. The set of logged names is capped ({@link #MAX_LOGGED_HOSTS}) because with
 * {@code runway.console.hosts} set any made-up {@code Host} header is refused.
 */
public class ConsoleHostFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ConsoleHostFilter.class);

    /** Request attribute set on a refused request, read by {@link RunwayErrorController} on the 404's error page. */
    public static final String REFUSED_ATTRIBUTE = "runway.consoleHostRefused";

    /** How many distinct refused host names are logged; later ones are refused silently. */
    static final int MAX_LOGGED_HOSTS = 256;

    private final SiteResolver resolver;
    private final PlatformSettingsStore platform;
    private final Set<String> allowed;
    private final boolean active;
    private final Set<String> logged = ConcurrentHashMap.newKeySet();

    /**
     * @param resolver the host → site index (explicit lookups only)
     * @param platform the platform settings, for the default site
     * @param hosts    {@code runway.console.hosts}; empty means "the default rule"
     * @param active   {@code false} in setup (wizard) mode, where the filter lets everything through
     */
    public ConsoleHostFilter(SiteResolver resolver, PlatformSettingsStore platform, Collection<String> hosts,
                             boolean active) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.allowed = hosts == null ? Set.of() : hosts.stream()
                .filter(Objects::nonNull)
                .map(ConsoleHostFilter::normalize)
                .filter(h -> !h.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.active = active;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !active || !isConsolePath(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The site lookup takes the host exactly as SiteContextFilter passes it, so both agree on which site a host
        // serves; the allow-list compares the normalized name.
        String serverName = request.getServerName();
        String host = normalize(serverName);
        Optional<Site> site = resolver.resolveExplicit(serverName);
        if (allows(host, site)) {
            chain.doFilter(request, response);
            return;
        }
        logOnce(host, site);
        request.setAttribute(REFUSED_ATTRIBUTE, Boolean.TRUE);
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    /** The rule from the class Javadoc. {@code site} is the site bound to {@code host}, with no fallback. */
    private boolean allows(String host, Optional<Site> site) {
        if (!allowed.isEmpty()) {
            return allowed.contains(host);
        }
        if (site.isEmpty()) {
            return true;
        }
        return platform.get().defaultSite().map(site.get().id()::equals).orElse(false);
    }

    /**
     * The console path check on both the raw request URI and the container's decoded path, so an encoded spelling
     * such as {@code /%5F%5Fapp} cannot reach the console's handlers past this filter ({@link RequestPaths}).
     */
    private static boolean isConsolePath(HttpServletRequest request) {
        // Also the canonical form of the raw URI (";params" dropped, "." and ".." resolved): Jetty leaves
        // "/x;/../__app" uncanonicalized in the servlet path, the firewall then answers 400, and the error page reads
        // the canonical "/__app" and would show the console's error page on a tenant host.
        return RequestPaths.eitherMatches(request, AppRealmPaths::isOperatorConsole)
                || AppRealmPaths.isOperatorConsole(RequestPaths.decode(RequestPaths.raw(request)));
    }

    private void logOnce(String host, Optional<Site> site) {
        if (logged.size() >= MAX_LOGGED_HOSTS || !logged.add(host)) {
            return;
        }
        String shown = printable(host);
        String serves = site.map(s -> "it serves the site '" + printable(s.id()) + "'").orElse("it serves no site");
        if (!allowed.isEmpty()) {
            log.info("Operator console refused on host '{}' ({}): it is not listed in runway.console.hosts. To allow "
                    + "it, add it to runway.console.hosts in external/application.yml and restart. Logged once per "
                    + "host.", shown, serves);
        } else {
            log.info("Operator console refused on host '{}' ({}, not the default site): the console does not answer "
                    + "on a host that serves another site. Sign in on the default site's host, the server's address "
                    + "or localhost, or list the hosts to use in runway.console.hosts in external/application.yml and "
                    + "restart. Logged once per host.", shown, serves);
        }
    }

    /**
     * A host name as {@link SiteResolver} compares it: lower case, trimmed, without the port. An IPv6 literal keeps
     * its address and loses the brackets, so {@code [::1]}, {@code [::1]:8443} and {@code ::1} are one host.
     */
    static String normalize(String host) {
        if (host == null) {
            return "";
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        // A list entry written as a URL ("https://ops.example.com/") is read as its host name, not as "https".
        int scheme = h.indexOf("://");
        if (scheme >= 0) {
            h = h.substring(scheme + 3);
        }
        int slash = h.indexOf('/');
        if (slash >= 0) {
            h = h.substring(0, slash);
        }
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            return close > 0 ? h.substring(1, close) : h.substring(1);
        }
        int colon = h.indexOf(':');
        if (colon >= 0 && h.indexOf(':', colon + 1) < 0) {
            h = h.substring(0, colon);
        }
        // "example.com." is the same name as "example.com" (the root label).
        while (h.endsWith(".") && h.length() > 1) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }

    /** The host as it may appear in a log line: printable ASCII only, at most 253 characters. */
    private static String printable(String text) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), 253));
        for (int i = 0; i < text.length() && out.length() < 253; i++) {
            char c = text.charAt(i);
            out.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        return out.toString();
    }
}
