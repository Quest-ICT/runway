package mx.quest.runway.web;

import java.util.Set;

/**
 * The kernel's <b>single authority</b> on which public paths are reserved — carved out of the tenant content
 * namespace because an exact kernel route (auth, recovery) or a reserved prefix serves them instead. Two views,
 * with deliberately different scope:
 *
 * <ul>
 *   <li>{@link #atRequestTime}: the blanket prefixes the page controller refuses before routing — kept
 *       EXACTLY as it always was ({@code /__…}, {@code /actuator…}, {@code /error}); the exact auth routes
 *       don't need to appear here because Spring MVC's exact mappings already beat the {@code /**} catch-all
 *       at dispatch time.</li>
 *   <li>{@link #atEnumerationTime}: the FULL union a content listing must drop — everything request-time
 *       reserves PLUS the app-realm prefix and the exact kernel tenant routes ({@code /login},
 *       {@code /logout}, {@code /forgot-password}, {@code /reset}) — because a sitemap/llms.txt/url-map must
 *       never advertise a URL the router serves as something other than content. (Exact
 *       {@code EndpointContributor} routes are additionally dropped by the catalog, which owns that
 *       registry.)</li>
 * </ul>
 *
 * <p><b>This class exists because the list drifted once</b>: the recovery routes landed in a later workstream
 * than the original login/logout vocabulary and were missed by a hand-maintained copy. When adding a kernel
 * tenant-realm route, add it HERE — every consumer follows.
 */
public final class ReservedPaths {

    /** The exact kernel tenant-realm routes (Spring-mapped; they beat the page catch-all at dispatch). */
    private static final Set<String> EXACT_KERNEL_ROUTES =
            Set.of("/error", "/login", "/logout", "/forgot-password", "/reset");

    private ReservedPaths() {
    }

    /** Whether the page controller must refuse {@code path} before routing (the blanket reserved surface). */
    public static boolean atRequestTime(String path) {
        return path.startsWith("/__") || path.startsWith("/actuator") || path.equals("/error");
    }

    /**
     * Whether {@code path} is exactly one of the kernel's own tenant-realm routes ({@code /error}, {@code /login},
     * {@code /logout}, {@code /forgot-password}, {@code /reset}) — the routes a plugin endpoint must never claim,
     * because the endpoint dispatcher runs before Spring MVC and would serve the plugin's handler in their place.
     */
    public static boolean isExactKernelRoute(String path) {
        return EXACT_KERNEL_ROUTES.contains(path);
    }

    /** Whether a content listing must drop {@code path} — the full enumerated union (see the class doc). */
    public static boolean atEnumerationTime(String path) {
        return atRequestTime(path) || path.startsWith("/app/") || EXACT_KERNEL_ROUTES.contains(path);
    }
}
