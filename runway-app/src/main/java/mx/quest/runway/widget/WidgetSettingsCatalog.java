package mx.quest.runway.widget;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.RunningPlugins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The read model that answers "what per-instance settings does content-widget {@code <type>} declare?" — the
 * kernel half of the widget-config seam. A plugin declares, in its {@code plugin.yml}, a
 * {@code widgetSettings:} block keyed by each widget's {@link mx.quest.runway.spi.ContentWidget#key() type key};
 * this catalog merges those blocks across every loaded plugin so the tenant <b>Content widgets</b> screen can
 * render a typed editor for any placed instance.
 *
 * <p>Reads the descriptors from {@link RunningPlugins} on demand (they are frozen for the boot, so no caching is
 * needed) rather than at construction, which keeps it independent of plugin-load ordering. A widget's type key
 * is globally unique (the {@link ContentWidgetRegistry} fails fast on a duplicate), so a type maps to exactly one
 * schema; the defensive last-wins + warn below only guards the impossible-by-construction collision.
 *
 * <p>Thread-safe: it holds no mutable state and rebuilds its view from the immutable descriptor list per call.
 */
@Component
public class WidgetSettingsCatalog {

    private static final Logger log = LoggerFactory.getLogger(WidgetSettingsCatalog.class);

    private final RunningPlugins runningPlugins;

    public WidgetSettingsCatalog(RunningPlugins runningPlugins) {
        this.runningPlugins = runningPlugins;
    }

    /**
     * The declared per-instance settings schema for one widget type, or an empty list when the type declares
     * none (or no such widget is installed) — in which case an instance of that type is shown as placed but not
     * configurable.
     *
     * @param widgetType the {@code <type>} half of a {@code data-widget} anchor (never {@code null})
     * @return the schema (never {@code null}; empty when the type declares no per-instance settings)
     */
    public List<PluginSettingDef> schemaFor(String widgetType) {
        if (widgetType == null) {
            return List.of();
        }
        for (LoadedPlugin loaded : runningPlugins.installed()) {
            List<PluginSettingDef> schema = loaded.descriptor().widgetSettings().get(widgetType);
            if (schema != null) {
                return schema;
            }
        }
        return List.of();
    }

    /** Whether the given widget type declares any per-instance settings (i.e. its placement is configurable). */
    public boolean isConfigurable(String widgetType) {
        return !schemaFor(widgetType).isEmpty();
    }

    /**
     * Every widget type that declares a per-instance schema this boot, mapped to that schema. Built fresh from
     * the frozen descriptor list; the first declaration of a type wins (a warning fires on the — impossible by
     * the unique-key rule — duplicate).
     */
    public Map<String, List<PluginSettingDef>> all() {
        Map<String, List<PluginSettingDef>> merged = new LinkedHashMap<>();
        for (LoadedPlugin loaded : runningPlugins.installed()) {
            loaded.descriptor().widgetSettings().forEach((type, schema) -> {
                List<PluginSettingDef> existing = merged.putIfAbsent(type, schema);
                if (existing != null) {
                    log.warn("two plugins declare widgetSettings for widget type '{}'; keeping the first", type);
                }
            });
        }
        return Map.copyOf(merged);
    }
}
