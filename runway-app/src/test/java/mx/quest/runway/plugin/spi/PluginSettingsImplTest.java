package mx.quest.runway.plugin.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.SitePluginConfigChangedEvent;
import mx.quest.runway.plugin.SitePluginConfigService;
import mx.quest.runway.spi.model.SettingsView;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link PluginSettingsImpl}: defaults, precedence, secrets (with and without ENC), freshness, isolation. */
class PluginSettingsImplTest {

    @TempDir
    Path external;
    private StorageLayout layout;
    private SitePluginConfigService config;
    private boolean cipherAvailable;
    private boolean cipherBroken;

    private final SecretCipher cipher = new SecretCipher() {
        @Override
        public boolean isAvailable() {
            return cipherAvailable;
        }

        @Override
        public String encrypt(String plaintext) {
            return "ENC(" + new StringBuilder(plaintext).reverse() + ")";
        }

        @Override
        public String decrypt(String value) {
            if (cipherBroken) {
                throw new IllegalStateException("bad tag for ENC(secret-ciphertext)");
            }
            return isEncrypted(value) ? new StringBuilder(value.substring(4, value.length() - 1)).reverse().toString()
                    : value;
        }

        @Override
        public boolean isEncrypted(String value) {
            return value != null && value.startsWith("ENC(") && value.endsWith(")");
        }
    };

