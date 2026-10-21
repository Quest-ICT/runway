package mx.quest.runway.endpoint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.plugin.ContributionGuard;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Collects every {@link EndpointContributor} bean — core and plugin alike — and indexes its declared
 * {@link Route}s for dispatch. A route is refused when it declares a realm other than {@code TENANT} or a path
 * with a reserved pattern character ({@code *}, <code>{</code>, <code>}</code>), when it would
 * <b>shadow a reserved kernel prefix</b> ({@code /__app}, {@code /__admin}, {@code /__plugin}, {@code /__assets},
 * {@code /__preview}, {@code /__csp-report}, {@code /actuator}, {@code /app/}, the auth surfaces, …), or when its
 * {@code method + path} is already claimed. For <b>kernel</b> code each of these, and a blank/duplicate
 * {@code key()}, fails startup — a loud failure beats a silent shadow. For a <b>plugin</b> the offending route (or
 * contributor) alone is refused (the plugin side of a kernel collision, the later plugin in load
 * order between two plugins), an ERROR names both sides, and the plugin is marked degraded; its other routes load.
 * Since SPI 0.5.0 a route is also refused when it is {@linkplain Route#anonymous() anonymous} but not {@code POST},
 * or when it claims one of the kernel's exact tenant routes ({@code /forgot-password}, {@code /reset}, … —
 * {@link mx.quest.runway.web.ReservedPaths#isExactKernelRoute}).
 *
 * <p>Routes are matched <b>exactly</b> on uppercased method + path (the SEO endpoints — {@code /robots.txt},
 * {@code /sitemap.xml}, … — are exact; richer patterns are a later refinement). Thread-safe and immutable.
 */
@Component
public class EndpointContributorRegistry {

    /** A matched route and the contributor that serves it. */
    public record Match(EndpointContributor contributor, Route route) {
    }

    private static final List<String> RESERVED_PREFIXES = List.of(
            "/__app", "/__admin", "/__plugin", "/__assets", "/__preview", "/__csp-report", "/actuator", "/app/",
            "/login", "/logout", "/error", // the realm auth surfaces and the error page — never plugin-claimable
            // Kernel routes served on a TENANT host, which a plugin route would otherwise shadow: the console
            // locale switcher (OperatorLocaleController) and the per-site health probe (SiteHealthFilter). The
            // health filter runs far outer of endpoint dispatch, so a plugin claiming the path could never
            // actually serve it — it would just fail confusingly; refusing it at startup says so out loud.
            "/__ui-lang", mx.quest.runway.web.SiteHealthPaths.PATH, mx.quest.runway.web.SiteReloadPaths.PREFIX);

    private final Map<String, Match> byMethodAndPath;

    /** Unit-test form: every contributor counts as kernel code, so any violation fails fast. */
    public EndpointContributorRegistry(List<EndpointContributor> contributors) {
        this(contributors, ContributionGuard.standalone());
    }

    /**
     * @param contributors every {@link EndpointContributor} bean Spring discovered (possibly empty)
     * @param guard        the containment policy: a plugin's bad route is refused alone, a kernel's fails startup
     * @throws IllegalStateException on a <b>kernel</b> contributor's blank/duplicate key, unsupported route,
     *                               reserved-prefix route, or route collision between two kernel contributors
     */
    @Autowired
    public EndpointContributorRegistry(List<EndpointContributor> contributors, ContributionGuard guard) {
        Map<String, Match> table = new LinkedHashMap<>();
        for (EndpointContributor contributor : guard.index("EndpointContributor", contributors,
                EndpointContributor::key).values()) {
            String plugin = guard.pluginOf(contributor);
            List<Route> routes;
            try {
                routes = contributor.routes();
            } catch (RuntimeException | LinkageError failure) {
                if (ContributionGuard.KERNEL.equals(plugin)) {
                    throw failure;
                }
                guard.refuse(plugin, "EndpointContributor '" + contributor.key() + "' routes() threw " + failure);
                continue;
            }
            for (Route route : routes == null ? List.<Route>of() : routes) {
                if (route != null) {
                    register(table, contributor, route, plugin, guard);
                }
            }
        }
        this.byMethodAndPath = Map.copyOf(table);
    }

    /**
     * Registers one route, or refuses it. The refusal reason is the same for kernel and plugin code; only the
     * consequence differs — a kernel route fails startup (a kernel defect), a plugin route is dropped alone and
     * its plugin marked degraded, while the plugin's other routes still load.
     */
    private static void register(Map<String, Match> table, EndpointContributor contributor, Route route,
                                 String plugin, ContributionGuard guard) {
        String problem = unsupported(route);
        if (problem == null) {
            problem = shadowedReservedPrefix(route.pathPattern());
        }
        if (problem != null) {
            String reason = "EndpointContributor '" + contributor.key() + "' route '" + route.method() + " "
                    + route.pathPattern() + "' " + problem;
            if (ContributionGuard.KERNEL.equals(plugin)) {
                throw new IllegalStateException(reason);
            }
            guard.refuse(plugin, reason);
            return;
        }
        String slot = key(route.method(), route.pathPattern());
        Match clash = table.get(slot);
        if (clash == null) {
            table.put(slot, new Match(contributor, route));
            return;
        }
        String clashPlugin = guard.pluginOf(clash.contributor());
        String reason = "Route collision on '" + slot + "': " + clash.contributor().key() + " and "
                + contributor.key();
        if (ContributionGuard.KERNEL.equals(plugin) && ContributionGuard.KERNEL.equals(clashPlugin)) {
            throw new IllegalStateException(reason);
        }
        if (guard.loserIsIncoming(clashPlugin, plugin)) {
            guard.refuse(plugin, reason + " — refused " + contributor.key() + "'s");
        } else {
            table.put(slot, new Match(contributor, route));
            guard.refuse(clashPlugin, reason + " — refused " + clash.contributor().key() + "'s");
        }
    }

    /**
     * Why a route breaks the {@link Route} contract, or {@code null}: the path must be an exact,
     * absolute path — {@code *}, <code>{</code> and <code>}</code> are reserved for a future pattern syntax, and
     * accepting them now would freeze a meaning they do not have — and the realm must be {@code TENANT}, the only
     * realm endpoints are dispatched on.
     */
    static String unsupported(Route route) {
        String path = route.pathPattern();
        if (route.realm() != Realm.TENANT) {
            return "declares realm " + route.realm() + "; endpoint routes are served on the TENANT realm only";
        }
        if (path.isEmpty() || path.charAt(0) != '/') {
            return "is not an absolute path";
        }
        if (path.indexOf('*') >= 0 || path.indexOf('{') >= 0 || path.indexOf('}') >= 0) {
            return "uses a reserved pattern character (*, { or }); routes match exact paths only";
        }
        if (route.anonymous() && !"POST".equalsIgnoreCase(route.method())) {
            return "is anonymous but uses " + route.method() + "; an anonymous route is a public form and must be POST";
        }
        return null;
    }

    private static String shadowedReservedPrefix(String path) {
        if (mx.quest.runway.web.ReservedPaths.isExactKernelRoute(path)) {
            // The recovery routes were missing from the prefix list: a plugin route on /reset would have been
            // dispatched before Spring MVC and served instead of the kernel's password reset (and, declared
            // anonymous, would have been exempt from CSRF on a kernel auth path).
            return "shadows the kernel route '" + path + "'";
        }
        for (String reserved : RESERVED_PREFIXES) {
            if (path.equals(reserved) || path.startsWith(reserved)) {
                return "shadows the reserved prefix '" + reserved + "'";
            }
        }
        return null;
    }

    /** The contributor + route matching {@code method}+{@code path} exactly, or empty. */
    public Optional<Match> match(String method, String path) {
        return Optional.ofNullable(byMethodAndPath.get(key(method, path)));
    }

    /**
     * Whether {@code method}+{@code path} is a registered <b>anonymous</b> route — the question the tenant chain's
     * CSRF exemption asks ({@link AnonymousRouteMatcher}). The same exact-match key as {@link #match}, so the
     * exemption and the dispatch can never disagree about which request is which route.
     */
    public boolean isAnonymous(String method, String path) {
        return match(method, path).map(found -> found.route().anonymous()).orElse(false);
    }

    /** Whether no routes are registered. */
    public boolean isEmpty() {
        return byMethodAndPath.isEmpty();
    }

    private static String key(String method, String path) {
        return (method == null ? "" : method.toUpperCase(java.util.Locale.ROOT)) + " " + path;
    }
}
