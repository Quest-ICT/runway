package mx.quest.runway.plugin.spi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.SettingType;
import mx.quest.runway.plugin.SitePluginConfigService;
import mx.quest.runway.spi.model.SettingsView;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.storage.PathSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The kernel's {@link PluginSettings} for <b>one</b> plugin — built once per plugin by
 * {@code PluginScopedServices} and handed to every injection point in that plugin's own jar. It reads through
 * {@link SitePluginConfigService} (whose per-site cache is evicted on every save, on this node and — from SPI 0.4.0
 * — on peers through the replayed change event), overlays the descriptor's declared defaults, and drops secrets.
 * App-level secrets ({@link #appSecret}) come from the running environment instead, through a lookup the factory
 * hands in, because app-level settings bind as {@code runway.plugin.<id>.<key>} properties at startup.
 *
 * <h2>Caching</h2>
 * The merged view is memoised per site against the <b>identity</b> of the stored map the config service returns:
 * that service hands back the same instance until its cache entry is evicted, so a save (or a replayed save, or a
 * site reload) makes the next call rebuild the view, and no second eviction path can drift out of step.
 *
 * <p>Thread-safe.
 */
public final class PluginSettingsImpl implements PluginSettings {

    private static final Logger log = LoggerFactory.getLogger(PluginSettingsImpl.class);

    private final String pluginId;
    private final Map<String, PluginSettingDef> schema;
    private final Map<String, PluginSettingDef> appSchema;
    private final Function<String, String> appValues;
    private final Function<String, String> savedAppValues;
    private final SitePluginConfigService config;
    private final SecretCipher cipher;
    private final Map<String, Memo> memo = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private record Memo(Map<String, Object> stored, SettingsView view) {
    }

    /**
     * Without app-level settings: {@link #appSecret} always answers empty.
     *
     * @param pluginId     the plugin this instance answers for
     * @param siteSettings that plugin's declared {@code siteSettings:} schema
     * @param config       the per-site settings store
     * @param cipher       decrypts {@code ENC(...)} secrets
     */
    public PluginSettingsImpl(String pluginId, List<PluginSettingDef> siteSettings, SitePluginConfigService config,
                              SecretCipher cipher) {
        this(pluginId, siteSettings, List.of(), key -> null, config, cipher);
    }

    /**
     * @param pluginId     the plugin this instance answers for
     * @param siteSettings that plugin's declared {@code siteSettings:} schema
     * @param appSettings  that plugin's declared app-level {@code settings:} schema
     * @param appValues    resolves a full property key ({@code runway.plugin.<id>.<key>}) to the value the running
     *                     environment holds, or {@code null}
     * @param config       the per-site settings store
     * @param cipher       decrypts {@code ENC(...)} secrets
     */
    public PluginSettingsImpl(String pluginId, List<PluginSettingDef> siteSettings, List<PluginSettingDef> appSettings,
                              Function<String, String> appValues, SitePluginConfigService config,
                              SecretCipher cipher) {
        this(pluginId, siteSettings, appSettings, appValues, key -> null, config, cipher);
    }

    /**
     * @param pluginId       the plugin this instance answers for
     * @param siteSettings   that plugin's declared {@code siteSettings:} schema
     * @param appSettings    that plugin's declared app-level {@code settings:} schema
     * @param appValues      resolves a full property key ({@code runway.plugin.<id>.<key>}) to the value the
     *                       running environment holds, or {@code null}
     * @param savedAppValues resolves a setting key to the value saved in the plugin's app-level settings file now,
     *                       in its stored form, or {@code null}
     * @param config         the per-site settings store
     * @param cipher         decrypts {@code ENC(...)} secrets
     */
    public PluginSettingsImpl(String pluginId, List<PluginSettingDef> siteSettings, List<PluginSettingDef> appSettings,
                              Function<String, String> appValues, Function<String, String> savedAppValues,
                              SitePluginConfigService config, SecretCipher cipher) {
        this.savedAppValues = Objects.requireNonNull(savedAppValues, "savedAppValues");
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.schema = byKey(siteSettings);
        this.appSchema = byKey(appSettings);
        this.appValues = Objects.requireNonNull(appValues, "appValues");
        this.config = Objects.requireNonNull(config, "config");
        this.cipher = cipher;
    }

    private static Map<String, PluginSettingDef> byKey(List<PluginSettingDef> defs) {
        Map<String, PluginSettingDef> byKey = new LinkedHashMap<>();
        for (PluginSettingDef def : defs == null ? List.<PluginSettingDef>of() : defs) {
            if (def != null && def.key() != null && !def.key().isBlank()) {
                byKey.putIfAbsent(def.key(), def);
            }
        }
        return Map.copyOf(byKey);
    }

    /** The plugin this instance is bound to. */
    public String pluginId() {
        return pluginId;
    }

    @Override
    public SettingsView site(String siteId) {
        requireSafe(siteId);
        Map<String, Object> stored = config.config(siteId, pluginId);
        Memo current = memo.get(siteId);
        if (current != null && current.stored() == stored) {
            return current.view();
        }
        SettingsView view = merge(stored);
        memo.put(siteId, new Memo(stored, view));
        return view;
    }

    /**
     * Declared defaults first (secret-typed keys never), then every stored value — except a secret-typed key, a
     * {@code null}, and a blank string for a declared key that has a default (a hand-cleared value must not mask
     * the default the form shows).
     */
    private SettingsView merge(Map<String, Object> stored) {
        Map<String, Object> values = new LinkedHashMap<>();
        schema.forEach((key, def) -> {
            if (!isSecret(def) && def.defaultValue() != null) {
                values.put(key, def.defaultValue());
            }
        });
        stored.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            PluginSettingDef def = schema.get(key);
            if (def != null && isSecret(def)) {
                return;
            }
            if (def != null && def.defaultValue() != null && value instanceof String text && text.isBlank()) {
                return;
            }
            values.put(key, value);
        });
        return new SettingsView(values);
    }

    @Override
    public Optional<String> secret(String siteId, String key) {
        requireSafe(siteId);
        PluginSettingDef def = key == null ? null : schema.get(key);
        if (def == null || !isSecret(def)) {
            return Optional.empty(); // only a key the plugin declared `type: secret` is ever handed out in clear
        }
        Object raw = config.config(siteId, pluginId).get(key);
        return reveal(raw == null ? null : String.valueOf(raw), "site '" + siteId + "'",
                siteId + PathSafety.KEY_SEPARATOR + key, key);
    }

    /**
     * Only a key this plugin declared {@code type: secret} in its own app-level {@code settings:} is handed out; the
     * value is the running environment's ({@code runway.plugin.<id>.<key>}), so a saved change answers after the
     * restart that applies it — the same moment the plugin's {@code @ConfigurationProperties} see it.
     */
    @Override
    public Optional<String> appSecret(String key) {
        PluginSettingDef def = key == null ? null : appSchema.get(key);
        if (def == null || !isSecret(def)) {
            return Optional.empty();
        }
        // The separator cannot occur in a site id, so the app-level warn-once key never collides with a site's.
        return reveal(appValues.apply("runway.plugin." + pluginId + "." + key), "the application",
                PathSafety.KEY_SEPARATOR + "app" + PathSafety.KEY_SEPARATOR + key, key);
    }

    /**
     * A key this plugin declared in its own app-level {@code settings:}, as saved in its settings file now: the saved
     * value when not blank (a secret decrypted), else a non-secret's declared default. The file is read on each call,
     * which suits the one use, checking settings before the restart that applies them.
     */
    @Override
    public Optional<String> savedApp(String key) {
        PluginSettingDef def = key == null ? null : appSchema.get(key);
        if (def == null) {
            return Optional.empty();
        }
        String raw = savedAppValues.apply(key);
        if (isSecret(def)) {
            return reveal(raw, "the application (saved, not yet applied)",
                    PathSafety.KEY_SEPARATOR + "saved" + PathSafety.KEY_SEPARATOR + key, key);
        }
        if (raw != null && !raw.isBlank()) {
            return Optional.of(raw);
        }
        Object fallback = def.defaultValue();
        return fallback == null ? Optional.empty() : Optional.of(String.valueOf(fallback));
    }

    /** A stored secret in clear: empty when blank or undecryptable, a non-{@code ENC(...)} value unchanged. */
    private Optional<String> reveal(String value, String scope, String warnKey, String key) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (cipher == null || !cipher.isEncrypted(value)) {
            return Optional.of(value); // a hand-written plaintext secret still works, as it always has
        }
        if (!cipher.isAvailable()) {
            warnOnce(warnKey, key, scope, "it is encrypted but the platform secret key (runway.security.secret-key) "
                    + "is not usable (unset, or itself in the ENC(...) form)");
            return Optional.empty();
        }
        try {
            return Optional.of(cipher.decrypt(value));
        } catch (RuntimeException failure) {
            // Never log the value or the cause's message (it may echo ciphertext); the type is enough to act on.
            warnOnce(warnKey, key, scope, "it could not be decrypted (" + failure.getClass().getSimpleName()
                    + ") — wrong platform secret key, or a corrupted value");
            return Optional.empty();
        }
    }

    private void warnOnce(String warnKey, String key, String scope, String why) {
        if (warned.add(warnKey)) {
            log.warn("plugin '{}' secret setting '{}' for {} is unavailable: {}", pluginId, key, scope, why);
        }
    }

    private static boolean isSecret(PluginSettingDef def) {
        return SettingType.of(def.type()) == SettingType.SECRET;
    }

    private static void requireSafe(String siteId) {
        if (siteId == null || !PathSafety.isSafeId(siteId)) {
            throw new IllegalArgumentException("unsafe site id: " + siteId);
        }
    }
}