    private static final List<PluginSettingDef> SCHEMA = List.of(
            new PluginSettingDef("enabled", "boolean", true, "l", null),
            new PluginSettingDef("limit", "int", 20, "l", "1..100"),
            new PluginSettingDef("title", "string", "Hello", "l", null),
            new PluginSettingDef("note", "text", null, "l", null),
            new PluginSettingDef("smtpPassword", "secret", null, "l", null));

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        layout = StorageLayout.from(props);
        config = new SitePluginConfigService(layout, cipher, event -> { });
        cipherAvailable = true;
        cipherBroken = false;
    }

    private void store(String site, String plugin, String yaml) throws IOException {
        Path file = layout.sitePluginConfigFile(site, plugin);
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

    private PluginSettingsImpl settings(String plugin) {
        return new PluginSettingsImpl(plugin, SCHEMA, config, cipher);
    }

    @Test
    void anAbsentFileYieldsTheDeclaredDefaultsAndTheCodeFallbackForUndeclaredKeys() {
        SettingsView view = settings("demo").site("acme");
        assertTrue(view.bool("enabled", false), "descriptor default beats the code fallback");
        assertEquals(20, view.integer("limit", 5));
        assertEquals("Hello", view.string("title", "x"));
        assertEquals("fallback", view.string("note", "fallback"), "declared without a default → code fallback");
        assertEquals("fallback", view.string("undeclared", "fallback"));
        assertFalse(view.values().containsKey("smtpPassword"));
    }

    @Test
    void storedValuesWinUnknownKeysArePreservedAndSecretsAndBlanksDoNotLeak() throws IOException {
        store("acme", "demo", """
                enabled: false
                limit: 70
                title: "  "
                legacy.key: kept
                smtpPassword: ENC(terces)
                """);
        SettingsView view = settings("demo").site("acme");
        assertFalse(view.bool("enabled", true));
        assertEquals(70, view.integer("limit", 5));
        assertEquals("Hello", view.string("title", "x"), "a blank stored value does not mask the declared default");
        assertEquals("kept", view.string("legacy.key", "x"), "unknown stored keys are preserved");
        assertFalse(view.values().containsKey("smtpPassword"), "secret-typed keys never appear in the view");
    }

    @Test
    void secretsDecryptPassPlaintextThroughAndAnswerEmptyWhenTheyCannot() throws IOException {
        PluginSettingsImpl settings = settings("contact");
        assertEquals(Optional.empty(), settings.secret("acme", "smtpPassword"), "unset");

        store("acme", "contact", "smtpPassword: ENC(terces)\ntitle: Hi\n");
        config.evict("acme", "contact");
        assertEquals(Optional.of("secret"), settings.secret("acme", "smtpPassword"));

        cipherAvailable = false;
        assertEquals(Optional.empty(), settings.secret("acme", "smtpPassword"), "ENC with no platform key");
        cipherAvailable = true;
        cipherBroken = true;
        assertEquals(Optional.empty(), settings.secret("acme", "smtpPassword"), "undecryptable");
        cipherBroken = false;

        store("acme", "contact", "smtpPassword: hand-written\n");
        config.evict("acme", "contact");
        assertEquals(Optional.of("hand-written"), settings.secret("acme", "smtpPassword"), "plaintext passes through");

        store("acme", "contact", "smtpPassword: '   '\n");
        config.evict("acme", "contact");
        assertEquals(Optional.empty(), settings.secret("acme", "smtpPassword"), "blank");

        store("acme", "contact", "smtpPassword: '   '\ntitle: visible-but-not-a-secret\n");
        config.evict("acme", "contact");
        assertEquals(Optional.empty(), settings.secret("acme", "title"), "a stored, non-secret-typed key");
        assertEquals(Optional.empty(), settings.secret("acme", "nope"), "not declared");
        assertEquals(Optional.empty(), settings.secret("acme", null));
    }

    @Test
    void anUnsafeSiteIdIsRefusedBeforeAnyIo() {
        PluginSettingsImpl settings = settings("demo");
        for (String bad : new String[] {null, "", "../x", "a/b", "a|b"}) {
            assertThrows(IllegalArgumentException.class, () -> settings.site(bad), "site(" + bad + ")");
            assertThrows(IllegalArgumentException.class, () -> settings.secret(bad, "smtpPassword"));
        }
        assertFalse(Files.exists(external.resolve("sites")), "nothing was created on disk");
    }

    @Test
    void theViewIsCachedUntilTheStoreIsEvictedAndThenReflectsTheSave() throws IOException {
        PluginSettingsImpl settings = settings("demo");
        store("acme", "demo", "limit: 30\n");
        SettingsView first = settings.site("acme");
        assertSame(first, settings.site("acme"), "memoised while the stored map is unchanged");

        store("acme", "demo", "limit: 40\n");
        assertEquals(30, settings.site("acme").integer("limit", 0), "still the cached value: nothing evicted");

        // A replayed save from a peer node: the config service now evicts on its own event (review m14b).
        config.onConfigChanged(new SitePluginConfigChangedEvent("acme", "demo"));
        assertEquals(40, settings.site("acme").integer("limit", 0));
    }

    @Test
    void theSettingsChangedListenerEvictsOnlyTheNamedEntry() throws IOException {
        store("acme", "demo", "limit: 30\n");
        store("acme", "other", "limit: 1\n");
        assertEquals(30, ((Number) config.config("acme", "demo").get("limit")).intValue());
        assertEquals(1, ((Number) config.config("acme", "other").get("limit")).intValue());
        store("acme", "demo", "limit: 31\n");
        store("acme", "other", "limit: 2\n");
        config.onConfigChanged(new SitePluginConfigChangedEvent("acme", "demo"));
        assertEquals(31, ((Number) config.config("acme", "demo").get("limit")).intValue());
        assertEquals(1, ((Number) config.config("acme", "other").get("limit")).intValue(), "other entry kept");
        config.onConfigChanged(new SitePluginConfigChangedEvent(null, "demo")); // tolerated, no-op
        config.onConfigChanged(new SitePluginConfigChangedEvent("../x", "demo"));
    }

    @Test
    void eachInstanceReadsOnlyItsOwnPluginsFile() throws IOException {
        store("acme", "demo", "title: Demo's\n");
        store("acme", "other", "title: Other's\n");
        assertEquals("Demo's", settings("demo").site("acme").string("title", ""));
        assertEquals("Other's", settings("other").site("acme").string("title", ""));
    }
}
