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

    // ---- app-level secrets (SPI 1.1.0) -------------------------------------------------------------

    private static final List<PluginSettingDef> APP_SCHEMA = List.of(
            new PluginSettingDef("password", "secret", null, "l", null),
            new PluginSettingDef("host", "string", "localhost", "l", null));

    private final java.util.Map<String, String> environment = new java.util.HashMap<>();

    private PluginSettingsImpl withApp(String plugin) {
        return new PluginSettingsImpl(plugin, SCHEMA, APP_SCHEMA, environment::get, config, cipher);
    }

    @Test
    void anAppSecretIsReadFromTheRunningEnvironmentUnderTheCallersOwnPrefixAndDecrypted() {
        environment.put("runway.plugin.database.password", cipher.encrypt("s3cret"));
        environment.put("runway.plugin.other.password", "not-mine");
        assertEquals(Optional.of("s3cret"), withApp("database").appSecret("password"));
        assertEquals(Optional.empty(), withApp("unconfigured").appSecret("password"),
                "the lookup is keyed by the bound plugin's own id: another plugin's value is never read");
    }

    @Test
    void aHandWrittenPlaintextAppSecretIsReturnedUnchanged() {
        environment.put("runway.plugin.database.password", "plain");
        assertEquals(Optional.of("plain"), withApp("database").appSecret("password"));
    }

    @Test
    void onlyAKeyDeclaredSecretInTheAppLevelSchemaIsHandedOut() {
        environment.put("runway.plugin.database.host", "db.example");
        environment.put("runway.plugin.database.smtpPassword", "site-level-key");
        environment.put("runway.plugin.database.undeclared", "x");
        PluginSettingsImpl settings = withApp("database");
        assertEquals(Optional.empty(), settings.appSecret("host"), "declared, but not a secret");
        assertEquals(Optional.empty(), settings.appSecret("smtpPassword"),
                "a secret declared only in siteSettings: is not an app-level secret");
        assertEquals(Optional.empty(), settings.appSecret("undeclared"));
        assertEquals(Optional.empty(), settings.appSecret(null));
    }

    @Test
    void anUnsetBlankOrUndecryptableAppSecretIsEmpty() {
        PluginSettingsImpl settings = withApp("database");
        assertEquals(Optional.empty(), settings.appSecret("password"), "unset");
        environment.put("runway.plugin.database.password", "   ");
        assertEquals(Optional.empty(), settings.appSecret("password"), "blank");
        environment.put("runway.plugin.database.password", cipher.encrypt("s3cret"));
        cipherAvailable = false;
        assertEquals(Optional.empty(), settings.appSecret("password"), "no usable platform secret key");
        cipherAvailable = true;
        cipherBroken = true;
        assertEquals(Optional.empty(), settings.appSecret("password"), "wrong key or corrupted value");
    }

    @Test
    void theValueFollowsTheEnvironmentItIsHandedNotAFileSavedSinceStartup() {
        PluginSettingsImpl settings = withApp("database");
        environment.put("runway.plugin.database.password", "first");
        assertEquals(Optional.of("first"), settings.appSecret("password"));
        environment.put("runway.plugin.database.password", "second");
        assertEquals(Optional.of("second"), settings.appSecret("password"), "not cached past the environment");
    }

    @Test
    void theSiteOnlyConstructorHasNoAppSecretsAndTheSpiDefaultIsEmpty() {
        environment.put("runway.plugin.database.password", "plain");
        assertEquals(Optional.empty(), settings("database").appSecret("password"));
        mx.quest.runway.spi.service.PluginSettings bare = new mx.quest.runway.spi.service.PluginSettings() {
            @Override
            public SettingsView site(String siteId) {
                return new SettingsView(java.util.Map.of());
            }

            @Override
            public Optional<String> secret(String siteId, String key) {
                return Optional.empty();
            }
        };
        assertEquals(Optional.empty(), bare.appSecret("password"),
                "an implementation written against SPI 1.0 still compiles and answers empty");
    }

    // ---- saved app-level settings (SPI 1.2.0) -------------------------------------------------------

    private void saveAppFile(String plugin, String yaml) throws IOException {
        Path file = layout.pluginSettingsFile(plugin);
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

    /** As the kernel wires it: the saved values come from the plugin's own settings file. */
    private PluginSettingsImpl withSavedApp(String plugin) {
        return new PluginSettingsImpl(plugin, SCHEMA, APP_SCHEMA, environment::get,
                key -> mx.quest.runway.plugin.PluginSettingsService.saved(layout, plugin).get(key), config, cipher);
    }

    @Test
    void aSavedAppSettingIsReadFromTheFileNotFromTheRunningEnvironment() throws IOException {
        environment.put("runway.plugin.database.host", "running.example");
        environment.put("runway.plugin.database.password", "running-secret");
        saveAppFile("database", "runway:\n  plugin:\n    database:\n      host: saved.example\n      password: "
                + cipher.encrypt("saved-secret") + "\n");
        PluginSettingsImpl settings = withSavedApp("database");
        assertEquals(Optional.of("saved.example"), settings.savedApp("host"));
        assertEquals(Optional.of("saved-secret"), settings.savedApp("password"), "a saved secret, decrypted");
        assertEquals(Optional.of("running-secret"), settings.appSecret("password"), "appSecret keeps the running one");
    }

    @Test
    void aSaveAfterStartupIsSeenOnTheNextCall() throws IOException {
        PluginSettingsImpl settings = withSavedApp("database");
        saveAppFile("database", "runway:\n  plugin:\n    database:\n      host: first.example\n");
        assertEquals(Optional.of("first.example"), settings.savedApp("host"));
        saveAppFile("database", "runway:\n  plugin:\n    database:\n      host: second.example\n");
        assertEquals(Optional.of("second.example"), settings.savedApp("host"), "read from the file each time");
    }

    @Test
    void anUnsavedOrBlankSettingFallsBackToTheDeclaredDefaultExceptASecret() throws IOException {
        PluginSettingsImpl settings = withSavedApp("database");
        assertEquals(Optional.of("localhost"), settings.savedApp("host"), "no file: the declared default");
        assertEquals(Optional.empty(), settings.savedApp("password"), "a secret has no default");
        saveAppFile("database", "runway:\n  plugin:\n    database:\n      host: '  '\n      password: ''\n");
        assertEquals(Optional.of("localhost"), settings.savedApp("host"));
        assertEquals(Optional.empty(), settings.savedApp("password"));
    }

    @Test
    void onlyTheCallersOwnDeclaredAppKeysAreRead() throws IOException {
        saveAppFile("database", "runway:\n  plugin:\n    database:\n      undeclared: x\n      smtpPassword: y\n");
        saveAppFile("other", "runway:\n  plugin:\n    other:\n      host: not-mine\n");
        PluginSettingsImpl settings = withSavedApp("database");
        assertEquals(Optional.empty(), settings.savedApp("undeclared"));
        assertEquals(Optional.empty(), settings.savedApp("smtpPassword"), "declared only in siteSettings:");
        assertEquals(Optional.empty(), settings.savedApp(null));
        assertEquals(Optional.of("localhost"), settings.savedApp("host"), "another plugin's file is never read");
    }

    @Test
    void aSavedSecretThatCannotBeDecryptedIsEmptyAndAPlaintextOneIsReturned() throws IOException {
        PluginSettingsImpl settings = withSavedApp("database");
        saveAppFile("database", "runway:\n  plugin:\n    database:\n      password: hand-written\n");
        assertEquals(Optional.of("hand-written"), settings.savedApp("password"));
        saveAppFile("database", "runway:\n  plugin:\n    database:\n      password: " + cipher.encrypt("s")
                + "\n");
        cipherBroken = true;
        assertEquals(Optional.empty(), settings.savedApp("password"));
    }

    @Test
    void theOlderConstructorsAndTheSpiDefaultHaveNoSavedValues() {
        environment.put("runway.plugin.database.host", "running.example");
        assertEquals(Optional.of("localhost"), withApp("database").savedApp("host"),
                "no saved-values lookup: only the declared default");
        mx.quest.runway.spi.service.PluginSettings bare = new mx.quest.runway.spi.service.PluginSettings() {
            @Override
            public SettingsView site(String siteId) {
                return new SettingsView(java.util.Map.of());
            }

            @Override
            public Optional<String> secret(String siteId, String key) {
                return Optional.empty();
            }
        };
        assertEquals(Optional.empty(), bare.savedApp("host"), "an implementation written before SPI 1.2.0");
    }
}
