package mx.quest.runway.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import mx.quest.runway.authz.AuthzStoreIntegrity;
import mx.quest.runway.bandwidth.BandwidthService;
import mx.quest.runway.plugin.EssentialFloor;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import org.junit.jupiter.api.Test;

/**
 * The health precedence matrix — pure, no Spring, no servlet types. The ordering here is a CORRECTNESS
 * requirement: it must mirror the filter ladder (suspended 60 &gt; floor 67 &gt; maintenance 68 &gt; quota 69),
 * or the probe names a different cause than the one a real visitor hits.
 */
class SiteHealthServiceTest {

    private static Site site(SiteStatus status, boolean maintenance) {
        return new Site("acme", "Acme", List.of("acme.test"), Locale.ENGLISH, List.of(Locale.ENGLISH),
                "https://acme.test", status, maintenance, maintenance ? "back soon" : null);
    }

    private static Site active() {
        return site(SiteStatus.ACTIVE, false);
    }

    /** A service whose collaborators all report "fine" unless a test says otherwise. */
    private static SiteHealthService service(boolean floorFailed, boolean overQuota, boolean quarantined,
                                             SiteHealth.ContentCheck content) {
        EssentialFloor floor = mock(EssentialFloor.class);
        when(floor.failed()).thenReturn(floorFailed);
        BandwidthService bandwidth = mock(BandwidthService.class);
        when(bandwidth.isOverQuota("acme")).thenReturn(overQuota);
        AuthzStoreIntegrity authz = mock(AuthzStoreIntegrity.class);
        when(authz.isQuarantined("acme")).thenReturn(quarantined);
        SiteContentReadiness readiness = mock(SiteContentReadiness.class);
        when(readiness.check(org.mockito.ArgumentMatchers.any())).thenReturn(content);
        return new SiteHealthService(floor, bandwidth, authz, readiness);
    }

    private static SiteHealthService healthy() {
        return service(false, false, false, SiteHealth.ContentCheck.OK);
    }

    @Test
    void anActiveSiteWithEverythingFineIsUp() {
        SiteHealth health = healthy().evaluate(active());
        assertThat(health.status()).isEqualTo(SiteHealth.Status.UP);
        assertThat(health.state()).isEqualTo(SiteHealth.State.ACTIVE);
        assertThat(health.down()).isFalse();
        assertThat(health.retryable()).isFalse();
    }

    @Test
    void suspendedOutranksEveryOtherBlock() {
        // All four blocks at once: suspension must win, because SuspendedSiteFilter (60) is outermost.
        SiteHealth health = service(true, true, true, SiteHealth.ContentCheck.OK)
                .evaluate(site(SiteStatus.SUSPENDED, true));
        assertThat(health.state()).isEqualTo(SiteHealth.State.SUSPENDED);
        assertThat(health.status()).isEqualTo(SiteHealth.Status.DOWN);
        assertThat(health.retryable()).as("suspension is indefinite — promising a retry window would lie").isFalse();
    }

    @Test
    void theDegradedPlatformFloorOutranksMaintenanceAndQuota() {
        SiteHealth health = service(true, true, false, SiteHealth.ContentCheck.OK).evaluate(site(SiteStatus.ACTIVE, true));
        assertThat(health.state()).isEqualTo(SiteHealth.State.PLATFORM_DEGRADED);
        assertThat(health.retryable()).isFalse();
    }

    @Test
    void maintenanceOutranksQuota() {
        SiteHealth health = service(false, true, false, SiteHealth.ContentCheck.OK).evaluate(site(SiteStatus.ACTIVE, true));
        assertThat(health.state()).isEqualTo(SiteHealth.State.MAINTENANCE);
        assertThat(health.status()).isEqualTo(SiteHealth.Status.DOWN);
        assertThat(health.retryable()).as("maintenance is time-bounded, like the filter's own Retry-After").isTrue();
    }

    @Test
    void overQuotaIsTheLastBlockAndIsRetryable() {
        SiteHealth health = service(false, true, false, SiteHealth.ContentCheck.OK).evaluate(active());
        assertThat(health.state()).isEqualTo(SiteHealth.State.OVER_QUOTA);
        assertThat(health.retryable()).isTrue();
    }

    @Test
    void quarantineDegradesButNeverTakesTheSiteDown() {
        // QuarantinedSiteFilter 503s /__admin ONLY — the public site serves fine, so nobody should be paged.
        SiteHealth health = service(false, false, true, SiteHealth.ContentCheck.OK).evaluate(active());
        assertThat(health.status()).isEqualTo(SiteHealth.Status.DEGRADED);
        assertThat(health.state()).isEqualTo(SiteHealth.State.ACTIVE);
        assertThat(health.admin()).isEqualTo(SiteHealth.AdminCheck.QUARANTINED);
        assertThat(health.down()).isFalse();
    }

    @Test
    void aFailedContentCheckDegradesButNeverTakesTheSiteDown() {
        SiteHealth missingHome = service(false, false, false, SiteHealth.ContentCheck.MISSING_HOME).evaluate(active());
        assertThat(missingHome.status()).isEqualTo(SiteHealth.Status.DEGRADED);
        assertThat(missingHome.down()).isFalse();

        // MISSING_MASTER can be a false alarm (an all-standalone site needs no master) — hence degraded, not down.
        SiteHealth missingMaster = service(false, false, false, SiteHealth.ContentCheck.MISSING_MASTER).evaluate(active());
        assertThat(missingMaster.status()).isEqualTo(SiteHealth.Status.DEGRADED);
    }

    @Test
    void aSkippedContentCheckIsNotTreatedAsAFault() {
        SiteHealth health = service(false, false, false, SiteHealth.ContentCheck.SKIPPED).evaluate(active());
        assertThat(health.status()).isEqualTo(SiteHealth.Status.UP);
    }

    @Test
    void aBlockedSiteSkipsTheContentCheckEntirely() {
        // Stat'ing templates for a site nobody can reach is waste; the verdict is DOWN either way.
        SiteHealth health = service(false, false, false, SiteHealth.ContentCheck.MISSING_HOME)
                .evaluate(site(SiteStatus.SUSPENDED, false));
        assertThat(health.content()).isEqualTo(SiteHealth.ContentCheck.SKIPPED);
    }
}
