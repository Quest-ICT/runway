package mx.quest.runway.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import mx.quest.runway.storage.Yaml;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The "every setting in the Web UI" rule as a test: every bound {@code runway.*} setting is either a field the
 * console edits (declared in
 * {@code kernel-settings.yml}) or listed in {@code scripts/dev/settings-homes.yml} — edited on another screen, or
 * server-only with the reason written down. A new property that is in neither fails the build, so it cannot ship
 * without a home in the Web UI or a stated reason; an entry for a key that no longer exists fails too.
 */
class SettingsParityTest {

    private static final Path HOMES = Path.of("..", "scripts", "dev", "settings-homes.yml");

    /** Every property name in every configuration-metadata file on the classpath (Runway's and Spring Boot's). */
    private static Set<String> boundKeys() throws IOException {
        Set<String> names = new TreeSet<>();
        Enumeration<URL> files = SettingsParityTest.class.getClassLoader()
                .getResources("META-INF/spring-configuration-metadata.json");
        for (URL url : Collections.list(files)) {
            try (InputStream in = url.openStream()) {
                JsonNode root = JsonMapper.builder().build().readTree(in);
                if (root.has("properties")) {
                    root.get("properties").forEach(p -> names.add(p.get("name").asString()));
                }
            }
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> homes() throws IOException {
        try (InputStream in = Files.newInputStream(HOMES)) {
            return Yaml.read(in, Map.class);
        }
    }

    @Test
    void everyBoundRunwaySettingHasAHomeOrAReason() throws IOException {
        Map<String, Map<String, String>> homes = homes();
        Set<String> console = KernelSettings.all().keySet();
        List<String> homeless = new ArrayList<>();
        for (String key : boundKeys()) {
            if (key.startsWith("runway.") && !console.contains(key) && !homes.get("elsewhere").containsKey(key)
                    && !homes.get("host-only").containsKey(key)) {
                homeless.add(key);
            }
        }
        assertEquals(List.of(), homeless, "settings with no console field and no entry in " + HOMES
                + ": add each to kernel-settings.yml (gen-kernel-settings.py) or to settings-homes.yml with its reason");
    }

    @Test
    void everyEntryNamesARealSettingAndEachHasOneHome() throws IOException {
        Set<String> bound = boundKeys();
        Map<String, Map<String, String>> homes = homes();
        Set<String> console = KernelSettings.all().keySet();
        List<String> problems = new ArrayList<>();
        for (String list : List.of("elsewhere", "host-only")) {
            homes.get(list).forEach((key, text) -> {
                if (!bound.contains(key)) {
                    problems.add(list + ": " + key + " is not a bound setting");
                }
                if (console.contains(key)) {
                    problems.add(list + ": " + key + " is also a console field");
                }
                if (text == null || text.isBlank()) {
                    problems.add(list + ": " + key + " has no text");
                }
            });
        }
        for (String key : console) {
            if (!bound.contains(key)) {
                problems.add("kernel-settings.yml: " + key + " is not a bound setting");
            }
        }
        assertEquals(List.of(), problems);
        assertTrue(homes.get("host-only").size() > 30, "the host-only list was read");
    }

    /**
     * docs/SETTINGS-REFERENCE.md is generated (scripts/dev/gen-settings-reference.py) from the same sources; a key it
     * does not mention means someone changed a source and did not run the generator.
     */
    @Test
    void theSettingsReferenceMentionsEverySetting() throws IOException {
        String reference = Files.readString(Path.of("..", "docs", "SETTINGS-REFERENCE.md"));
        Map<String, Map<String, String>> homes = homes();
        List<String> missing = new ArrayList<>();
        List<String> keys = new ArrayList<>(KernelSettings.all().keySet());
        keys.addAll(homes.get("elsewhere").keySet());
        keys.addAll(homes.get("host-only").keySet());
        for (String key : keys) {
            if (!reference.contains("`" + key + "`")) {
                missing.add(key);
            }
        }
        assertEquals(List.of(), missing, "run python3 scripts/dev/gen-settings-reference.py");
    }
}
