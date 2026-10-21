package mx.quest.runway.web;

/**
 * The single predicate for "this path is <b>platform</b> traffic, not a tenant's" — the surfaces that must be
 * excluded from per-site accounting: {@code BandwidthFilter}'s byte metering (and therefore the quota that can
 * 503 a site) and {@code SiteRequestMetricsFilter}'s per-site request timer.
 *
 * <p>Both filters previously carried an identical hand-copied condition. One authority means they can no longer
 * drift apart — a path metered by one but not the other would make a tenant's quota and its traffic graph
 * disagree, which is exactly the kind of discrepancy nobody notices until it is billed.
 *
 * <p>The set: the application realm exactly as {@link AppRealmPaths#isAppRealm} defines it (the console, its
 * sign-in and recovery routes, {@code /actuator}), the per-site {@code /__health} probe, and the operator
 * {@code /__reload} endpoint. It takes a <b>decoded</b> path ({@link RequestPaths#normalized}): {@code /%5F%5Fapp}
 * is console traffic, and was once charged to the site whose host it was sent to. Before {@code 1.0.0-rc4} the realm part
 * was a loose prefix ({@code /__app…}, {@code /app/…}, {@code /actuator…}), which also left every other path under
 * {@code /app/} unmetered (now only the four console routes are). Health is excluded because a monitoring system polls it continuously and by design:
 * charging that to the tenant would let an operator's own probe consume the tenant's quota and eventually 503 the
 * site it is meant to be watching, and it would bury real traffic in the per-site request timer.
 *
 * <p><b>{@code /__health} matches EXACTLY, never by prefix.</b> The realm prefixes below are genuine namespaces
 * whose whole subtree is platform traffic; {@code /__health} is one concrete route, so a hypothetical
 * {@code /__healthful} must stay metered rather than silently escaping accounting.
 */
public final class UnmeteredPaths {

    private UnmeteredPaths() {
    }

    /** Whether {@code path} is platform traffic excluded from per-site bandwidth metering and request metrics. */
    public static boolean isUnmetered(String path) {
        if (path == null) {
            return false;
        }
        return AppRealmPaths.isAppRealm(path)
                || path.equals(SiteHealthPaths.PATH)
                || AppRealmPaths.inNamespace(path, SiteReloadPaths.PREFIX);
    }
}
