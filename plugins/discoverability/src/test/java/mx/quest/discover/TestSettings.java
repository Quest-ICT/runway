package mx.quest.discover;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import org.yaml.snakeyaml.Yaml;

/**
 * Test settings shaped the way the kernel's {@code PluginSettings} hands them to the plugin: the {@code default:}s
 * this plugin's descriptor declares, overlaid by what a test stores for a site.
 *
 * <p>Every unit test builds its settings here rather than from an empty map, so each assertion also proves that the
 * declared defaults change nothing a tenant sees (1.x read only the stored values and applied its code fallbacks;
 * since 2.0.0 the host applies the declared defaults first).
 */
final class TestSettings {

    private TestSettings() {
    }

    /** A fake {@code PluginSettings} carrying the descriptor's declared per-site defaults and no stored value. */
    static FakePluginSettings declared() {
        return new FakePluginSettings().defaults(declaredDefaults());
    }

    /** A fake with NO declared defaults — what the plugin would see if the host stopped applying them. */
    static FakePluginSettings bare() {
        return new FakePluginSettings();
    }

    /** The {@code default:} of every {@code siteSettings} key that declares one (secret-typed keys never). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> declaredDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (Map<String, Object> def : (List<Map<String, Object>>) descriptor().get("siteSettings")) {
            if (def.containsKey("default") && def.get("default") != null && !"secret".equals(def.get("type"))) {
                defaults.put(String.valueOf(def.get("key")), def.get("default"));
            }
        }
        return defaults;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> descriptor() {
        try (Reader in = Files.newBufferedReader(Path.of("plugin.yml"), StandardCharsets.UTF_8)) {
            return (Map<String, Object>) new Yaml().load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
