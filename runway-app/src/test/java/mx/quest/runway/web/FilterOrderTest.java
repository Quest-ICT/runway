package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import mx.quest.runway.spi.FilterBand;
import org.junit.jupiter.api.Test;

/** The kernel ladder is strictly outer→inner and the plugin band windows are disjoint and correctly nested. */
class FilterOrderTest {

    @Test
    void theCoreLadderIsStrictlyOuterToInner() {
        int[] ladder = {
                FilterOrder.CORE_FORWARDED,
                FilterOrder.CORE_PROTECTION,
                FilterOrder.CORE_ACCESS_LOG,
                FilterOrder.CORE_BANDWIDTH,
                FilterOrder.CORE_SITE_METRICS,
                FilterOrder.CORE_COMPRESSION,
                FilterOrder.CORE_CSP_FINALIZE,
                FilterOrder.CORE_HEAD_INJECTION,
                FilterOrder.CORE_SITE_CONTEXT,
                FilterOrder.CORE_SITE_HEALTH,
                FilterOrder.CORE_SITE_RELOAD,
                FilterOrder.CORE_CONSOLE_HOST,
                FilterOrder.CORE_ROUTE_FORM_CAPTURE,
                FilterOrder.CORE_FORM_CONTENT,
                FilterOrder.CORE_SUSPENDED_SITE,
                FilterOrder.CORE_QUARANTINED_SITE,
                FilterOrder.CORE_ESSENTIAL_FLOOR,
                FilterOrder.CORE_EDGE_MODE,
                FilterOrder.CORE_SECURITY,
                FilterOrder.CORE_ENDPOINT_DISPATCH,
        };
        for (int i = 1; i < ladder.length; i++) {
            assertTrue(ladder[i - 1] < ladder[i],
                    "ladder not strictly increasing at index " + i + ": " + ladder[i - 1] + " !< " + ladder[i]);
        }
    }

    @Test
    void theEgressTrioInjectsThenCompresses() {
        // Compression is OUTER of head-injection so head-injection's post (rewrite) runs before
        // compression's post (encode): inject-then-compress.
        assertTrue(FilterOrder.CORE_COMPRESSION < FilterOrder.CORE_CSP_FINALIZE);
        assertTrue(FilterOrder.CORE_CSP_FINALIZE < FilterOrder.CORE_HEAD_INJECTION);
    }

    @Test
    void siteContextIsOuterOfSuspendedQuarantineAndSecurity() {
        // The tenant-isolation linchpin: the site is bound before the gates and the security chain read it.
        assertTrue(FilterOrder.CORE_SITE_CONTEXT < FilterOrder.CORE_SUSPENDED_SITE);
        // The health probe MUST sit between site binding and the first gate: inner of 50 so it knows which site
        // it answers for, outer of 60..69 so it REPORTS suspension/maintenance/quota instead of being 503'd by
        // them. Inward drift here silently turns the monitor off in exactly the states worth monitoring.
        assertTrue(FilterOrder.CORE_SITE_CONTEXT < FilterOrder.CORE_SITE_HEALTH);
        assertTrue(FilterOrder.CORE_SITE_HEALTH < FilterOrder.CORE_SUSPENDED_SITE);
        assertTrue(FilterOrder.CORE_SITE_HEALTH < FilterOrder.CORE_ESSENTIAL_FLOOR);
        assertTrue(FilterOrder.CORE_SITE_HEALTH < FilterOrder.CORE_MAINTENANCE);
        assertTrue(FilterOrder.CORE_SITE_HEALTH < FilterOrder.CORE_BANDWIDTH_LIMIT);
        assertTrue(FilterOrder.CORE_SITE_HEALTH < FilterOrder.CORE_SECURITY);
        // The reload endpoint sits beside health and for the same reason: outer of every tenant gate, so a
        // suspended/maintenance catch-all site cannot 503 the request that would re-read the config fixing it.
        assertTrue(FilterOrder.CORE_SITE_HEALTH < FilterOrder.CORE_SITE_RELOAD);
        assertTrue(FilterOrder.CORE_SITE_RELOAD < FilterOrder.CORE_SUSPENDED_SITE);
        assertTrue(FilterOrder.CORE_SITE_RELOAD < FilterOrder.CORE_MAINTENANCE);
        // THE invariant behind the reload endpoint's loopback gate. It reads request.getRemoteAddr(), which only
        // means "the real client" because TrustedForwardedHeaderFilter has already resolved it. Move the reload
        // filter OUTSIDE that one and getRemoteAddr() becomes the raw TCP peer — which, behind a proxy that
        // originates upstream connections from loopback (HAProxy 'source 127.0.0.1'), is 127.0.0.1 for EVERY
        // internet request. The endpoint would then be publicly reachable with only its token in front, and a
        // localhost end-to-end test would still pass. Reported from a live deployment, where that proxy setting is live.
        assertTrue(FilterOrder.CORE_FORWARDED < FilterOrder.CORE_SITE_RELOAD);
        assertTrue(FilterOrder.CORE_SUSPENDED_SITE < FilterOrder.CORE_QUARANTINED_SITE);
        assertTrue(FilterOrder.CORE_QUARANTINED_SITE < FilterOrder.CORE_SECURITY);
    }

