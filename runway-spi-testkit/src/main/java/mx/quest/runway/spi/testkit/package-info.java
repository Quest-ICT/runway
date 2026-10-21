/**
 * Test doubles for the SPI service tier, for plugin unit tests ({@code test} scope). A plugin that migrates off
 * host classes tests against these instead of building real kernel objects.
 *
 * <p>It holds a fake for every service that exists in SPI 0.6.0:
 * {@link mx.quest.runway.spi.testkit.FakePluginSettings} (0.4.0), {@link mx.quest.runway.spi.testkit.TempDirSiteFiles}
 * (the per-site files over a temporary folder, with the kernel's real path-confinement rules),
 * {@link mx.quest.runway.spi.testkit.FakeSiteServices} (sites, request context, access check, messages and starter
 * pages, 0.5.0), {@link mx.quest.runway.spi.testkit.FakeContentCatalog} (the content surface, 0.6.0) and
 * {@link mx.quest.runway.spi.testkit.EventDispatch} (delivers change events to a plugin's listeners in the kernel's
 * phase order, and is the plugin's {@code ContentChanges}, 0.6.0); {@link mx.quest.runway.spi.model.PluginInfo} is a
 * plain record a test constructs directly. Every fake applies the same input rules as the kernel implementation, so
 * a plugin that passes against the fake behaves the same against the kernel.
 */
package mx.quest.runway.spi.testkit;
