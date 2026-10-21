package mx.quest.demo;

import mx.quest.runway.spi.RunwaySpi;
import mx.quest.runway.spi.model.PluginInfo;
import mx.quest.runway.spi.service.PluginLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code PluginLifecycle} — the optional start/stop hook. <b>When to use it:</b> to run code once the platform is up
 * (warm a cache, open an index, start your own executor) or when it shuts down (close what you opened). A plugin that
 * only contributes beans needs none.
 *
 * <p>The host collects every {@code PluginLifecycle} bean by type and matches it to its plugin by the jar its class was
 * loaded from — there is no {@code id()} to keep in sync with {@code plugin.yml}. {@link #started} runs once, after
 * every singleton exists, in plugin load order; {@link #stopping} runs at shutdown in reverse order, only if
 * {@code started} returned normally. Both block the host's startup/shutdown thread, so keep them short; a throw is
 * logged and counted on the plugins screen and the platform keeps booting.
 *
 * <p>{@link PluginInfo} is this plugin's own identity — its id, the version from {@code plugin.yml} and its install
 * folder. It arrives here and can also be injected into any bean.
 *
 * <p>{@link RunwaySpi#VERSION} is a compile-time constant, so in plugin code it is the SPI version the plugin was
 * <b>built against</b>; {@link RunwaySpi#runtimeVersion()} is the SPI of the kernel it is <b>running on</b>.
 */
public class DemoLifecycle implements PluginLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DemoLifecycle.class);

    private volatile PluginInfo self;

    @Override
    public void started(PluginInfo self) {
        this.self = self;
        log.info("{} {} started — install dir {}; built against SPI {}, running on SPI {}", self.id(),
                self.version(), self.installDir(), RunwaySpi.VERSION, RunwaySpi.runtimeVersion());
    }

    @Override
    public void stopping() {
        PluginInfo info = self;
        log.info("{} stopping", info == null ? "runway-demo" : info.id());
    }
}
