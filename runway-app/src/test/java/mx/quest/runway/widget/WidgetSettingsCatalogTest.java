package mx.quest.runway.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.RunningPlugins;
import org.junit.jupiter.api.Test;

/** The catalog merges per-widget-type schemas from loaded descriptors and answers "is this type configurable?". */
class WidgetSettingsCatalogTest {

    private static PluginDescriptor descriptor(String id, Map<String, List<PluginSettingDef>> widgetSettings) {
        return new PluginDescriptor(id, id, "1.0.0", null, null, null, null, null,
                List.of(), List.of(), List.of(), widgetSettings, List.of(), List.of(), null);
    }

    private static PluginSettingDef def(String key, String type) {
        return new PluginSettingDef(key, type, null, "label." + key, null);
    }

    private static RunningPlugins running(PluginDescriptor... descriptors) {
        List<LoadedPlugin> loaded = java.util.Arrays.stream(descriptors)
                .map(d -> new LoadedPlugin(d, Path.of("/tmp/" + d.id())))
                .toList();
        return () -> loaded;
    }

    @Test
    void schemaForReturnsTheDeclaringPluginsSchema() {
        WidgetSettingsCatalog catalog = new WidgetSettingsCatalog(running(
                descriptor("carousel-manager", Map.of("carousel", List.of(def("interval", "int")))),
                descriptor("search", Map.of("search", List.of(def("placeholder", "string"))))));

        assertEquals(1, catalog.schemaFor("carousel").size());
        assertEquals("interval", catalog.schemaFor("carousel").get(0).key());
        assertTrue(catalog.isConfigurable("search"));
        assertFalse(catalog.isConfigurable("unknown-type"), "a type no plugin declares is not configurable");
        assertTrue(catalog.schemaFor(null).isEmpty());
    }

    @Test
    void allMergesEveryDeclaredType() {
        WidgetSettingsCatalog catalog = new WidgetSettingsCatalog(running(
                descriptor("a", Map.of("carousel", List.of(def("x", "string")))),
                descriptor("b", Map.of("gallery", List.of(def("y", "int"))))));

        Map<String, List<PluginSettingDef>> all = catalog.all();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("carousel"));
        assertTrue(all.containsKey("gallery"));
    }
}
