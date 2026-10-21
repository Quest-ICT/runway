package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.RequestContext;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.ui.Model;
import org.springframework.validation.support.BindingAwareModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * The two screens' controller logic, exercised directly (gating is proven separately by
 * {@code ProtectionScreenGatingTest}). Covers: the app screen persists + applies live and renders the plugin
 * content template; the per-site screen writes ONLY the bound tenancy's file (isolation); a blank captcha-secret
 * field preserves the stored secret, a non-blank one encrypts it; and the GET model masks the secret.
 */
class ProtectionControllerLogicTest {

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
                return new String(Base64.getDecoder().decode(value.substring(4, value.length() - 1)));
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

    private static StaticMessageSource messages() {
        return new StaticMessageSource(); // returns the fallback for any key — fine for flash-text assertions
    }

    /** The SPI request context a tenant-console request bound to {@code siteId} answers with. */
    private static RequestContext boundTo(String siteId) {
        SiteView site = new SiteView(siteId, siteId, java.util.Locale.ENGLISH, java.util.List.of(), null);
        return () -> Optional.of(site);
    }

    private static ProtectionConfigStore store(StorageLayout layout, SecretCipher cipher) {
        return new ProtectionConfigStore(new ProtectionProperties(), layout, new YamlStore(), cipher);
    }

    // --- App screen -----------------------------------------------------------------------------------

    @Test
    void theAppSaveAppliesLiveAndRedirectsToThePluginSettingsPage(@TempDir Path root) {
        StorageLayout layout = layout(root);
        ProtectionConfigStore store = store(layout, fakeCipher());
        AppProtectionController controller = new AppProtectionController(store, messages());
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        String view = controller.save(false, 3, 60, 120, true, "recaptcha-v3", "pub", "the-secret", 0.6, redirect);

        // The panel's POST redirects back to the plugin settings page (where the panel renders).
        assertEquals("redirect:/__app/plugins/protection/settings", view);
        // Applied live (no restart): the store's app snapshot reflects the save immediately.
        ProtectionSettings live = store.appSettings();
        assertFalse(live.honeypotEnabled());
        assertEquals(3, live.maxAttempts());
        assertTrue(live.captcha().enabled());
        assertEquals("recaptcha-v3", live.captcha().provider());
        assertEquals("the-secret", live.captcha().secretKey());
        // Persisted with the secret encrypted at rest.
        assertTrue(redirect.getFlashAttributes().containsKey("flash"));
    }

    // --- Per-site screen + isolation ------------------------------------------------------------------

    @Test
    void thePerSiteSaveWritesOnlyTheBoundSitesFile(@TempDir Path root) throws IOException {
        StorageLayout layout = layout(root);
        ProtectionConfigStore store = store(layout, fakeCipher());
        SiteProtectionController controller = new SiteProtectionController(boundTo("alpha"), store, messages());

        controller.save(true, true, "recaptcha-v2", "site-a", "alpha-secret", 0.5,
                new RedirectAttributesModelMap());

        // Site "alpha" got a file; site "bravo" did NOT — the bound tenancy is the only one touched (isolation).
        Path alphaFile = layout.sitePluginConfigFile("alpha", "protection");
        Path bravoFile = layout.sitePluginConfigFile("bravo", "protection");
        assertTrue(Files.exists(alphaFile), "the bound site's file was written");
        assertFalse(Files.exists(bravoFile), "no other site's file was touched");
        // The app scope is untouched by the per-site save.
        assertFalse(Files.exists(layout.pluginSettings().resolve(ProtectionConfigStore.APP_CONFIG_FILE)),
                "the per-site screen never writes the app-scope config");
        // The per-site file carries honeypot + captcha ONLY — the app-scope lockout keys are never written per-site.
        String yaml = Files.readString(alphaFile);
        assertFalse(yaml.contains("maxAttempts") || yaml.contains("windowSeconds") || yaml.contains("lockoutSeconds"),
                "the per-site protection file must not carry the app-scope lockout keys: " + yaml);
        assertTrue(yaml.contains("honeypotEnabled") && yaml.contains("captcha"),
                "it carries the per-site honeypot + captcha");
    }

