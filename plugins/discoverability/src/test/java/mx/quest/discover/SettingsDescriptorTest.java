package mx.quest.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Keeps the settings form's layout honest. The kernel deliberately <b>tolerates</b> a broken presentation key —
 * an unknown group, a {@code showWhen} on a missing switch, a dangling {@code inheritsFrom}, an unresolved
 * message key — by quietly rendering the plain field, so a typo here would never fail anything at runtime: the
 * tenant would simply lose the section, the hiding or the help line. This test is where such a typo fails.
 *
 * <p>Reads {@code plugin.yml} as plain YAML (the plugin links only the SPI, so the kernel's descriptor parser is not
 * on the default test classpath); the {@code host-it} profile additionally parses it with the kernel's own
 * {@code PluginDescriptor} ({@code HostDescriptorTest}).
 */
class SettingsDescriptorTest {

    /** The plugin project root — the working directory when the module's tests run. */
    private static final Path ROOT = Path.of(System.getProperty("user.dir"));

    /** One setting definition as the YAML holds it. */
    private record Def(String key, String type, String group, String help, String label, String helpAnchor,
                       String showWhen, String inheritsFrom, String requiresPlatform, String range) {

        static Def of(Map<String, Object> raw) {
            return new Def(str(raw, "key"), str(raw, "type"), str(raw, "group"), str(raw, "help"), str(raw, "label"),
                    str(raw, "helpAnchor"), str(raw, "showWhen"), str(raw, "inheritsFrom"),
                    str(raw, "requiresPlatform"), str(raw, "range"));
        }

        private static String str(Map<String, Object> raw, String key) {
            Object value = raw.get(key);
            return value == null ? null : String.valueOf(value);
        }
    }

    private static Map<String, Object> descriptor() {
        return TestSettings.descriptor();
    }

    @SuppressWarnings("unchecked")
    private static List<Def> defs(String section) {
        List<Def> out = new ArrayList<>();
        Object list = descriptor().get(section);
        if (list != null) {
            for (Map<String, Object> raw : (List<Map<String, Object>>) list) {
                out.add(Def.of(raw));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groups() {
        return (List<Map<String, Object>>) descriptor().get("settingGroups");
    }

    private static Properties bundle(String name) throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(ROOT.resolve("i18n").resolve(name), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    private static Map<String, Def> byKey(List<Def> schema) {
        Map<String, Def> map = new HashMap<>();
        schema.forEach(def -> map.put(def.key(), def));
        return map;
    }

    private static List<Def> all() {
        List<Def> all = new ArrayList<>(defs("settings"));
        all.addAll(defs("siteSettings"));
        return all;
    }

    @Test
    void everyFieldIsInADeclaredSectionAndEverySectionIsUsed() {
        Set<String> groups = new TreeSet<>();
        groups().forEach(g -> groups.add(String.valueOf(g.get("id"))));
        Set<String> used = new TreeSet<>();
        for (Def def : all()) {
            assertTrue(groups.contains(def.group()), def.key() + " names an undeclared group '" + def.group() + "'");
            used.add(def.group());
        }
        assertEquals(groups, used, "a declared section with no fields would never show");
        assertEquals(6, groups.size(), "the six sections of the 1.6.0 layout");
    }

    @Test
    void everyDependencyPointsAtARealSwitch() {
        for (List<Def> schema : List.of(defs("settings"), defs("siteSettings"))) {
            Map<String, Def> keys = byKey(schema);
            for (Def def : schema) {
                if (def.showWhen() != null) {
                    Def gate = keys.get(def.showWhen());
                    assertTrue(gate != null && "boolean".equals(gate.type()),
                            def.key() + ": showWhen must name a boolean in the same schema, got " + def.showWhen());
                }
                if (def.inheritsFrom() != null) {
                    String ref = def.inheritsFrom();
                    Def source = ref.startsWith("app:")
                            ? byKey(defs("settings")).get(ref.substring(4)) : keys.get(ref);
                    assertTrue(source != null, def.key() + ": inheritsFrom names nothing: " + ref);
                    assertTrue(def.range().contains("inherit"), def.key() + ": inheritsFrom without an inherit choice");
                }
            }
        }
        for (Def def : defs("siteSettings")) {
            if (def.requiresPlatform() != null) {
                Def platform = byKey(defs("settings")).get(def.requiresPlatform());
                assertTrue(platform != null && "boolean".equals(platform.type()),
                        def.key() + ": requiresPlatform must name an app-level boolean");
            }
        }
        assertEquals("app:robots.ai-default", byKey(defs("siteSettings")).get("robots.ai-policy").inheritsFrom(),
                "the AI policy still inherits the platform default");
        assertEquals("indexnow.enabled", byKey(defs("siteSettings")).get("indexnow.enabled").requiresPlatform());
    }

    @Test
    void everyFieldHasAHelpLineAndEveryKeyResolvesInEnglishAndSpanish() throws IOException {
        List<String> keys = new ArrayList<>();
        for (Def def : all()) {
            assertNotNull(def.help(), def.key() + " has no help line");
            keys.add(def.label());
            keys.add(def.help());
        }
        for (Map<String, Object> g : groups()) {
            keys.add(String.valueOf(g.get("label")));
            keys.add(String.valueOf(g.get("help")));
        }
        Properties en = bundle("messages.properties");
        Properties es = bundle("messages_es.properties");
        for (String key : keys) {
            assertTrue(en.containsKey(key), "missing in messages.properties: " + key);
            assertTrue(es.containsKey(key), "missing in messages_es.properties: " + key);
        }
        assertEquals(new TreeSet<>(en.stringPropertyNames()), new TreeSet<>(es.stringPropertyNames()),
                "the English and Spanish bundles carry the same keys");
    }

    @Test
    void everyLearnMoreLinkLandsOnASectionOfTheHelpPage() throws IOException {
        assertEquals("/__plugin/discoverability/static/discoverability-help.html", descriptor().get("helpUrl"));
        String page = Files.readString(ROOT.resolve("static").resolve("discoverability-help.html"));
        List<String> anchors = new ArrayList<>();
        groups().forEach(g -> anchors.add(g.get("helpAnchor") == null ? null : String.valueOf(g.get("helpAnchor"))));
        all().forEach(def -> anchors.add(def.helpAnchor()));
        for (String anchor : anchors) {
            if (anchor != null) {
                assertTrue(page.contains("id=\"" + anchor + "\""), "the help page has no #" + anchor);
            }
        }
    }
}
