package mx.quest.runway.spi.service;

import java.util.Optional;
import mx.quest.runway.spi.model.SettingsView;

/**
 * The calling plugin's own per-site settings — the values an operator saves on the plugin's per-site settings
 * form ({@code siteSettings:} in {@code plugin.yml}), stored in {@code sites/<siteId>/plugins/<pluginId>.yml}.
 *
 * <h2>How a plugin gets one</h2>
 * By ordinary Spring injection into a class from the plugin's <b>own jar</b> — a constructor or {@code @Bean}
 * method parameter, a field, or an {@code ObjectProvider<PluginSettings>}. The host binds the instance to the
 * plugin <b>whose jar the injecting class was loaded from</b>; no method takes a plugin id, so a plugin can only
 * ever read its own settings. Injection fails fast (the plugin does not start) from a class in the plugin's
 * {@code lib/} folder, from kernel code, and through a bare {@code getBean(PluginSettings.class)} (which has no
 * injection point to bind by). Binding happens at injection, so the instance keeps answering for the right
 * plugin on any thread, later.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Thread-safe. No method returns {@code null}.</li>
 *   <li>An unsafe site id (one that could not be a folder name) throws {@link IllegalArgumentException} before
 *       any I/O. A well-formed id of a site that does not exist answers the declared defaults.</li>
 *   <li>Changes apply immediately: a save on the settings form evicts the cached view on every node, and the
 *       next call returns the new values.</li>
 * </ul>
 *
 * <p>App-level (platform-wide) plugin settings ({@code settings:} in {@code plugin.yml}) bind through Spring
 * {@code @ConfigurationProperties} under {@code runway.plugin.<id>.*}, which is descriptor contract. The one
 * exception is a {@code secret}-typed app-level setting: it binds there only in its stored {@code ENC(...)} form, so
 * {@link #appSecret(String)} hands it out in clear (since SPI 1.1.0). Those bindings are the values the running
 * application started with; {@link #savedApp(String)} reads what is saved now, which a restart will apply (since SPI
 * 1.2.0).
 *
 * @since SPI 0.4.0
 */
public interface PluginSettings {

    /**
     * The calling plugin's effective settings for {@code siteId}: the declared {@code siteSettings:} defaults,
     * overlaid by the stored values (a stored blank string does not mask a declared default), plus any stored
     * keys the schema does not declare (preserved on purpose — a schema upgrade never wipes operator data).
     * Secret-typed keys are absent. Cached; never {@code null}.
     *
     * @throws IllegalArgumentException if {@code siteId} is not a safe identifier
     */
    SettingsView site(String siteId);

    /**
     * A {@code secret}-typed setting in clear. A stored {@code ENC(...)} value is decrypted; any other value (a
     * hand-written plaintext) is returned unchanged. Empty when the key is not declared {@code type: secret} in
     * {@code siteSettings:}, when it is unset or blank, when it is encrypted but no platform secret key is
     * configured, or when decryption fails (logged once per site and key, never with the value).
     *
     * @throws IllegalArgumentException if {@code siteId} is not a safe identifier
     */
    Optional<String> secret(String siteId, String key);

    /**
     * A {@code secret}-typed <b>app-level</b> setting of the calling plugin, in clear — the platform-wide twin of
     * {@link #secret(String, String)}, for a credential that belongs to the whole Runway (a database password, an
     * API key) rather than to one site.
     *
     * <p>The value is the one the <b>running</b> application was started with, read from the same environment
     * the plugin's {@code @ConfigurationProperties} bind from: an app-level setting saved on the console applies
     * on the next restart, and so does this answer. A stored {@code ENC(...)} value is decrypted; any other value
     * (a hand-written plaintext) is returned unchanged.
     *
     * <p>Empty when the key is not declared {@code type: secret} in the plugin's own {@code settings:} — a key
     * declared only in {@code siteSettings:} does not count, and no plugin can name another's — when it is unset
     * or blank, when it is encrypted but no platform secret key is configured, or when decryption fails (logged
     * once per key, never with the value).
     *
     * @param key the setting key as declared in {@code settings:}
     * @return the clear value, or empty
     * @since SPI 1.1.0
     */
    default Optional<String> appSecret(String key) {
        return Optional.empty();
    }

    /**
     * An <b>app-level</b> setting of the calling plugin as it is <b>saved now</b>, which may differ from the running
     * value until the restart that applies it. It lets a plugin check settings before that restart, for example by
     * testing a connection with them.
     *
     * <p>The answer is the value stored in the plugin's app-level settings file when it is not blank (a {@code secret}
     * in clear, decrypted as {@link #appSecret(String)} does), otherwise the declared default of a non-secret
     * setting, otherwise empty. It is read from the file on each call, so call it when needed rather than in a loop.
     *
     * <p>Empty when the key is not declared in the plugin's own {@code settings:} (a key declared only in
     * {@code siteSettings:} does not count, and no plugin can name another's), and for an encrypted secret that
     * cannot be decrypted (logged once per key, never with the value).
     *
     * @param key the setting key as declared in {@code settings:}
     * @return the saved value, or empty
     * @since SPI 1.2.0
     */
    default Optional<String> savedApp(String key) {
        return Optional.empty();
    }
}
