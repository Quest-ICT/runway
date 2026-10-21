package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginVersion;
import mx.quest.runway.spi.RunwaySpi;
import org.junit.jupiter.api.Test;

/**
 * The shipped descriptor declares the SPI floor the plugin's code actually needs.
 *
 * <h2>Why</h2>
 * The installer (on every upgrade) replaces the
 * protection jar in the version-independent
 * {@code external/plugins/protection/}, so after an upgrade the jar outlives the kernel it came with: a rollback
 * is a symlink flip that puts an older kernel in front of the newer jar. The plugin links
 * {@code spi.service.RequestContext} (SPI 0.5.0) and {@code spi.service.ConsoleScreen} (SPI 0.6.0). A kernel
 * without them would register the plugin and then fail to start with {@code NoClassDefFoundError: …RequestContext}
 * — the essential plugin, so nothing comes up, and nothing names the plugin. The {@code requiresSpi} floor makes
 * every kernel that reads it refuse the plugin with a reason instead.
 */
class DescriptorSpiFloorTest {

    /** The highest SPI level whose types the plugin's code links today. Raise it with the code. */
    private static final PluginVersion NEEDED = PluginVersion.parse("0.6.0");

    private static PluginDescriptor descriptor() throws IOException {
        try (InputStream in = Files.newInputStream(Path.of("plugin.yml"))) {
            PluginDescriptor descriptor = PluginDescriptor.read(in);
            descriptor.validate();
            return descriptor;
        }
    }

    @Test
    void theDescriptorDeclaresAFloorCoveringTheSpiTypesTheCodeLinks() throws IOException {
        PluginVersion floor = descriptor().spiFloor();
        assertNotNull(floor, "plugin.yml must declare requiresSpi — without it an older kernel registers the jar "
                + "and then dies on a NoClassDefFoundError");
        assertTrue(floor.compareTo(NEEDED) >= 0, "requiresSpi " + floor + " is below " + NEEDED
                + ", the level of the newest SPI type the plugin links");
    }

    @Test
    void theFloorIsSatisfiedByTheSpiThePluginIsBuiltAndShippedWith() throws IOException {
        PluginVersion floor = descriptor().spiFloor();
        PluginVersion built = PluginVersion.parse(RunwaySpi.VERSION);
        assertTrue(floor.compareTo(built) <= 0, "requiresSpi " + floor + " is above this kernel's SPI " + built
                + ": the essential plugin would be refused by the kernel it ships with (fail-closed 503)");
    }
}
