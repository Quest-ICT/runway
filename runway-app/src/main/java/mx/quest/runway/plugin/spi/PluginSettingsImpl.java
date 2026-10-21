package mx.quest.runway.plugin.spi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final SitePluginConfigService config;
    private final SecretCipher cipher;
    private final Map<String, Memo> memo = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private record Memo(Map<String, Object> stored, SettingsView view) {
    }

    /**
     * @param pluginId     the plugin this instance answers for
     * @param siteSettings that plugin's declared {@code siteSettings:} schema
     * @param config       the per-site settings store
     * @param cipher       decrypts {@code ENC(...)} secrets
     */
    public PluginSettingsImpl(String pluginId, List<PluginSettingDef> siteSettings, SitePluginConfigService config,
                              SecretCipher cipher) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        Map<String, PluginSettingDef> byKey = new LinkedHashMap<>();
        for (PluginSettingDef def : siteSettings) {
            if (def != null && def.key() != null && !def.key().isBlank()) {
                byKey.putIfAbsent(def.key(), def);
            }
        }
        this.schema = Map.copyOf(byKey);
        this.config = Objects.requireNonNull(config, "config");
        this.cipher = cipher;
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
        String value = raw == null ? null : String.valueOf(raw);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (cipher == null || !cipher.isEncrypted(value)) {
            return Optional.of(value); // a hand-written plaintext secret still works, as it always has
        }
        if (!cipher.isAvailable()) {
            warnOnce(siteId, key, "it is encrypted but the platform secret key (runway.security.secret-key) is "
                    + "not usable (unset, or itself in the ENC(...) form)");
            return Optional.empty();
        }
        try {
            return Optional.of(cipher.decrypt(value));
        } catch (RuntimeException failure) {
            // Never log the value or the cause's message (it may echo ciphertext); the type is enough to act on.
            warnOnce(siteId, key, "it could not be decrypted (" + failure.getClass().getSimpleName()
                    + ") — wrong platform secret key, or a corrupted value");
            return Optional.empty();
        }
    }

    private void warnOnce(String siteId, String key, String why) {
        if (warned.add(siteId + PathSafety.KEY_SEPARATOR + key)) {
            log.warn("plugin '{}' secret setting '{}' for site '{}' is unavailable: {}", pluginId, key, siteId, why);
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
