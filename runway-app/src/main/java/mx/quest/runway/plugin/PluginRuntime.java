package mx.quest.runway.plugin;

import java.util.List;
import java.util.Map;

/**
 * The result of the pre-refresh load, published as a singleton so post-refresh collaborators ({@link PluginManager},
 * the resource configs, the admin read-model) share one authoritative view: the plugins whose {@code mainClass}
 * was registered (ordered), the shared classloader, the blocked map (id → reason), and the {@link #essentialFailed}
 * flag that drives fail-closed degraded mode. Immutable.
 *
 * <p>(Until SPI 1.0.0 it also carried the pre-refresh compliance <i>warnings</i>; from 1.0.0 every such finding blocks
 * the plugin, so it is in {@link #blocked} instead.)
 *
 * @param loaded          the registered plugins (mainClass'd or library-only), in load order
 * @param classLoader     the shared plugin classloader (the app classloader when no plugins loaded)
 * @param blocked         id → operator-readable reason for every plugin that did not load
 * @param essentialFailed whether an allowlisted-essential plugin failed to load (fail-closed)
 */
public record PluginRuntime(List<LoadedPlugin> loaded, ClassLoader classLoader, Map<String, String> blocked,
                            boolean essentialFailed) {

    /** The singleton bean name the initializer registers this under, for post-refresh lookup. */
    public static final String BEAN_NAME = "runwayPluginRuntime";

    public PluginRuntime {
        loaded = List.copyOf(loaded);
        blocked = Map.copyOf(blocked);
    }

    /** An empty runtime — no plugins loaded (WIZARD mode, no plugins present, or a non-SpringApplication context). */
    public static PluginRuntime empty(ClassLoader classLoader) {
        return new PluginRuntime(List.of(), classLoader, Map.of(), false);
    }
}
