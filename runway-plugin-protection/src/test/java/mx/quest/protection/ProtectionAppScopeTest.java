package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.security.ProtectionScope;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The writable app scope: a {@link ProtectionConfigStore#saveApp} applies to the live
 * snapshot IMMEDIATELY (so {@link ProtectionConfigStore#appSettings()} — which the login gate/guard call fresh —
 * reflects it with no restart), persists to {@code external/plugin-settings/protection-app.yml} with the captcha
 * secret encrypted at rest, and the file (a prior save) is authoritative on the next boot; with no saved file the
 * snapshot seeds from {@link ProtectionProperties} (backward-compat with {@code runway.plugin.protection.login.*}).
 */
class ProtectionAppScopeTest {

    /** A reversible test cipher: encrypt wraps base64 in {@code ENC(...)}, decrypt unwraps it. */
    private static SecretCipher fakeCipher() {
        return new SecretCipher() {
            @Override public boolean isAvailable() { return true; }

            @Override public String encrypt(String plaintext) {
                return "ENC(" + Base64.getEncoder().encodeToString(plaintext.getBytes()) + ")";
            }

            @Override public String decrypt(String value) {
                if (!isEncrypted(value)) {
                    return value;
                }
                String b64 = value.substring("ENC(".length(), value.length() - 1);
                return new String(Base64.getDecoder().decode(b64));
            }

            @Override public boolean isEncrypted(String value) {
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
        return p;
    }

    @Test
    void appScopeSeedsFromPropertiesWhenNoFileExists(@TempDir Path root) {
        ProtectionConfigStore store = new ProtectionConfigStore(appProps(), layout(root), new YamlStore(), fakeCipher());
        ProtectionSettings s = store.appSettings();
        // Backward-compat: with no saved app-config file, the snapshot is derived from the properties.
        assertFalse(s.honeypotEnabled());
        assertEquals(7, s.maxAttempts());
        assertEquals(111, s.windowSeconds());
        assertEquals(222, s.lockoutSeconds());
    }

    @Test
    void saveAppAppliesImmediatelyToTheLiveSnapshot(@TempDir Path root) throws IOException {
        ProtectionConfigStore store = new ProtectionConfigStore(appProps(), layout(root), new YamlStore(), fakeCipher());
        ProtectionSettings updated = new ProtectionSettings(true, 3, 60, 120,
                new ProtectionSettings.Captcha(true, "recaptcha-v3", "pub-key", "the-secret", 0.6));
        store.saveApp(updated);

        // The live snapshot (what the login gate/guard read fresh) reflects the save with no restart.
        ProtectionSettings live = store.appSettings();
        assertTrue(live.honeypotEnabled());
        assertEquals(3, live.maxAttempts());
        assertEquals(60, live.windowSeconds());
        assertEquals(120, live.lockoutSeconds());
        assertTrue(live.captcha().enabled());
        assertEquals("recaptcha-v3", live.captcha().provider());
        assertEquals("the-secret", live.captcha().secretKey(), "the live secret is the plaintext the verifier uses");

        // resolve(application) goes through the same snapshot.
        assertEquals(live, store.resolve(ProtectionScope.application()));
    }

    @Test
    void saveAppPersistsWithTheSecretEncryptedAndTheFileIsAuthoritativeOnReboot(@TempDir Path root) throws IOException {
        StorageLayout layout = layout(root);
        SecretCipher cipher = fakeCipher();
        ProtectionConfigStore store = new ProtectionConfigStore(appProps(), layout, new YamlStore(), cipher);
        store.saveApp(new ProtectionSettings(true, 4, 200, 300,
                new ProtectionSettings.Captcha(true, "recaptcha-v2", "site-k", "app-secret-value", 0.5)));

        Path file = layout.pluginSettings().resolve(ProtectionConfigStore.APP_CONFIG_FILE);
        String onDisk = Files.readString(file);
        assertTrue(onDisk.contains("ENC("), "the app captcha secret is stored encrypted");
        assertFalse(onDisk.contains("app-secret-value"), "the plaintext app secret is never written to disk");

        // A NEW store (a reboot) seeds from the saved file — NOT the properties — and decrypts the secret back.
        ProtectionConfigStore rebooted = new ProtectionConfigStore(appProps(), layout, new YamlStore(), cipher);
        ProtectionSettings seeded = rebooted.appSettings();
        assertEquals(4, seeded.maxAttempts(), "the saved file, not the properties (which say 7), is authoritative");
        assertEquals(200, seeded.windowSeconds());
        assertEquals(300, seeded.lockoutSeconds());
        assertEquals("app-secret-value", seeded.captcha().secretKey(), "the app secret decrypts back on reboot");
    }

    @Test
    void savingAnAppSecretWithoutACipherFailsLoudly(@TempDir Path root) {
        SecretCipher unavailable = new SecretCipher() {
            @Override public boolean isAvailable() { return false; }
            @Override public String encrypt(String plaintext) { throw new IllegalStateException("no key"); }
            @Override public String decrypt(String value) { return value; }
            @Override public boolean isEncrypted(String value) { return value != null && value.startsWith("ENC("); }
        };
        ProtectionConfigStore store =
                new ProtectionConfigStore(appProps(), layout(root), new YamlStore(), unavailable);
        ProtectionSettings withSecret = new ProtectionSettings(true, 4, 60, 120,
                new ProtectionSettings.Captcha(true, "recaptcha-v2", "site", "a-secret", 0.5));
        // A secret must never be persisted in the clear: no key → the save fails rather than writing plaintext.
        assertThrows(IllegalStateException.class, () -> store.saveApp(withSecret));
        assertFalse(store.secretStorable(), "the store reports the secret is not storable (drives the screen hint)");
    }
}
