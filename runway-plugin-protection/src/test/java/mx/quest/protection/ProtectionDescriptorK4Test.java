package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginResolution;
import mx.quest.runway.plugin.PluginVersion;
import mx.quest.runway.spi.RunwaySpi;
import org.junit.jupiter.api.Test;

/**
 * The shipped descriptor of the essential plugin against the SPI 1.0.0 loader rules: it is
 * on the kernel's version line and declares the SPI major's floor ({@code >=1.0.0}, met by every 1.x kernel), so the
 * mandatory floor can never block it — a blocked essential plugin would put the whole platform in fail-closed mode.
 */
class ProtectionDescriptorK4Test {

    private static PluginDescriptor shipped() throws IOException {
        try (InputStream in = Files.newInputStream(Path.of("plugin.yml"))) { // the module dir (surefire's CWD)
            PluginDescriptor descriptor = PluginDescriptor.read(in);
            descriptor.validate();
            return descriptor;
        }
    }

    @Test
    void theShippedDescriptorIsOnTheKernelsLineAndDeclaresTheKernelsSpi() throws IOException {
        PluginDescriptor descriptor = shipped();
        assertEquals("protection", descriptor.id());
        assertTrue(descriptor.isEssential());
        assertEquals("1.1.0", descriptor.version(), "versioned on the kernel's line");
        assertEquals(PluginVersion.parse("1.0.0"), descriptor.spiFloor());
        assertEquals(PluginVersion.parse(RunwaySpi.VERSION).major(), descriptor.spiFloor().major(),
                "the floor names the kernel's own SPI major line");
    }

    @Test
    void theRunningLoaderAcceptsIt() throws IOException {
        PluginResolution.Result result = PluginResolution.resolve(
                List.of(new LoadedPlugin(shipped(), Path.of("."))));
        assertTrue(result.blocked().isEmpty(), "never blocked by the floor rules: " + result.blocked());
        assertEquals(1, result.loadable().size());
    }
}
