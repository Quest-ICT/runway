package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.security.ProtectionScope;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-scope resolution and the per-site round-trip: app scope comes from {@link ProtectionProperties}; per-site
 * scope round-trips through {@code sites/<id>/plugins/protection.yml} with the captcha secret <b>encrypted at
 * rest</b> and decrypted back on read; and every fault path degrades to {@link ProtectionSettings#defaults()}.
 */
class ProtectionConfigStoreTest {

    /** A reversible test cipher: encrypt wraps base64 in {@code ENC(...)}, decrypt unwraps it. */
    private static SecretCipher fakeCipher() {
        return new SecretCipher() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String encrypt(String plaintext) {
                return "ENC(" + Base64.getEncoder().encodeToString(plaintext.getBytes()) + ")";
            }

            @Override
            public String decrypt(String value) {
                if (!isEncrypted(value)) {
                    return value;
                }
                String b64 = value.substring("ENC(".length(), value.length() - 1);
                return new String(Base64.getDecoder().decode(b64));
            }

            @Override
            public boolean isEncrypted(String value) {
                return value != null && value.startsWith("ENC(") && value.endsWith(")");
            }
        };
    }

    private static StorageLayout layout(Path root) {
        StorageProperties props = new StorageProperties();
        props.setExternal(root.resolve("external"));
        props.setSites(root.resolve("external").resolve("sites"));
        return StorageLayout.from(props);
    }

    private static ProtectionProperties appProps() {
        ProtectionProperties p = new ProtectionProperties();
        p.getLogin().setHoneypotEnabled(false);
        p.getLogin().setMaxAttempts(7);
        p.getLogin().setWindowSeconds(111);
        p.getLogin().setLockoutSeconds(222);
        p.getLogin().getCaptcha().setEnabled(true);
        p.getLogin().getCaptcha().setSiteKey("app-site-key");
        p.getLogin().getCaptcha().setSecretKey("app-plaintext-secret"); // app-level: bound as plaintext here
        return p;
    }

    @Test
    void appScopeComesFromTheProperties(@TempDir Path root) {
        ProtectionConfigStore store = new ProtectionConfigStore(appProps(), layout(root), new YamlStore(), fakeCipher());
        ProtectionSettings s = store.resolve(ProtectionScope.application());
        assertFalse(s.honeypotEnabled(), "app honeypot flag from properties");
        assertEquals(7, s.maxAttempts());
        assertEquals(111, s.windowSeconds());
        assertEquals(222, s.lockoutSeconds());
        assertTrue(s.captcha().enabled());
        assertEquals("app-plaintext-secret", s.captcha().secretKey(), "app secret decrypts (plaintext passes through)");
    }

    @Test
    void everyPathUsesTheSameLockoutDefaults(@TempDir Path root) throws IOException {
        ProtectionSettings fresh = new ProtectionConfigStore(new ProtectionProperties(), layout(root), new YamlStore(),
                fakeCipher()).resolve(ProtectionScope.application());
        ProtectionSettings expected = ProtectionSettings.defaults();
        assertEquals(List.of(5, 300, 900), List.of(fresh.maxAttempts(), fresh.windowSeconds(), fresh.lockoutSeconds()),
                "a fresh install");
        assertEquals(List.of(5, 300, 900),
                List.of(expected.maxAttempts(), expected.windowSeconds(), expected.lockoutSeconds()), "defaults()");

        StorageLayout layout = layout(root.resolve("saved"));
        Files.createDirectories(layout.pluginSettings());
        Files.writeString(layout.pluginSettings().resolve("protection-app.yml"), "honeypotEnabled: true\n");
        ProtectionSettings omitted = new ProtectionConfigStore(new ProtectionProperties(), layout, new YamlStore(),
                fakeCipher()).resolve(ProtectionScope.application());
        assertEquals(List.of(5, 300, 900),
                List.of(omitted.maxAttempts(), omitted.windowSeconds(), omitted.lockoutSeconds()),
                "a saved file that omits the lockout keys");
        assertEquals(5, new ProtectionSettings(true, 0, -1, 0, null).maxAttempts(), "a non-positive value");
    }

    @Test
    void loginPropertiesShadowedByTheSavedPanelFileAreReportedAtStartup(@TempDir Path root) throws IOException {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProtectionConfigStore.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            StorageLayout layout = layout(root);
            Files.createDirectories(layout.pluginSettings());
            Files.writeString(layout.pluginSettings().resolve("protection-app.yml"), "maxAttempts: 9\n");

            new ProtectionConfigStore(new ProtectionProperties(), layout, new YamlStore(), fakeCipher());
            assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains("login.*")),
                    "properties at their defaults: nothing to say");

            ProtectionSettings applied = new ProtectionConfigStore(appProps(), layout, new YamlStore(), fakeCipher())
                    .resolve(ProtectionScope.application());
            assertEquals(9, applied.maxAttempts(), "the saved panel file applies");
            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                            && e.getFormattedMessage().contains("runway.plugin.protection.login.* is set")),
                    "a set property that does not apply is reported");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void perSiteRoundTripsWithTheCaptchaSecretEncryptedAtRest(@TempDir Path root) throws IOException {
        SecretCipher cipher = fakeCipher();
        StorageLayout layout = layout(root);
        ProtectionConfigStore store = new ProtectionConfigStore(new ProtectionProperties(), layout, new YamlStore(), cipher);

        ProtectionSettings toSave = new ProtectionSettings(true, 4, 60, 120,
                new ProtectionSettings.Captcha(true, "recaptcha-v3", "site-key-123", "super-secret-value", 0.7));
        store.savePerSite("acme", toSave);

        // On disk the secret is ENC(...), never the plaintext — and the app-scope lockout keys are NOT written per-site.
        Path file = layout.sitePluginConfigFile("acme", "protection");
        String onDisk = Files.readString(file);
        assertTrue(onDisk.contains("ENC("), "the captcha secret is stored encrypted");
        assertFalse(onDisk.contains("super-secret-value"), "the plaintext secret is never written to disk");
        assertFalse(onDisk.contains("maxAttempts") || onDisk.contains("lockoutSeconds"),
                "the per-site file carries no lockout keys (lockout is app-scope)");

        // Read back: honeypot + captcha round-trip and the secret is decrypted. The lockout is app-scope, so per-site
        // it reads back at defaults (never the saved 4/60/120 — those are not stored per-site).
        ProtectionSettings read = store.resolve(ProtectionScope.forSite("acme"));
        assertTrue(read.honeypotEnabled());
        assertTrue(read.captcha().enabled());
        assertEquals("recaptcha-v3", read.captcha().provider());
        assertEquals("site-key-123", read.captcha().siteKey());
        assertEquals("super-secret-value", read.captcha().secretKey(), "the secret decrypts back to plaintext");
        assertEquals(0.7, read.captcha().scoreThreshold());
    }

    @Test
    void anAbsentPerSiteFileYieldsDefaults(@TempDir Path root) {
        ProtectionConfigStore store =
                new ProtectionConfigStore(new ProtectionProperties(), layout(root), new YamlStore(), fakeCipher());
        ProtectionSettings s = store.resolve(ProtectionScope.forSite("no-such-site"));
        assertEquals(ProtectionSettings.defaults(), s, "absent file → defaults (fail-safe)");
    }

    @Test
    void aSiteLessTenantScopeYieldsDefaults(@TempDir Path root) {
        ProtectionConfigStore store =
                new ProtectionConfigStore(new ProtectionProperties(), layout(root), new YamlStore(), fakeCipher());
        // forSite(null) is a site-less tenant scope (unresolvable site) → defaults, never another site's config.
        assertEquals(ProtectionSettings.defaults(), store.resolve(ProtectionScope.forSite(null)));
    }

    @Test
    void aCorruptPerSiteFileDegradesToDefaults(@TempDir Path root) throws IOException {
        StorageLayout layout = layout(root);
        Path file = layout.sitePluginConfigFile("broken", "protection");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "this: [is: not: valid: yaml: {{{");
        ProtectionConfigStore store =
                new ProtectionConfigStore(new ProtectionProperties(), layout, new YamlStore(), fakeCipher());
        assertEquals(ProtectionSettings.defaults(), store.resolve(ProtectionScope.forSite("broken")),
                "a hand-corrupted file must not 500 the login — degrade to defaults");
    }

    @Test
    void savingASecretWithoutAnAvailableCipherFailsLoudly(@TempDir Path root) {
        SecretCipher unavailable = new SecretCipher() {
            @Override public boolean isAvailable() { return false; }
            @Override public String encrypt(String plaintext) { throw new IllegalStateException("no key"); }
            @Override public String decrypt(String value) { return value; }
            @Override public boolean isEncrypted(String value) { return value != null && value.startsWith("ENC("); }
        };
        ProtectionConfigStore store =
                new ProtectionConfigStore(new ProtectionProperties(), layout(root), new YamlStore(), unavailable);
        ProtectionSettings withSecret = new ProtectionSettings(true, 4, 60, 120,
                new ProtectionSettings.Captcha(true, "recaptcha-v2", "site", "a-secret", 0.5));
        // A secret must never be persisted in the clear: no key → the save fails rather than writing plaintext.
        assertThrows(IllegalStateException.class, () -> store.savePerSite("acme", withSecret));
    }

    @Test
    void aBlankSecretIsStoredBlankNotEncrypted(@TempDir Path root) throws IOException {
        StorageLayout layout = layout(root);
        ProtectionConfigStore store =
                new ProtectionConfigStore(new ProtectionProperties(), layout, new YamlStore(), fakeCipher());
        ProtectionSettings noSecret = new ProtectionSettings(true, 4, 60, 120,
                new ProtectionSettings.Captcha(false, "recaptcha-v2", "", "", 0.5));
        store.savePerSite("acme", noSecret);
        String onDisk = Files.readString(layout.sitePluginConfigFile("acme", "protection"));
        assertFalse(onDisk.contains("ENC("), "a blank secret is stored blank, not encrypted");
    }

    // --- operator rescue: disable a site's login CAPTCHA from the app console -------------------------

    @Test
    void disableSiteCaptchaTurnsItOffAndPreservesTheStoredKeys(@TempDir Path root) throws IOException {
        StorageLayout layout = layout(root);
        ProtectionConfigStore store =
                new ProtectionConfigStore(new ProtectionProperties(), layout, new YamlStore(), fakeCipher());
        store.savePerSite("acme", new ProtectionSettings(true, 4, 60, 120,
                new ProtectionSettings.Captcha(true, "recaptcha-v2", "site-key-abc", "top-secret", 0.5)));
        assertTrue(store.siteCaptchaEnabled("acme"), "captcha starts enabled");

        assertTrue(store.disableSiteCaptcha("acme"), "a change was written");

        assertFalse(store.siteCaptchaEnabled("acme"), "captcha is now off");
        ProtectionSettings read = store.resolve(ProtectionScope.forSite("acme"));
        assertFalse(read.captcha().enabled());
        assertEquals("recaptcha-v2", read.captcha().provider(), "the provider is preserved");
        assertEquals("site-key-abc", read.captcha().siteKey(), "the site key is preserved");
        assertEquals("top-secret", read.captcha().secretKey(), "the secret is preserved (decrypts back), not wiped");
        String onDisk = Files.readString(layout.sitePluginConfigFile("acme", "protection"));
        assertTrue(onDisk.contains("ENC("), "the secret stays encrypted on disk");
        assertFalse(onDisk.contains("top-secret"), "the plaintext secret is never written");
    }

    @Test
    void disableSiteCaptchaWorksWithoutAnEncryptionKey(@TempDir Path root) throws IOException {
        // The rescue must not require a cipher: the ENC(...) secret is carried through untouched. Seed a file that
        // has an encrypted secret, then disable with an UNAVAILABLE cipher and confirm it still succeeds.
        StorageLayout layout = layout(root);
        new ProtectionConfigStore(new ProtectionProperties(), layout, new YamlStore(), fakeCipher())
                .savePerSite("acme", new ProtectionSettings(true, 0, 0, 0,
                        new ProtectionSettings.Captcha(true, "recaptcha-v2", "k", "s", 0.5)));
        SecretCipher unavailable = new SecretCipher() {
            @Override public boolean isAvailable() { return false; }
            @Override public String encrypt(String plaintext) { throw new IllegalStateException("no key"); }
            @Override public String decrypt(String value) { return value; }
            @Override public boolean isEncrypted(String value) { return value != null && value.startsWith("ENC("); }
        };
        ProtectionConfigStore noKey =
                new ProtectionConfigStore(new ProtectionProperties(), layout, new YamlStore(), unavailable);
        assertTrue(noKey.disableSiteCaptcha("acme"), "the rescue succeeds even with no encryption key configured");
        assertFalse(noKey.siteCaptchaEnabled("acme"), "captcha is off");
    }

    @Test
    void disableSiteCaptchaIsANoOpWhenAbsentOrAlreadyOff(@TempDir Path root) throws IOException {
        StorageLayout layout = layout(root);
        ProtectionConfigStore store =
                new ProtectionConfigStore(new ProtectionProperties(), layout, new YamlStore(), fakeCipher());
        assertFalse(store.disableSiteCaptcha("no-such-site"), "nothing to disable when no config exists");
        store.savePerSite("acme", new ProtectionSettings(true, 0, 0, 0,
                new ProtectionSettings.Captcha(false, "recaptcha-v2", "k", "", 0.5)));
        assertFalse(store.siteCaptchaEnabled("acme"), "captcha is already off");
        assertFalse(store.disableSiteCaptcha("acme"), "no change when captcha is already off");
    }
}
