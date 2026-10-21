package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The per-tenant config engine: immediate apply (cache evict + change event), preserved unknown keys,
 * blank=remove, default-valued booleans omitted, secret encryption with keep-on-blank.
 */
class SitePluginConfigServiceTest {

    private static final String SITE = "acme";
    private static final String PLUGIN = "demo";

    private final List<Object> events = new ArrayList<>();

    private SitePluginConfigService service(Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        ApplicationEventPublisher publisher = events::add;
        return new SitePluginConfigService(StorageLayout.from(props), fakeCipher(), publisher);
    }

    private static Path file(Path external) {
        return external.resolve("sites").resolve(SITE).resolve("plugins").resolve(PLUGIN + ".yml");
    }

    private static PluginSettingDef def(String key, String type, Object dflt, String range) {
        return new PluginSettingDef(key, type, dflt, "label." + key, range);
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
                return isEncrypted(value) ? new StringBuilder(value.substring(4, value.length() - 1)).reverse().toString() : value;
            }
            @Override public boolean isEncrypted(String value) {
                return value != null && value.startsWith("ENC(") && value.endsWith(")");
            }
        };
    }

    @Test
    void savingWritesTypedValuesAppliesImmediatelyAndPublishes(@TempDir Path external) {
        SitePluginConfigService svc = service(external);
        List<PluginSettingDef> schema = List.of(def("greeting", "string", null, null), def("max", "int", null, "1..100"));
        svc.save(SITE, PLUGIN, schema, Map.of("greeting", "hello", "max", "42"));

        Map<String, Object> config = svc.config(SITE, PLUGIN);
        assertEquals("hello", config.get("greeting"));
        assertEquals(42L, ((Number) config.get("max")).longValue());
        assertEquals(1, events.size(), "a change event was published (immediate apply)");
        assertTrue(events.get(0) instanceof SitePluginConfigChangedEvent);
    }

    @Test
    void aHandCorruptedConfigFileDegradesToEmptyInsteadOf500(@TempDir Path external) throws IOException {
        // A malformed YAML file must not 500 every read/save for this plugin — it degrades to "no config"
        // (plugin defaults), matching SeoConfigService, so the tenant's plugin keeps working.
        Files.createDirectories(file(external).getParent());
        Files.writeString(file(external), "greeting: : : not valid yaml :\n\t- broken\n", StandardCharsets.UTF_8);
        SitePluginConfigService svc = service(external);
        assertTrue(svc.config(SITE, PLUGIN).isEmpty(), "a corrupt config reads back empty, not throwing");
    }

    @Test
    void aListSettingRoundTripsWithoutAccumulatingBrackets(@TempDir Path external) {
        // Regression: a list field (e.g. geo.speakable, org.sameas) used to render its stored List via
        // String.valueOf → "[h1, main, article]", which then re-split on save and grew another bracket pair
        // each time ("[[…]]"). The form must render a list comma-joined and re-saving it must be stable.
        SitePluginConfigService svc = service(external);
        List<PluginSettingDef> schema = List.of(def("geo.speakable", "list", "h1,main", null));

        svc.save(SITE, PLUGIN, schema, Map.of("geo.speakable", "h1, main, article"));
        String rendered = svc.render(SITE, PLUGIN, schema).get(0).value();
        assertEquals("h1, main, article", rendered, "a list renders comma-joined, with no brackets");
        assertTrue(svc.config(SITE, PLUGIN).get("geo.speakable") instanceof List, "stored as a real YAML list");

        // Re-saving exactly what the form showed must NOT wrap it again.
        svc.save(SITE, PLUGIN, schema, Map.of("geo.speakable", rendered));
        assertEquals("h1, main, article", svc.render(SITE, PLUGIN, schema).get(0).value(), "stable after re-save");
        svc.save(SITE, PLUGIN, schema, Map.of("geo.speakable", svc.render(SITE, PLUGIN, schema).get(0).value()));
        assertEquals("h1, main, article", svc.render(SITE, PLUGIN, schema).get(0).value(), "stable after 3rd save");
    }

    @Test
    void unknownHandEditedKeysArePreserved(@TempDir Path external) throws IOException {
        Files.createDirectories(file(external).getParent());
        Files.writeString(file(external), "greeting: hi\nlegacyKey: keep-me\n", StandardCharsets.UTF_8);
        SitePluginConfigService svc = service(external);
        svc.save(SITE, PLUGIN, List.of(def("greeting", "string", null, null)), Map.of("greeting", "bye"));

        String saved = Files.readString(file(external));
        assertTrue(saved.contains("bye"), "the schema key was updated");
        assertTrue(saved.contains("legacyKey"), "an unknown key the schema does not declare is preserved");
    }

    @Test
    void aBlankNonSecretRemovesTheKey(@TempDir Path external) {
        SitePluginConfigService svc = service(external);
        List<PluginSettingDef> schema = List.of(def("greeting", "string", null, null));
        svc.save(SITE, PLUGIN, schema, Map.of("greeting", "hi"));
        assertEquals("hi", svc.config(SITE, PLUGIN).get("greeting"));

        svc.save(SITE, PLUGIN, schema, Map.of("greeting", "")); // blank → remove → plugin default
        assertNull(svc.config(SITE, PLUGIN).get("greeting"), "a blank value removed the key");
    }

    @Test
    void aDefaultValuedBooleanIsOmittedButAnOverrideIsWritten(@TempDir Path external) {
        SitePluginConfigService svc = service(external);
        List<PluginSettingDef> schema = List.of(def("flag", "boolean", true, null)); // default true

        svc.save(SITE, PLUGIN, schema, Map.of("flag", "false")); // off, != default → written
        assertEquals(Boolean.FALSE, svc.config(SITE, PLUGIN).get("flag"));

        svc.save(SITE, PLUGIN, schema, Map.of("flag", "true")); // == default → omitted
        assertNull(svc.config(SITE, PLUGIN).get("flag"), "a default-valued boolean is not written");
    }

    @Test
    void aSecretIsEncryptedAndKeptOnBlank(@TempDir Path external) throws IOException {
        SitePluginConfigService svc = service(external);
        List<PluginSettingDef> schema = List.of(def("token", "secret", null, null));

        svc.save(SITE, PLUGIN, schema, Map.of("token", "s3cr3t"));
        String afterSet = Files.readString(file(external));
        assertTrue(afterSet.contains("ENC("), "the secret is stored encrypted");
        assertFalse(afterSet.contains("s3cr3t"), "the plaintext is never written");

        svc.save(SITE, PLUGIN, schema, Map.of("token", "")); // blank → keep
        assertTrue(Files.readString(file(external)).contains("ENC("), "a blank secret on resave kept the stored one");
    }

    @Test
    void anOutOfRangeIntIsAFieldError(@TempDir Path external) {
        SitePluginConfigService svc = service(external);
        assertThrows(SettingValidationException.class,
                () -> svc.save(SITE, PLUGIN, List.of(def("max", "int", null, "1..100")), Map.of("max", "500")));
    }

    /**
     * Pins the per-site plugin-config path as {@code <site>/plugins/<id>.yml}.
     *
     * <p>This layout is no longer private: a plugin declaring its head inputs
     * ({@code HeadContributor#headInputs}) must name the very file this service reads, so that editing a
     * per-site setting moves each page's ETag. It names it as a <b>literal string</b> — a plugin cannot call
     * this method, since {@code headInputs} is answered without a {@link StorageLayout}.
     *
     * <p>So the two are coupled by a convention rather than by a shared symbol, and the failure is silent in
     * the worse direction: a declared path that does not match this one folds a stable "absent" marker
     * forever, and the validator goes on looking correct while covering nothing. Changing this layout means
     * updating every plugin that declares it.
     */
    @Test
    void thePerSitePluginConfigLivesWhereDeclaringPluginsSayItDoes(@TempDir Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);

        Path file = layout.sitePluginConfigFile("acme", "discoverability");

        assertEquals("plugins/discoverability.yml",
                layout.siteDir("acme").relativize(file).toString().replace('\\', '/'),
                "plugins declare this path as a literal; if the layout moves, they all have to move with it");
    }
}
