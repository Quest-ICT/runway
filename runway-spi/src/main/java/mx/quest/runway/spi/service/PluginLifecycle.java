package mx.quest.runway.spi.service;

import mx.quest.runway.spi.model.PluginInfo;

/**
 * An optional start/stop hook. A plugin that only contributes beans needs none. Implement it on any bean from the
 * plugin's own jar to run code once the platform is up (warm a cache, open an index, schedule work) or when it
 * shuts down. The host collects every {@code PluginLifecycle} bean by type and matches each to its plugin by the
 * jar its class was loaded from — a {@code PluginLifecycle} defined in kernel code or a {@code lib/} jar is
 * ignored with a warning.
 *
 * <h2>Limits &amp; failure</h2>
 * <ul>
 *   <li>{@link #started} runs once, after the context has refreshed and every singleton exists, in plugin load
 *       order (a plugin after the plugins it {@code requires:}). {@link #stopping} runs at shutdown in the
 *       reverse order, and only for hooks whose {@code started} returned normally.</li>
 *   <li>Both run on the host's startup/shutdown thread, which they block: keep them short and hand long work to
 *       the plugin's own executor.</li>
 *   <li>Each call is isolated: a {@link RuntimeException} or {@link LinkageError} it throws is logged (and
 *       counted on the plugins screen) and the next hook still runs; the platform keeps booting.</li>
 * </ul>
 *
 * <p>Replaces the host-internal {@code RunwayPlugin}/{@code PluginContext} pair, which handed plugins the host's
 * {@code ApplicationContext}.
 *
 * @since SPI 0.4.0
 */
public interface PluginLifecycle {

    /**
     * Called once the platform is up.
     *
     * @param self the plugin this hook belongs to
     */
    default void started(PluginInfo self) {
    }

    /** Called at shutdown, before the context closes. */
    default void stopping() {
    }
}
