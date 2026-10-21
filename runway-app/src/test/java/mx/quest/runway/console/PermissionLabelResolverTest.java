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

/** The permissions matrix resolves permission KEYS to human LABELS (plugin label / core op.perm.* / key fallback). */
class PermissionLabelResolverTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PluginRuntime> provider(PluginRuntime runtime) {
        ObjectProvider<PluginRuntime> p = mock(ObjectProvider.class);
        when(p.getIfAvailable(any())).thenReturn(runtime);
        return p;
    }

    @Test
    void resolvesPluginLabel_coreConvention_andKeyFallback(@TempDir Path external) throws IOException {
        // A plugin declaring a permission whose label key it ships in its own i18n bundle.
        Path pluginDir = external.resolve("plugins").resolve("demo");
        Files.createDirectories(pluginDir.resolve("i18n"));
        Files.writeString(pluginDir.resolve("i18n").resolve("messages.properties"),
                "runwaydemo.perm.manageNotes=Manage notes\n", StandardCharsets.UTF_8);
        PluginDescriptor descriptor = PluginDescriptor.read(new ByteArrayInputStream(("""
                id: demo
                name: Demo
                version: 1.0.0
                permissions:
                  - { id: manage-notes, realm: tenant, label: runwaydemo.perm.manageNotes }
                """).getBytes(StandardCharsets.UTF_8)));
        PluginRuntime runtime = new PluginRuntime(List.of(new LoadedPlugin(descriptor, pluginDir)),
                getClass().getClassLoader(), Map.of(), false);

        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        OperatorMessageSource messages = new OperatorMessageSource(layout, new I18nProperties(), provider(runtime));
        PermissionLabelResolver resolver = new PermissionLabelResolver(messages, provider(runtime));

        assertEquals("Manage notes", resolver.label("demo|manage-notes", Locale.ENGLISH),
                "a plugin permission resolves via the plugin's own bundle");
        assertEquals("Maintenance mode", resolver.label("MAINTENANCE_MODE", Locale.ENGLISH),
                "a core permission resolves via the op.perm.<KEY> convention");
        assertEquals("x|y", resolver.label("x|y", Locale.ENGLISH),
                "an unknown key falls back to the raw permission key");
        assertEquals("—", resolver.joinHeld(List.of(), Locale.ENGLISH));
        assertEquals("Maintenance mode, Manage notes",
                resolver.joinHeld(List.of("MAINTENANCE_MODE", "demo|manage-notes"), Locale.ENGLISH));
    }
}
