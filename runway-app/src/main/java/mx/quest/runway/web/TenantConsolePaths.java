package mx.quest.runway.web;

import java.util.List;

/**
 * The one definition of the <b>tenant console realm</b>'s paths: the site's admin console ({@code /__admin/**}),
 * its sign-in and sign-out ({@code /login}, {@code /logout}), its password recovery ({@code /forgot-password},
 * {@code /reset}) and the self-hosted console assets those pages load ({@code /__assets/**}).
 *
 * <p>Tenant-band soft-block filters that short-circuit a site's <b>public</b> traffic (maintenance mode, the
 * bandwidth quota) exempt this realm so the site manager is never locked out: they can always sign in, recover a
 * forgotten password, and lift the block. It is the tenant parallel of {@link AppRealmPaths}. The
 * recovery routes belong here too: without them, a site in maintenance would answer {@code 503} on
 * {@code /forgot-password} while {@code /login} worked.
 *
 * <p>Exact matches and real namespaces only, so a public page at {@code /__adminx} is not exempted. Like
 * {@link AppRealmPaths}, the predicates take a <b>decoded</b> path ({@link RequestPaths#normalized}).
 */
public final class TenantConsolePaths {

    /** The site's admin console namespace. */
    public static final String ADMIN_PREFIX = "/__admin";

    /** The self-hosted operator assets namespace (CSS, scripts, fonts of the console and the sign-in pages). */
    public static final String ASSETS_PREFIX = "/__assets";

    /** The tenant realm's single-path routes: sign-in, sign-out, password recovery. */
    public static final List<String> CONSOLE_ROUTES = List.of("/login", "/logout", "/forgot-password", "/reset");

    private TenantConsolePaths() {
    }

    /** Whether the decoded {@code path} is a tenant-console path: a console page or a console asset. */
    public static boolean isConsole(String path) {
        return isConsolePage(path) || AppRealmPaths.inNamespace(path, ASSETS_PREFIX);
    }

    /**
     * Whether the decoded {@code path} is a page the tenant console renders: {@code /__admin/**}, sign-in,
     * sign-out and recovery. {@link #isConsole} without the assets.
     */
    public static boolean isConsolePage(String path) {
        return path != null && (AppRealmPaths.inNamespace(path, ADMIN_PREFIX) || CONSOLE_ROUTES.contains(path));
    }
}
