package mx.quest.runway.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.SettingValidationException;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-instance widget config engine: two placements of one type carry independent config; typed coercion is
 * shared with the settings engines; blank=remove; unknown keys and other instances survive a save; secrets are
 * encrypted and kept on blank; a wholesale site event drops the cache; a corrupt file degrades to defaults.
 */
@SuppressWarnings("unchecked")
class WidgetInstanceConfigServiceTest {

    private static final String SITE = "acme";

    private WidgetInstanceConfigService service(Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return new WidgetInstanceConfigService(StorageLayout.from(props), fakeCipher());
    }

    private static Path file(Path external) {
        return external.resolve("sites").resolve(SITE).resolve("widget-instances.yml");
    }

    private static PluginSettingDef def(String key, String type, Object dflt, String range) {
        return new PluginSettingDef(key, type, dflt, "label." + key, range);
    }

    @Test
    void twoInstancesOfOneTypeAreConfiguredIndependently(@TempDir Path external) {
        WidgetInstanceConfigService svc = service(external);
        List<PluginSettingDef> schema = List.of(def("heading", "string", null, null));

        svc.save(SITE, "carousel", "home", schema, Map.of("heading", "Welcome"));
        svc.save(SITE, "carousel", "promos", schema, Map.of("heading", "Deals"));

        assertEquals("Welcome", svc.config(SITE, "carousel", "home").get("heading"));
        assertEquals("Deals", svc.config(SITE, "carousel", "promos").get("heading"));
        assertTrue(svc.config(SITE, "carousel", "absent").isEmpty(), "an unconfigured instance is empty");
    }

    @Test
    void valuesAreCoercedToTheirTypeAndRangeChecked(@TempDir Path external) {
        WidgetInstanceConfigService svc = service(external);
        List<PluginSettingDef> schema = List.of(def("interval", "int", 5, "1..60"));

        svc.save(SITE, "carousel", "home", schema, Map.of("interval", "12"));
        Object stored = svc.config(SITE, "carousel", "home").get("interval");
        assertTrue(stored instanceof Number, "int stored as a number, not text");
        assertEquals(12, ((Number) stored).intValue());

        SettingValidationException bad = assertThrows(SettingValidationException.class,
                () -> svc.save(SITE, "carousel", "home", schema, Map.of("interval", "999")));
        assertEquals("interval", bad.key());
        assertEquals(12, ((Number) svc.config(SITE, "carousel", "home").get("interval")).intValue(),
                "a rejected save writes nothing");
    }

    @Test
    void blankRemovesAKeyAndUnknownKeysAndOtherInstancesSurvive(@TempDir Path external) throws IOException {
        WidgetInstanceConfigService svc = service(external);
        List<PluginSettingDef> schema = List.of(def("heading", "string", null, null));

        // A file with a hand-added key in "home" and a second instance; both must survive a schema save.
        Files.createDirectories(file(external).getParent());
        Files.writeString(file(external), """
                carousel/home:
                  heading: Hi
                  legacy: kept
                carousel/promos:
                  heading: Keep me
                """, StandardCharsets.UTF_8);

        svc.save(SITE, "carousel", "home", schema, Map.of("heading", "")); // blank → remove heading
        Map<String, Object> home = svc.config(SITE, "carousel", "home");
        assertFalse(home.containsKey("heading"), "blank removed the declared key");
        assertEquals("kept", home.get("legacy"), "an unknown key in the instance is preserved");
        assertEquals("Keep me", svc.config(SITE, "carousel", "promos").get("heading"), "the other instance is untouched");
    }

    @Test
    void aSecretIsEncryptedAndKeptOnBlankResubmit(@TempDir Path external) throws IOException {
        WidgetInstanceConfigService svc = service(external);
        List<PluginSettingDef> schema = List.of(def("token", "secret", null, null));

        svc.save(SITE, "feed", "main", schema, Map.of("token", "s3cr3t"));
        String stored = String.valueOf(svc.config(SITE, "feed", "main").get("token"));
        assertTrue(stored.startsWith("ENC("), "a secret is stored encrypted, never plaintext");
        assertFalse(stored.contains("s3cr3t"));

        svc.save(SITE, "feed", "main", schema, Map.of("token", "")); // blank keeps the stored secret
        assertEquals(stored, String.valueOf(svc.config(SITE, "feed", "main").get("token")));
    }

