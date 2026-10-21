package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.plugin.PluginSettingsService.RenderedSetting;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Typed validate/write of the app-level settings file: the complete key set, per-type coercion + range
 * checks, secret encryption/masking/keep-on-blank, {@code chmod 600}, stray-key drift, and pending-restart
 * drift vs the live environment. Uses a reversible fake {@link SecretCipher}, so the test does not depend on
 * a configured key.
 */
class PluginSettingsServiceTest {

    private static final String ID = "demo";

    private static PluginSettingsService service(Path external, SecretCipher cipher) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return new PluginSettingsService(StorageLayout.from(props), cipher);
    }

    private static Path settingsFile(Path external) {
        return external.resolve("plugin-settings").resolve(ID + ".yml");
    }

    private static PluginSettingDef def(String key, String type, Object dflt, String range) {
        return new PluginSettingDef(key, type, dflt, "label." + key, range);
    }

    private static String value(List<RenderedSetting> rendered, String key) {
        return rendered.stream().filter(r -> r.def().key().equals(key)).findFirst().orElseThrow().value();
    }

    /** A reversible, available cipher: {@code ENC(<reversed>)} — no real key involved. */
    private static SecretCipher fakeCipher(boolean available) {
        return new SecretCipher() {
            @Override public boolean isAvailable() {
                return available;
            }
            @Override public String encrypt(String plaintext) {
                if (!available) {
                    throw new IllegalStateException("no key");
                }
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
    void saveWritesTheCompleteKeySetTypedAndReadsBack(@TempDir Path external) {
        List<PluginSettingDef> schema = List.of(
                def("title", "string", null, null),
                def("max-items", "int", null, null),
                def("enabled", "boolean", null, null),
                def("mode", "enum", null, "fast|slow"),
                def("tags", "list", null, null));
        service(external, fakeCipher(true)).save(ID, schema, Map.of(
                "title", "Hello", "max-items", "42", "enabled", "on", "mode", "slow", "tags", "x, y\nz"));

        List<RenderedSetting> rendered = service(external, fakeCipher(true)).render(ID, schema);
        assertEquals("Hello", value(rendered, "title"));
        assertEquals("42", value(rendered, "max-items"));
        assertEquals("true", value(rendered, "enabled"));
        assertEquals("slow", value(rendered, "mode"));
        assertEquals("x, y, z", value(rendered, "tags"), "list joined for display");
    }

    @Test
    void aBlankNonSecretFallsBackToItsDefault(@TempDir Path external) {
        List<PluginSettingDef> schema = List.of(def("greeting", "string", "hi there", null));
        service(external, fakeCipher(true)).save(ID, schema, Map.of()); // nothing submitted
        assertEquals("hi there", value(service(external, fakeCipher(true)).render(ID, schema), "greeting"));
    }

    @Test
    void intRangeAndTypeAreValidated(@TempDir Path external) {
        List<PluginSettingDef> schema = List.of(def("n", "int", null, "1..100"));
        PluginSettingsService svc = service(external, fakeCipher(true));
        assertThrows(SettingValidationException.class, () -> svc.save(ID, schema, Map.of("n", "500")));
        assertThrows(SettingValidationException.class, () -> svc.save(ID, schema, Map.of("n", "abc")));
        svc.save(ID, schema, Map.of("n", "50")); // in range — fine
        assertEquals("50", value(svc.render(ID, schema), "n"));
    }

    @Test
    void anEnumValueMustBeAllowed(@TempDir Path external) {
        List<PluginSettingDef> schema = List.of(def("mode", "enum", null, "a, b"));
        assertThrows(SettingValidationException.class,
                () -> service(external, fakeCipher(true)).save(ID, schema, Map.of("mode", "c")));
    }

    @Test
    void aSecretIsEncryptedMaskedAndKeptOnBlank(@TempDir Path external) throws IOException {
        List<PluginSettingDef> schema = List.of(def("api-key", "secret", null, null));
        PluginSettingsService svc = service(external, fakeCipher(true));
        svc.save(ID, schema, Map.of("api-key", "s3cr3t"));

        String raw = Files.readString(settingsFile(external), StandardCharsets.UTF_8);
        assertFalse(raw.contains("s3cr3t"), "the plaintext secret is never written");
        assertTrue(raw.contains("ENC("), "stored encrypted");

        RenderedSetting secret = svc.render(ID, schema).get(0);
        assertEquals("", secret.value(), "a secret is never echoed to the form");
        assertTrue(secret.secretSet(), "but the form knows one is stored");

        // Re-saving with a blank secret keeps the stored one (you never retype it).
        svc.save(ID, schema, Map.of("api-key", ""));
        assertTrue(svc.render(ID, schema).get(0).secretSet(), "blank submit kept the secret");
        assertTrue(Files.readString(settingsFile(external)).contains("ENC("));
    }

    @Test
    void savingASecretWithoutAKeyIsAFieldError(@TempDir Path external) {
        List<PluginSettingDef> schema = List.of(def("api-key", "secret", null, null));
        SettingValidationException error = assertThrows(SettingValidationException.class,
                () -> service(external, fakeCipher(false)).save(ID, schema, Map.of("api-key", "x")));
        assertEquals("api-key", error.key());
    }

    @Test
    void aSecretBearingFileIsChmod600(@TempDir Path external) throws IOException {
        List<PluginSettingDef> schema = List.of(def("api-key", "secret", null, null));
        service(external, fakeCipher(true)).save(ID, schema, Map.of("api-key", "x"));
        Path file = settingsFile(external);
        try {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        } catch (UnsupportedOperationException nonPosix) {
            // non-POSIX filesystem — nothing to assert
        }
    }

    @Test
    void strayKeysAreReportedForDriftWarning(@TempDir Path external) throws IOException {
        Files.createDirectories(settingsFile(external).getParent());
        Files.writeString(settingsFile(external),
                "runway:\n  plugin:\n    demo:\n      known: a\n      stray: b\n");
        List<PluginSettingDef> schema = List.of(def("known", "string", null, null));
        assertEquals(List.of("stray"), service(external, fakeCipher(true)).strayKeys(ID, schema));
    }

    @Test
    void pendingRestartKeysCompareSavedAgainstLive(@TempDir Path external) {
        List<PluginSettingDef> schema = List.of(def("k", "string", null, null));
        PluginSettingsService svc = service(external, fakeCipher(true));
        svc.save(ID, schema, Map.of("k", "new"));

        Map<String, String> liveOld = Map.of("runway.plugin.demo.k", "old"); // not yet restarted
        assertEquals(List.of("k"), svc.pendingRestartKeys(ID, schema, liveOld::get));
        Map<String, String> liveNew = Map.of("runway.plugin.demo.k", "new"); // restarted
        assertTrue(svc.pendingRestartKeys(ID, schema, liveNew::get).isEmpty());
    }

    @Test
    void dottedKeysThatSharePrefixesDoNotCollide(@TempDir Path external) {
        // "a.b" and "a.b.c" would clash if each key were split into a nested tree; they are literal keys now.
        List<PluginSettingDef> schema = List.of(def("a.b", "string", null, null), def("a.b.c", "string", null, null));
        PluginSettingsService svc = service(external, fakeCipher(true));
        svc.save(ID, schema, Map.of("a.b", "one", "a.b.c", "two")); // must not ClassCast / 500
        List<RenderedSetting> rendered = svc.render(ID, schema);
        assertEquals("one", value(rendered, "a.b"));
        assertEquals("two", value(rendered, "a.b.c"));
    }

    @Test
    void aBareFileRendersDefaultsAndNoStrays(@TempDir Path external) {
        List<PluginSettingDef> schema = List.of(def("x", "string", "d", null));
        PluginSettingsService svc = service(external, fakeCipher(true));
        assertEquals("d", value(svc.render(ID, schema), "x"));
        assertTrue(svc.strayKeys(ID, schema).isEmpty());
    }
}
