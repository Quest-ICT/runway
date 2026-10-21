package mx.quest.runway.health;

import mx.quest.runway.authz.AuthzStoreIntegrity;
import mx.quest.runway.bandwidth.BandwidthService;
import mx.quest.runway.plugin.EssentialFloor;
import mx.quest.runway.site.Site;

/**
 * Composes the five independent state sources into one {@link SiteHealth} verdict. Pure: no servlet types, no
 * Spring context, no I/O of its own beyond what its collaborators do — so the whole precedence matrix is
 * unit-testable directly.
 *
 * <p><b>The precedence mirrors the filter ladder exactly</b>, and that is a correctness requirement, not a
 * stylistic one: if this ordering diverged, {@code /__health} would name a different cause than the one a real
 * visitor actually hits. Reading outward-to-inward as the request does —
 * {@code SuspendedSiteFilter}(60) → {@code EssentialFloorFilter}(67) → {@code MaintenanceSiteFilter}(68) →
 * {@code BandwidthLimitFilter}(69) — the first that would fire wins. ({@code MaintenanceSiteFilter} itself guards
 * on {@code !suspended}, so suspension genuinely does outrank maintenance on the wire.)
 *
 * <p>{@code QuarantinedSiteFilter}(65) is deliberately NOT in that ladder. It 503s only {@code /__admin}; the
 * public site keeps serving visitors normally. Reporting it as {@code DOWN} would page an operator about a site
 * that is fine for every actual reader, so it contributes {@link SiteHealth.Status#DEGRADED} and nothing more.
 *
 * <p><b>Two documented staleness caveats</b> — surfaced rather than papered over, because an operator acting on a
 * stale fact is worse than one who knows the lag: {@link BandwidthService#isOverQuota} reads a set recomputed on
 * the metering snapshot cadence (default 60s), so {@code OVER_QUOTA} can lag by about that; and
 * {@link AuthzStoreIntegrity} is a <b>startup</b> snapshot, so a repaired {@code roles.yml} keeps reporting
 * quarantined until the next restart.
 */
public class SiteHealthService {

    private final EssentialFloor essentialFloor;
    private final BandwidthService bandwidth;
    private final AuthzStoreIntegrity authzIntegrity;
    private final SiteContentReadiness content;

    public SiteHealthService(EssentialFloor essentialFloor, BandwidthService bandwidth,
                             AuthzStoreIntegrity authzIntegrity, SiteContentReadiness content) {
        this.essentialFloor = essentialFloor;
        this.bandwidth = bandwidth;
        this.authzIntegrity = authzIntegrity;
        this.content = content;
    }

    /** The verdict for {@code site}. */
    public SiteHealth evaluate(Site site) {
        SiteHealth.AdminCheck admin = authzIntegrity.isQuarantined(site.id())
                ? SiteHealth.AdminCheck.QUARANTINED
                : SiteHealth.AdminCheck.OK;

        // The blocking ladder, in the filters' own order. Content is not consulted here: a blocked site is DOWN
        // regardless of whether its templates are intact, and stat'ing files for a site nobody can reach is waste.
        SiteHealth.State blocked = blockingState(site);
        if (blocked != null) {
            return new SiteHealth(SiteHealth.Status.DOWN, blocked, SiteHealth.ContentCheck.SKIPPED, admin);
        }

        SiteHealth.ContentCheck contentCheck = content.check(site);
        boolean degraded = admin == SiteHealth.AdminCheck.QUARANTINED
                || (contentCheck != SiteHealth.ContentCheck.OK && contentCheck != SiteHealth.ContentCheck.SKIPPED);
        return new SiteHealth(degraded ? SiteHealth.Status.DEGRADED : SiteHealth.Status.UP,
                SiteHealth.State.ACTIVE, contentCheck, admin);
    }

    /** The first gate that would short-circuit a public request, or {@code null} if the site is serving. */
    private SiteHealth.State blockingState(Site site) {
        if (site.suspended()) {
            return SiteHealth.State.SUSPENDED;            // SuspendedSiteFilter, order 60
        }
        if (essentialFloor.failed()) {
            return SiteHealth.State.PLATFORM_DEGRADED;    // EssentialFloorFilter, order 67
        }
        if (site.maintenance()) {
            return SiteHealth.State.MAINTENANCE;          // MaintenanceSiteFilter, order 68
        }
        if (bandwidth.isOverQuota(site.id())) {
            return SiteHealth.State.OVER_QUOTA;           // BandwidthLimitFilter, order 69
        }
        return null;
    }
}
