package mx.quest.runway.plugin;

import org.springframework.stereotype.Component;

/**
 * The shared fail-closed flag for the security floor: {@code true} when an allowlisted-essential plugin either
 * failed to load (computed pre-refresh by {@link PluginContextInitializer}, in {@link PluginRuntime}) <b>or</b>
 * loaded but did not arm the floor (its {@code @Primary} {@code EdgeGuard}/{@code LoginAttemptGate} override
 * silently did not take effect — verified post-refresh by {@link PluginManager}). {@link EssentialFloorFilter}
 * reads it per request to decide whether to serve tenant hosts a {@code 503}. A separate mutable bean (not the
 * immutable {@link PluginRuntime}) because the post-refresh arming check can only run once all beans exist.
 */
@Component
public class EssentialFloor {

    private volatile boolean failed;

    /** Whether the platform is in fail-closed degraded mode (an essential security-floor plugin is missing/broken). */
    public boolean failed() {
        return failed;
    }

    /** Set by {@link PluginManager} once the post-refresh arming check has run. */
    void set(boolean failed) {
        this.failed = failed;
    }
}
