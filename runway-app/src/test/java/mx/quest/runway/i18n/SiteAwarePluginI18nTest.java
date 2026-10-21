package mx.quest.runway.i18n;

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
import java.util.Optional;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginRuntime;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

/** Public/site realm: a plugin's i18n bundle resolves against the bound site's source, but the site wins. */
class SiteAwarePluginI18nTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PluginRuntime> provider(PluginRuntime runtime) {
        ObjectProvider<PluginRuntime> p = mock(ObjectProvider.class);
        when(p.getIfAvailable(any())).thenReturn(runtime);
        return p;
    }

    @Test
    void aPluginKeyResolvesForTheBoundSiteButTheSiteWins(@TempDir Path external) throws IOException {
        // Site bundle: its own key + a key it shares with the plugin (the site must win the shared one).
        Path siteI18n = external.resolve("sites").resolve("acme").resolve("i18n");
        Files.createDirectories(siteI18n);
        Files.writeString(siteI18n.resolve("messages.properties"),
                "site.only=Site value\nshared.key=SITE-WINS\n", StandardCharsets.UTF_8);
        // Plugin bundle: a plugin-only key + the shared key (must lose).
        Path pluginI18n = external.resolve("plugins").resolve("demo").resolve("i18n");
        Files.createDirectories(pluginI18n);
        Files.writeString(pluginI18n.resolve("messages.properties"),
                "runwaydemo.hello=Hello from the plugin\nshared.key=PLUGIN-LOSES\n", StandardCharsets.UTF_8);

        PluginDescriptor descriptor = PluginDescriptor.read(new ByteArrayInputStream(
                "id: demo\nname: Demo\nversion: 1.0.0\n".getBytes(StandardCharsets.UTF_8)));
        PluginRuntime runtime = new PluginRuntime(List.of(new LoadedPlugin(descriptor, pluginI18n.getParent())),
                getClass().getClassLoader(), Map.of(), false);

        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);

        SiteResolver resolver = mock(SiteResolver.class);
        when(resolver.current()).thenReturn(Optional.of(new Site("acme", "Acme", List.of("acme.test"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE)));

        SiteAwareMessageSource source = new SiteAwareMessageSource(resolver, layout, new I18nProperties(),
                provider(runtime));

        assertEquals("Hello from the plugin", source.getMessage("runwaydemo.hello", null, "def", Locale.ENGLISH),
                "a plugin key resolves via its bundle in the site realm");
        assertEquals("Site value", source.getMessage("site.only", null, "def", Locale.ENGLISH));
        assertEquals("SITE-WINS", source.getMessage("shared.key", null, "def", Locale.ENGLISH),
                "the site's own bundle wins over a plugin key");
        assertEquals("fallback", source.getMessage("no.such.key", null, "fallback", Locale.ENGLISH));
    }
}
