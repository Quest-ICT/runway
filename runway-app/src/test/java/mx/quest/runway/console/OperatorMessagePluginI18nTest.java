package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.i18n.I18nProperties;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginRuntime;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

/** Plugin i18n seam: a plugin's own i18n/ bundle contributes keys to the console, but can never override core. */
class OperatorMessagePluginI18nTest {

    @SuppressWarnings("unchecked")
    private static OperatorMessageSource sourceWith(Path external, PluginRuntime runtime) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        ObjectProvider<PluginRuntime> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(runtime);
        return new OperatorMessageSource(layout, new I18nProperties(), provider);
    }

    private static LoadedPlugin plugin(String id, Path installDir) {
        PluginDescriptor descriptor = PluginDescriptor.read(new ByteArrayInputStream(
                ("id: " + id + "\nname: Demo\nversion: 1.0.0\n").getBytes(StandardCharsets.UTF_8)));
        return new LoadedPlugin(descriptor, installDir);
    }

    @Test
    void aPluginBundleContributesKeysButNeverOverridesCore(@TempDir Path external) throws IOException {
        Path pluginDir = external.resolve("plugins").resolve("demo");
        Path i18n = pluginDir.resolve("i18n");
        Files.createDirectories(i18n);
        // A NEW key (plugin-owned) and an attempt to hijack a real CORE key.
        Files.writeString(i18n.resolve("messages.properties"),
                "demo.hello=Hello from the plugin\nop.nav.settings=HIJACKED\n", StandardCharsets.UTF_8);

        PluginRuntime runtime = new PluginRuntime(List.of(plugin("demo", pluginDir)),
                getClass().getClassLoader(), Map.of(), false);
        OperatorMessageSource source = sourceWith(external, runtime);

        assertEquals("Hello from the plugin", source.getMessage("demo.hello", null, Locale.ENGLISH),
                "a plugin-owned key resolves from the plugin bundle");
        assertEquals("Settings", source.getMessage("op.nav.settings", null, Locale.ENGLISH),
                "core wins — a plugin cannot override a core key");
    }

    @Test
    void aPluginLocaleBundleResolvesForThatLocale(@TempDir Path external) throws IOException {
        Path i18n = external.resolve("plugins").resolve("demo").resolve("i18n");
        Files.createDirectories(i18n);
        Files.writeString(i18n.resolve("messages.properties"), "demo.hi=Hi\n", StandardCharsets.UTF_8);
        Files.writeString(i18n.resolve("messages_es.properties"), "demo.hi=Hola\n", StandardCharsets.UTF_8);

        PluginRuntime runtime = new PluginRuntime(List.of(plugin("demo", external.resolve("plugins").resolve("demo"))),
                getClass().getClassLoader(), Map.of(), false);
        OperatorMessageSource source = sourceWith(external, runtime);

        assertEquals("Hola", source.getMessage("demo.hi", null, new Locale("es")));
        assertEquals("Hi", source.getMessage("demo.hi", null, Locale.ENGLISH));
    }

    @Test
    void noPluginsLeavesCoreResolutionIntact(@TempDir Path external) {
        OperatorMessageSource source = sourceWith(external,
                PluginRuntime.empty(getClass().getClassLoader()));
        assertEquals("Settings", source.getMessage("op.nav.settings", null, Locale.ENGLISH));
        assertEquals("no.such.key", source.getMessage("no.such.key", null, Locale.ENGLISH),
                "a missing key renders the code (useCodeAsDefaultMessage)");
    }
}
