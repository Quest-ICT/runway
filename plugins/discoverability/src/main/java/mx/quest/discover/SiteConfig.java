package mx.quest.discover;

import java.util.List;
import java.util.Map;

/**
 * Null/absent-safe typed readers over this plugin's effective per-site settings map — the
 * {@code SettingsView.values()} that {@code PluginSettings.site(siteId)} returns: the descriptor's
 * {@code siteSettings} defaults overlaid by the values stored for the site, each already coerced by the host to
 * its declared type (boolean→Boolean, int→Long, list→List&lt;String&gt;, enum/string/text→String).
 *
 * <p>Kept instead of the {@code SettingsView} accessors on purpose: these are the readers 1.x used, and their
 * lenient rules for a hand-edited value (any string other than true/on/yes/1 reads as {@code false}; a blank value
 * falls back) are part of what an unchanged site renders. The code fallbacks passed in equal the descriptor's
 * declared defaults ({@code DescriptorDefaultsTest} proves the rendered surfaces do not change when the defaults
 * are applied), so they matter only for a key the descriptor declares without a default.
 */
final class SiteConfig {

    private SiteConfig() {
    }

    static boolean bool(Map<String, Object> cfg, String key, boolean def) {
        Object v = cfg.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s && !s.isBlank()) {
            return s.equalsIgnoreCase("true") || s.equalsIgnoreCase("on") || s.equalsIgnoreCase("yes") || s.equals("1");
        }
        return def;
    }

    static String str(Map<String, Object> cfg, String key, String def) {
        Object v = cfg.get(key);
        return (v == null || v.toString().isBlank()) ? def : v.toString();
    }

    @SuppressWarnings("unchecked")
    static List<String> list(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        if (v instanceof List<?> l) {
            return l.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isBlank()).toList();
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s.split("[,\\n]")).stream().map(String::trim).filter(x -> !x.isBlank()).toList();
        }
        return List.of();
    }
}
