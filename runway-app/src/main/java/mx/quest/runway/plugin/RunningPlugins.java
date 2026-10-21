package mx.quest.runway.plugin;

import java.util.List;

/**
 * The frozen "what loaded this boot" view, as a one-method seam. {@link PluginManager} is the production
 * implementation (it owns the {@link PluginRuntime}); {@link PluginAdminService} depends on this narrow
 * contract rather than the whole manager, so the drift read model can be unit-tested against a hand-built
 * running set (a lambda) with no Spring lifecycle.
 */
@FunctionalInterface
public interface RunningPlugins {

    /** The plugins running this boot (the frozen {@link PluginRuntime#loaded()}), in load order. */
    List<LoadedPlugin> installed();
}
