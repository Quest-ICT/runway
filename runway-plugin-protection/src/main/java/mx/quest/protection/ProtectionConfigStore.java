package mx.quest.protection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.security.ProtectionScope;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.YamlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves and persists a {@link ProtectionScope}'s {@link ProtectionSettings} — the plugin's per-scope config
 * engine backing the {@link LoginFormPolicyGuard} and the {@link SlidingWindowLoginGate} lockout, and the
 * dedicated app-level and per-site protection screens.
 *
 * <p>Two scopes, two backing stores:
 * <ul>
 *   <li><b>Application</b> — held in a live in-memory snapshot ({@link #appSnapshot}), <b>seeded at construction</b>
 *       from the plugin's own file {@code external/plugin-settings/protection-app.yml} if present (a previous save
 *       is authoritative), else from the app-level {@link ProtectionProperties}
 *       ({@code runway.plugin.protection.login.*}, so a fresh install honors the operator's config file /
 *       plugin-settings). {@link #saveApp} replaces the snapshot <b>and</b> persists the file, so a change applies
 *       <b>immediately</b>: the login gate and form guard read {@link #appSettings()} fresh on every check — no
 *       restart. The captcha secret is stored {@code ENC(...)} in that file.</li>
 *   <li><b>Per-site</b> — the tenant-owned file {@code sites/<id>/plugins/protection.yml}, so this
 *       plugin-GENERATED tenant data travels in the site's backup (the self-containment rule). Read via
 *       {@link YamlStore}; written by {@link #savePerSite}, which <b>encrypts the captcha secret</b>
 *       ({@code ENC(...)}) with {@link SecretCipher} before it touches disk.</li>
 * </ul>
 *
 * <p><b>Fail-safe.</b> A missing file, an unresolvable site, a decryption/IO/parse fault — any of these degrades
 * to {@link ProtectionSettings#defaults()} and logs, never throwing out of {@link #resolve}: a store fault must
 * never lock every user out or 500 a login. The stored captcha secret is decrypted into the returned settings so
 * the {@link LoginFormPolicyGuard} receives plaintext at verification time and the secret never leaves the server.
 */
public class ProtectionConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ProtectionConfigStore.class);

    /** The plugin-owned app-scope config filename, alongside the per-plugin settings (survives upgrade/uninstall). */
    static final String APP_CONFIG_FILE = "protection-app.yml";

    private final ProtectionProperties appProperties;
    private final StorageLayout layout;
    private final YamlStore yaml;
    private final SecretCipher cipher;

    /**
     * The live app-scope settings. Seeded once at construction (file if present, else properties) and swapped
     * atomically by {@link #saveApp}, so {@link #appSettings()} — called fresh by the gate/guard on every check —
     * reflects a save with no restart. Holds the decrypted (plaintext-secret) form the verifier consumes.
     */
    private final AtomicReference<ProtectionSettings> appSnapshot = new AtomicReference<>();

    public ProtectionConfigStore(ProtectionProperties appProperties, StorageLayout layout, YamlStore yaml,
                                 SecretCipher cipher) {
        this.appProperties = appProperties;
        this.layout = layout;
        this.yaml = yaml;
        this.cipher = cipher;
        this.appSnapshot.set(seedAppSettings());
    }

    /**
     * The effective settings governing {@code scope}: the live app snapshot for an {@code APPLICATION} scope,
     * the per-site file for a resolved {@code TENANT} scope, else {@link ProtectionSettings#defaults()}. Never
     * throws — every fault degrades to defaults (fail-safe).
     */
    public ProtectionSettings resolve(ProtectionScope scope) {
        if (scope == null) {
            return ProtectionSettings.defaults();
        }
        return switch (scope.realm()) {
            case APPLICATION -> appSettings();
            case TENANT -> scope.resolvedSiteId().map(this::perSiteSettings).orElseGet(ProtectionSettings::defaults);
        };
    }

    /**
     * The live app-realm settings (the current in-memory snapshot, with the captcha secret as plaintext). Read
     * fresh by the login gate / form guard on every check, and by the app screen to pre-fill its form — so a
     * {@link #saveApp} takes effect on the very next login attempt without a restart. Never {@code null}.
     */
    public ProtectionSettings appSettings() {
        ProtectionSettings current = appSnapshot.get();
        return current != null ? current : ProtectionSettings.defaults();
    }

    /**
     * Replaces the app-scope settings and persists them to {@code external/plugin-settings/protection-app.yml},
     * encrypting the captcha secret first. The in-memory snapshot is swapped so the change applies immediately;
     * a blank secret is stored blank (no {@code ENC(...)}); a non-blank one requires an available
     * {@link SecretCipher} (else the save fails loudly rather than persisting a secret in the clear).
     *
     * @throws IOException           if the file cannot be written
     * @throws IllegalStateException if a non-blank captcha secret is submitted but no encryption key is configured
     */
    public void saveApp(ProtectionSettings settings) throws IOException {
        StoredProtection stored = toStored(settings);
        yaml.write(appConfigFile(), stored);
        // Swap the live snapshot to the freshly-saved settings (secret back as plaintext for the verifier).
        appSnapshot.set(stored.toSettings(this::decryptQuietly));
        log.info("saved app protection config ({} captcha secret)",
                stored.getCaptcha().getSecretKey().isBlank() ? "no" : "encrypted");
    }

    /**
     * The per-site settings from {@code sites/<id>/plugins/protection.yml}, or {@link ProtectionSettings#defaults()}
     * when the file is absent or unreadable. The stored captcha secret ({@code ENC(...)}) is decrypted into the
     * returned record.
     */
    public ProtectionSettings perSiteSettings(String siteId) {
        StoredSiteProtection stored = readPerSite(siteId).orElse(null);
        if (stored == null) {
            return ProtectionSettings.defaults();
        }
        return stored.toSettings(this::decryptQuietly);
    }

    /**
     * Writes {@code settings} for a site, encrypting the captcha secret before it is persisted. A blank secret is
     * stored blank (no {@code ENC(...)}); a non-blank one requires an available {@link SecretCipher} (else the
     * save fails loudly rather than persisting a secret in the clear).
     *
     * @throws IOException           if the file cannot be written
     * @throws IllegalStateException if a non-blank captcha secret is submitted but no encryption key is configured
     */
    public void savePerSite(String siteId, ProtectionSettings settings) throws IOException {
        StoredSiteProtection stored = toStoredSite(settings);
        Path file = layout.sitePluginConfigFile(siteId, "protection"); // traversal-safe
        yaml.write(file, stored);
        log.info("saved per-site protection config for site '{}' ({} captcha secret)",
                siteId, stored.getCaptcha().getSecretKey().isBlank() ? "no" : "encrypted");
    }

    /**
     * Whether a site's login CAPTCHA is switched on — the stored enable flag (the keys may still be invalid). Reads
     * the raw stored file, so it needs no encryption key. Drives the app Sites screen's rescue affordance.
     */
    public boolean siteCaptchaEnabled(String siteId) {
        return readPerSite(siteId).map(s -> s.getCaptcha() != null && s.getCaptcha().isEnabled()).orElse(false);
    }

    /**
     * Force a site's login CAPTCHA off — an application-operator rescue for a tenant locked out of its console by a
     * broken CAPTCHA. Preserves the stored provider and keys (the secret is carried through in its {@code ENC(...)}
     * form, so no encryption key is required) and every other setting (the honeypot); a no-op when the site has no
     * protection config or its CAPTCHA is already off. Applies without a restart (the per-site config is read fresh
     * on the next login).
     *
     * @return {@code true} if a change was written, {@code false} if there was nothing to disable
     * @throws IOException if the file cannot be rewritten
     */
    public boolean disableSiteCaptcha(String siteId) throws IOException {
        StoredSiteProtection stored = readPerSite(siteId).orElse(null);
        if (stored == null || stored.getCaptcha() == null || !stored.getCaptcha().isEnabled()) {
            return false;
        }
        StoredProtection.StoredCaptcha c = stored.getCaptcha();
        // Rebuild with enabled=false, carrying the ENC(...) secret through untouched — no decrypt/encrypt round-trip,
        // so a rescue works even when no encryption key is configured.
        stored.setCaptcha(new StoredProtection.StoredCaptcha(false, c.getProvider(), c.getSiteKey(),
                c.getSecretKey(), c.getScoreThreshold()));
        yaml.write(layout.sitePluginConfigFile(siteId, "protection"), stored); // traversal-safe
        log.info("app operator disabled login CAPTCHA for site '{}' (keys preserved)", siteId);
        return true;
    }

    /** Whether the captcha secret can be stored (an encryption key is configured); false ⇒ the screen must warn. */
    public boolean secretStorable() {
        return cipher.isAvailable();
    }

    /** The <b>app</b> on-disk shape (honeypot + per-IP lockout + captcha) — the app scope owns the lockout floor. */
    private StoredProtection toStored(ProtectionSettings settings) {
        return new StoredProtection(settings.honeypotEnabled(), settings.maxAttempts(),
                settings.windowSeconds(), settings.lockoutSeconds(), toStoredCaptcha(settings.captcha()));
    }

    /**
     * The <b>per-site</b> on-disk shape — honeypot + captcha ONLY. The per-IP lockout is an application-scope
     * platform floor ({@link SlidingWindowLoginGate} reads the app settings), so a per-site value would never take
     * effect; it is deliberately not stored per-site (its screen no longer offers it).
     */
    private StoredSiteProtection toStoredSite(ProtectionSettings settings) {
        return new StoredSiteProtection(settings.honeypotEnabled(), toStoredCaptcha(settings.captcha()));
    }

    /**
     * Builds the on-disk captcha section, encrypting the secret (blank stays blank; a non-blank secret without an
     * available cipher fails loudly so a secret is never persisted in the clear). Shared by the app + per-site saves.
     */
    private StoredProtection.StoredCaptcha toStoredCaptcha(ProtectionSettings.Captcha c) {
        String storedSecret = "";
        if (c.secretKey() != null && !c.secretKey().isBlank()) {
            if (!cipher.isAvailable()) {
                throw new IllegalStateException("cannot store a captcha secret: no encryption key is configured "
                        + "(runway.security.secret-key)");
            }
            storedSecret = cipher.encrypt(c.secretKey());
        }
        return new StoredProtection.StoredCaptcha(c.enabled(), c.provider(), c.siteKey(), storedSecret,
                c.scoreThreshold());
    }

    /**
     * The startup seed for the app snapshot: the saved app-config file if present (a prior {@link #saveApp} is
     * authoritative), else the settings derived from {@link ProtectionProperties}. Never throws — a broken file
     * or a derivation fault degrades to the properties/defaults, so the plugin always boots with a working floor.
     */
    private ProtectionSettings seedAppSettings() {
        try {
            Optional<StoredProtection> saved = yaml.read(appConfigFile(), StoredProtection.class);
            if (saved.isPresent()) {
                warnIfLoginPropertiesAreIgnored();
                return saved.get().toSettings(this::decryptQuietly);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("could not read app protection config {} — seeding from properties: {}",
                    APP_CONFIG_FILE, e.toString());
        }
        return appSettingsFromProperties();
    }

    /**
     * The {@code runway.plugin.protection.login.*} properties only seed a fresh install: once the login-protection
     * panel has saved {@code protection-app.yml}, that file is what applies. Says so at startup when they are set
     * to anything but their defaults, so a value typed into {@code application.yml} is never silently ignored.
     */
    private void warnIfLoginPropertiesAreIgnored() {
        try {
            if (!appSettingsFromProperties(appProperties).equals(appSettingsFromProperties(new ProtectionProperties()))) {
                log.warn("runway.plugin.protection.login.* is set, but {} (saved from the console's login-protection "
                        + "panel) applies: those properties only seed a fresh install. Change the values on the "
                        + "panel, or remove the properties.", APP_CONFIG_FILE);
            }
        } catch (RuntimeException e) {
            log.debug("could not compare the login properties with their defaults: {}", e.toString());
        }
    }

    /** The app-scope settings derived from the bound {@link ProtectionProperties} (its captcha secret decrypted). */
    private ProtectionSettings appSettingsFromProperties() {
        return appSettingsFromProperties(appProperties);
    }

    private ProtectionSettings appSettingsFromProperties(ProtectionProperties properties) {
        try {
            ProtectionProperties.Login login = properties.getLogin();
            ProtectionProperties.Captcha c = login.getCaptcha();
            // The app-level secret binds as its stored (possibly ENC(...)) string; decrypt it to the plaintext the
            // CaptchaVerifier uses. Decryption is null/plaintext-tolerant, so a hand-written plaintext key still works.
            String secret = decryptQuietly(c.getSecretKey());
            ProtectionSettings.Captcha captcha = new ProtectionSettings.Captcha(
                    c.isEnabled(), c.getProvider(), c.getSiteKey(), secret, c.getScoreThreshold());
            return new ProtectionSettings(login.isHoneypotEnabled(), login.getMaxAttempts(),
                    (int) login.getWindowSeconds(), (int) login.getLockoutSeconds(), captcha);
        } catch (RuntimeException e) {
            log.warn("could not derive app protection settings from properties — using defaults: {}", e.toString());
            return ProtectionSettings.defaults();
        }
    }

    /** The plugin-owned app-scope config file, under the UI-owned settings dir (outside the install dir). */
    private Path appConfigFile() {
        return layout.pluginSettings().resolve(APP_CONFIG_FILE);
    }

    private Optional<StoredSiteProtection> readPerSite(String siteId) {
        Path file;
        try {
            file = layout.sitePluginConfigFile(siteId, "protection"); // traversal-safe; throws on an unsafe id
        } catch (RuntimeException unsafeId) {
            log.warn("unsafe site id for protection config, using defaults: {}", unsafeId.toString());
            return Optional.empty();
        }
        try {
            return yaml.read(file, StoredSiteProtection.class);
        } catch (IOException | RuntimeException e) {
            // A hand-corrupted file must not 500 every login for the site — degrade to defaults and warn.
            log.warn("could not read per-site protection config {} — using defaults: {}", file, e.toString());
            return Optional.empty();
        }
    }

    /** Decrypts a stored (possibly {@code ENC(...)}) value to plaintext; on any fault returns it unchanged. */
    private String decryptQuietly(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return cipher.decrypt(value);
        } catch (RuntimeException e) {
            log.warn("could not decrypt a stored protection secret — treating captcha as unconfigured: {}",
                    e.toString());
            return "";
        }
    }
}
