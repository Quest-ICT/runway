package mx.quest.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.SitePluginConfigService;
import mx.quest.runway.plugin.spi.PluginSettingsImpl;
import org.junit.jupiter.api.Test;

/**
 * host-it: the descriptor through the KERNEL's own parser, and the settings the kernel's {@code PluginSettingsImpl}
 * hands this plugin compared with the fake every unit test uses ({@code TestSettings}) — so the unit tests' premise
 * (declared defaults overlaid by stored values) is the kernel's behaviour, not an assumption.
 */
class HostDescriptorTest {

    private static PluginDescriptor descriptor() throws IOException {
        try (InputStream in = Files.newInputStream(Path.of("plugin.yml"))) {
            return PluginDescriptor.read(in);
        }
    }

    @Test
    void theKernelParsesTheDescriptorWithEveryLayoutKey() throws IOException {
        PluginDescriptor d = descriptor();
        assertEquals("discoverability", d.id());
        // The release number is asserted once, in DiscoverabilityConfigTest (the default build); here the kernel's
        // parse is compared with the descriptor, so a version bump does not have to remember this profile's test.
        assertEquals(String.valueOf(TestSettings.descriptor().get("version")), d.version());
        assertEquals(6, d.settingGroups().size());
        Map<String, PluginSettingDef> site = new LinkedHashMap<>();
        d.siteSettings().forEach(def -> site.put(def.key(), def));
        assertEquals("app:robots.ai-default", site.get("robots.ai-policy").inheritsFrom());
        assertEquals("indexnow.enabled", site.get("indexnow.enabled").requiresPlatform());
        assertEquals("jsonld.enabled", site.get("jsonld.type").showWhen());
        assertTrue(site.get("content.schema").advanced());
        for (PluginSettingDef def : d.siteSettings()) {
            assertNotNull(def.group(), def.key());
            assertNotNull(def.help(), def.key());
        }
    }

    @Test
    void theKernelsSettingsMergeIsTheOneTheUnitTestsAssume() throws IOException {
        List<PluginSettingDef> schema = descriptor().siteSettings();
        for (Map<String, Object> stored : List.<Map<String, Object>>of(Map.of(),
                Map.of("jsonld.type", "", "geo.enabled", true, "geo.speakable", List.of("h1"), "org.name", "Acme",
                        "unknown.key", "kept"))) {
            SitePluginConfigService config = mock(SitePluginConfigService.class);
            when(config.config(anyString(), eq("discoverability"))).thenReturn(stored);
            PluginSettingsImpl kernel = new PluginSettingsImpl("discoverability", schema, config, null);
            assertEquals(TestSettings.declared().site("acme", stored).site("acme").values(),
                    kernel.site("acme").values(), "stored: " + stored);
        }
    }
}
