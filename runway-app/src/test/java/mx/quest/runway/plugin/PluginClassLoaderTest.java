package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import mx.quest.runway.spi.HeadContributor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shared classloader is parent-first: a plugin's SPI impl resolves to the kernel's SAME Class object. */
class PluginClassLoaderTest {

    @Test
    void aPluginSpiImplIsAssignableToTheKernelInterface(@TempDir Path root) throws Exception {
        assumeTrue(PluginFixture.compilerAvailable(), "no in-JVM compiler (JRE-only) — skipping");
        PluginFixture.buildAlpha(root);
        List<LoadedPlugin> discovered = PluginLoader.discover(root);
        assertEquals(1, discovered.size());

        ClassLoader pluginCl = PluginLoader.classLoaderFor(discovered, getClass().getClassLoader());
        Class<?> alphaHead = Class.forName("fixtures.plugin.alpha.AlphaHead", false, pluginCl);

        // Parent-first: the plugin's HeadContributor is the kernel's HeadContributor, not a shadow copy.
        assertTrue(HeadContributor.class.isAssignableFrom(alphaHead), "must implement the kernel's HeadContributor");
        assertSame(HeadContributor.class, alphaHead.getInterfaces()[0], "the SPI interface is the same Class object");

        HeadContributor instance = (HeadContributor) alphaHead.getDeclaredConstructor().newInstance();
        assertEquals("alpha.head", instance.key());
    }
}