    @Test
    void aWholesaleSiteEventDropsTheCache(@TempDir Path external) throws IOException {
        WidgetInstanceConfigService svc = service(external);
        List<PluginSettingDef> schema = List.of(def("heading", "string", null, null));
        svc.save(SITE, "carousel", "home", schema, Map.of("heading", "before"));
        assertEquals("before", svc.config(SITE, "carousel", "home").get("heading")); // now cached

        // A restore rewrites the file out-of-band; without an eviction the cache would serve the stale value.
        Files.writeString(file(external), "carousel/home:\n  heading: after\n");
        svc.onSiteChanged(new SiteChangedEvent(SITE, SiteChangedEvent.Kind.RESTORED));
        assertEquals("after", svc.config(SITE, "carousel", "home").get("heading"));
    }

    @Test
    void anAllDefaultInstanceLeavesNoFileBehind(@TempDir Path external) {
        WidgetInstanceConfigService svc = service(external);
        List<PluginSettingDef> schema = List.of(def("heading", "string", null, null));
        svc.save(SITE, "carousel", "home", schema, Map.of("heading", "")); // nothing set
        assertFalse(Files.exists(file(external)), "no configured values → no file");
        assertTrue(svc.config(SITE, "carousel", "home").isEmpty());
    }

    @Test
    void aCorruptFileDegradesToDefaults(@TempDir Path external) throws IOException {
        WidgetInstanceConfigService svc = service(external);
        Files.createDirectories(file(external).getParent());
        Files.writeString(file(external), "this: : not: valid: yaml: [");
        assertTrue(svc.config(SITE, "carousel", "home").isEmpty(), "a hand-corrupted file must not throw");
    }

    private static SecretCipher fakeCipher() {
        return new SecretCipher() {
            @Override public boolean isAvailable() {
                return true;
            }
            @Override public String encrypt(String plaintext) {
                return "ENC(" + new StringBuilder(plaintext).reverse() + ")";
            }
            @Override public String decrypt(String value) {
                return isEncrypted(value)
                        ? new StringBuilder(value.substring(4, value.length() - 1)).reverse().toString() : value;
            }
            @Override public boolean isEncrypted(String value) {
                return value != null && value.startsWith("ENC(") && value.endsWith(")");
            }
        };
    }

    /** What a widget receives has no nulls at any depth and is unmodifiable all the way down. */
    @Test
    void aHandWrittenConfigReachesTheWidgetDeepFrozenWithNullsDropped(@TempDir Path external) throws Exception {
        java.nio.file.Files.createDirectories(file(external).getParent());
        java.nio.file.Files.writeString(file(external), """
                carousel/home:
                  heading: Hi
                  gone:
                  slides:
                    - { title: One, link: null }
                    - null
                    - Two
                """);
        Map<String, Object> config = service(external).config(SITE, "carousel", "home");
        assertEquals("Hi", config.get("heading"));
        assertFalse(config.containsKey("gone"), "a null value is dropped: " + config);
        List<?> slides = (List<?>) config.get("slides");
        assertEquals(2, slides.size(), "a null list element is dropped");
        assertEquals(Map.of("title", "One"), slides.get(0), "nested nulls are dropped too");
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> config.put("x", 1));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, slides::clear);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) slides.get(0)).put("y", 2));
        // ...and the SPI view the widget actually receives can be built from it (Map.copyOf refuses nulls).
        new mx.quest.runway.spi.model.WidgetView(
                new mx.quest.runway.spi.model.SiteView("acme", "Acme", java.util.Locale.ENGLISH,
                        List.of(java.util.Locale.ENGLISH), null),
                new mx.quest.runway.spi.model.PageMeta("home", "Home", "", List.of(java.util.Locale.ENGLISH),
                        java.util.Locale.ENGLISH, true, false),
                java.util.Locale.ENGLISH, "home", false, config);
    }
}