    @Test
    void twoSitesAreFullyIsolated(@TempDir Path root) {
        StorageLayout layout = layout(root);
        ProtectionConfigStore store = store(layout, fakeCipher());

        new SiteProtectionController(boundTo("alpha"), store, messages())
                .save(true, false, "recaptcha-v2", "alpha-site", "", 0.5, new RedirectAttributesModelMap());
        new SiteProtectionController(boundTo("bravo"), store, messages())
                .save(false, false, "recaptcha-v2", "bravo-site", "", 0.5, new RedirectAttributesModelMap());

        // Each site reads back exactly its own per-site config (honeypot + captcha) — neither leaks into the other.
        assertTrue(store.perSiteSettings("alpha").honeypotEnabled());
        assertEquals("alpha-site", store.perSiteSettings("alpha").captcha().siteKey());
        assertFalse(store.perSiteSettings("bravo").honeypotEnabled());
        assertEquals("bravo-site", store.perSiteSettings("bravo").captcha().siteKey());
    }

    @Test
    void anExistingPerSiteFileWithLegacyLockoutKeysStillReads(@TempDir Path root) throws IOException {
        StorageLayout layout = layout(root);
        ProtectionConfigStore store = store(layout, fakeCipher());
        Path file = layout.sitePluginConfigFile("legacy", "protection");
        Files.createDirectories(file.getParent());
        // A pre-change file that still carries the (now-inert) lockout keys alongside honeypot + captcha; it must
        // load cleanly (StoredSiteProtection ignores the unknown keys) rather than degrading to defaults.
        Files.writeString(file, "honeypotEnabled: false\nmaxAttempts: 3\nwindowSeconds: 60\nlockoutSeconds: 120\n"
                + "captcha:\n  enabled: true\n  provider: hcaptcha\n  siteKey: legacy-key\n");

        ProtectionSettings s = store.perSiteSettings("legacy");
        assertFalse(s.honeypotEnabled(), "honeypot bound from the legacy file");
        assertEquals("legacy-key", s.captcha().siteKey(), "captcha bound from the legacy file (lockout keys ignored)");
    }

    // --- Secret masking + preserve-on-blank -----------------------------------------------------------

    @Test
    void aBlankSecretOnPostPreservesTheStoredSecret(@TempDir Path root) {
        StorageLayout layout = layout(root);
        ProtectionConfigStore store = store(layout, fakeCipher());
        SiteProtectionController controller = new SiteProtectionController(boundTo("acme"), store, messages());

        // 1) Save WITH a secret.
        controller.save(true, true, "recaptcha-v2","site-k", "original-secret", 0.5,
                new RedirectAttributesModelMap());
        assertEquals("original-secret", store.perSiteSettings("acme").captcha().secretKey());

        // 2) Re-save with the secret field BLANK (and a changed site key) — the stored secret must be KEPT.
        controller.save(true, true, "recaptcha-v2","site-k-CHANGED", "  ", 0.5,
                new RedirectAttributesModelMap());
        ProtectionSettings after = store.perSiteSettings("acme");
        assertEquals("original-secret", after.captcha().secretKey(), "a blank secret field keeps the stored secret");
        assertEquals("site-k-CHANGED", after.captcha().siteKey(), "the public site key still updated");

        // 3) A non-blank value REPLACES it.
        controller.save(true, true, "recaptcha-v2","site-k-CHANGED", "new-secret", 0.5,
                new RedirectAttributesModelMap());
        assertEquals("new-secret", store.perSiteSettings("acme").captcha().secretKey());
    }

    @Test
    void thePerSiteGetMasksTheSecret(@TempDir Path root) {
        StorageLayout layout = layout(root);
        ProtectionConfigStore store = store(layout, fakeCipher());
        SiteProtectionController controller = new SiteProtectionController(boundTo("acme"), store, messages());
        controller.save(true, true, "recaptcha-v2","site-k", "hidden-secret", 0.5,
                new RedirectAttributesModelMap());

        Model model = new BindingAwareModelMap();
        controller.form(model);
        assertEquals(Boolean.TRUE, model.getAttribute("captchaSecretConfigured"), "the secret is reported configured");
        assertFalse(model.asMap().containsValue("hidden-secret"), "the plaintext secret is NEVER in the model");
    }
}
