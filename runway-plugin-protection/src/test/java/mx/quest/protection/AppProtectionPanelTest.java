package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The read-model the app login-protection PANEL ({@code templates/admin-app.html}) binds to. It must surface the
 * live app-scope settings for pre-fill, and — the security-critical contract — expose the captcha secret ONLY as a
 * boolean "configured?" flag, never the value, so the panel has no binding through which the secret could reach the
 * page.
 */
class AppProtectionPanelTest {

    private static AppProtectionPanel panel(Path root, ProtectionSettings settings) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(root);
        ProtectionConfigStore store = new ProtectionConfigStore(
                new ProtectionProperties(), StorageLayout.from(props), new YamlStore(), passthrough());
        store.saveApp(settings);
        return new AppProtectionPanel(store);
    }

    @Test
    void itSurfacesTheLiveSettingsForThePanel(@TempDir Path root) throws IOException {
        AppProtectionPanel panel = panel(root, new ProtectionSettings(true, 7, 250, 800,
                new ProtectionSettings.Captcha(true, "recaptcha-v3", "public-site-key", "", 0.4)));

        assertTrue(panel.honeypotEnabled());
        assertEquals(7, panel.maxAttempts());
        assertEquals(250, panel.windowSeconds());
        assertEquals(800, panel.lockoutSeconds());
        assertTrue(panel.captchaEnabled());
        assertEquals("recaptcha-v3", panel.provider());
        assertEquals("public-site-key", panel.siteKey());
        assertEquals(0.4, panel.scoreThreshold());
        assertTrue(panel.providers().contains("recaptcha-v2"), "the provider list is offered to the select");
        assertTrue(panel.secretStorable(), "a cipher is available");
        assertFalse(panel.secretConfigured(), "no secret set yet");
    }

    @Test
    void itExposesTheCaptchaSecretOnlyAsABoolean(@TempDir Path root) throws IOException {
        AppProtectionPanel panel = panel(root, new ProtectionSettings(true, 5, 300, 900,
                new ProtectionSettings.Captcha(true, "recaptcha-v2", "public-site-key", "TOP-SECRET-VALUE", 0.5)));

        assertTrue(panel.secretConfigured(), "a stored secret is reported as a boolean");
        assertEquals("public-site-key", panel.siteKey(), "the PUBLIC site key is surfaced (it is not secret)");
        // Structural masking guard: NO zero-arg String getter on the panel returns the secret value, so the fragment
        // has nothing to bind it to. Guards against a future regression that adds such an accessor.
        boolean leaks = Arrays.stream(AppProtectionPanel.class.getMethods())
                .filter(m -> m.getParameterCount() == 0 && m.getReturnType() == String.class)
                .anyMatch(m -> {
                    try {
                        return "TOP-SECRET-VALUE".equals(m.invoke(panel));
                    } catch (ReflectiveOperationException e) {
                        return false;
                    }
                });
        assertFalse(leaks, "no accessor on the panel returns the captcha secret value");
    }

    private static SecretCipher passthrough() {
        return new SecretCipher() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String encrypt(String plaintext) {
                return plaintext;
            }

            @Override
            public String decrypt(String value) {
                return value;
            }

            @Override
            public boolean isEncrypted(String value) {
                return false;
            }
        };
    }
}
