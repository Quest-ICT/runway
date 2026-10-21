package mx.quest.runway.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The one authority for "platform traffic, never charged to a tenant", shared by the bandwidth meter and the
 * per-site request timer so the two can no longer drift apart.
 */
class UnmeteredPathsTest {

    @Test
    void platformRealmsAndActuatorAreUnmetered() {
        assertThat(UnmeteredPaths.isUnmetered("/__app")).isTrue();
        assertThat(UnmeteredPaths.isUnmetered("/__app/sites")).isTrue();
        assertThat(UnmeteredPaths.isUnmetered("/app/login")).isTrue();
        assertThat(UnmeteredPaths.isUnmetered("/actuator/health")).isTrue();
    }

    @Test
    void theHealthProbeIsUnmeteredSoAMonitorCannotConsumeATenantsQuota() {
        assertThat(UnmeteredPaths.isUnmetered("/__health")).isTrue();
    }

    @Test
    void theHealthProbeMatchesExactlyNeverByPrefix() {
        // A prefix match would let a hypothetical neighbouring path escape accounting silently.
        assertThat(UnmeteredPaths.isUnmetered("/__healthful")).isFalse();
        assertThat(UnmeteredPaths.isUnmetered("/__health/x")).isFalse();
    }

    @Test
    void ordinaryTenantTrafficIsMetered() {
        assertThat(UnmeteredPaths.isUnmetered("/")).isFalse();
        assertThat(UnmeteredPaths.isUnmetered("/about")).isFalse();
        assertThat(UnmeteredPaths.isUnmetered("/health")).isFalse();     // a free tenant slug, not our probe
        assertThat(UnmeteredPaths.isUnmetered("/__admin")).isFalse();    // tenant console IS the tenant's own
        assertThat(UnmeteredPaths.isUnmetered(null)).isFalse();
    }

    @Test
    void theRealmIsTheAppRealmExactlyNotAPrefix() {
        assertThat(UnmeteredPaths.isUnmetered("/app/forgot-password")).isTrue();
        assertThat(UnmeteredPaths.isUnmetered("/app/reset")).isTrue();
        assertThat(UnmeteredPaths.isUnmetered("/app/anything-else")).isFalse();
        assertThat(UnmeteredPaths.isUnmetered("/__apple")).isFalse();
        assertThat(UnmeteredPaths.isUnmetered("/actuatorX")).isFalse();
        assertThat(UnmeteredPaths.isUnmetered("/__reload")).isTrue();
        assertThat(UnmeteredPaths.isUnmetered("/__reloaded")).isFalse();
    }
}