    @Test
    void pluginWindowsAreWellFormedAndOrdered() {
        // FilterBand is kept out of this class's method *signatures* on purpose — referencing an SPI type in a
        // helper's parameter forces eager resolution at JUnit discovery, which fails under a plugin test's
        // leaked thread-context classloader. Inlined in the body, the type resolves at execution instead.
        var ingress = PluginFilterPlacement.window(FilterBand.EDGE_INGRESS);
        var egress = PluginFilterPlacement.window(FilterBand.EDGE_EGRESS);
        var tenant = PluginFilterPlacement.window(FilterBand.TENANT);
        var application = PluginFilterPlacement.window(FilterBand.APPLICATION);

        for (FilterBand band : FilterBand.values()) {
            if (band.contributable()) {
                var w = PluginFilterPlacement.window(band);
                assertTrue(w.outer() <= w.inner(), band + " window is inverted");
            }
        }
        // EDGE_INGRESS: inner of the protection floor, outer of the egress kernel filters.
        assertTrue(ingress.outer() == FilterOrder.CORE_PROTECTION + 1, "the hard floor");
        assertTrue(ingress.inner() < FilterOrder.CORE_COMPRESSION);
        // EDGE_EGRESS: inner of head-injection, outer of the TENANT band.
        assertTrue(egress.outer() > FilterOrder.CORE_HEAD_INJECTION);
        assertTrue(egress.inner() < FilterOrder.CORE_SITE_CONTEXT);
        // TENANT: after the kernel tenant gates, before security.
        assertTrue(tenant.outer() > FilterOrder.CORE_EDGE_MODE);
        assertTrue(tenant.inner() < FilterOrder.CORE_SECURITY);
        // APPLICATION: inner of security (never preempts it).
        assertTrue(application.outer() > FilterOrder.CORE_SECURITY);
        // The access log: inner of every EDGE_INGRESS filter (floor rejections stay out of it), outer of every
        // other band, so whatever a plugin filter answers is logged.
        assertTrue(ingress.inner() < FilterOrder.CORE_ACCESS_LOG);
        assertTrue(FilterOrder.CORE_ACCESS_LOG < FilterOrder.CORE_BANDWIDTH);
        assertTrue(FilterOrder.CORE_ACCESS_LOG < egress.outer());
        // The windows are disjoint and nested outer→inner.
        assertTrue(ingress.inner() < egress.outer());
        assertTrue(egress.inner() < tenant.outer());
        assertTrue(tenant.inner() < application.outer());
        // SECURITY is not plugin-reachable.
        assertTrue(!FilterBand.SECURITY.contributable());
    }
}
