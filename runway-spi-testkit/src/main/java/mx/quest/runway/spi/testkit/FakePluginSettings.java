package mx.quest.runway.spi.testkit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import mx.quest.runway.spi.model.SettingsView;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * An in-memory {@link PluginSettings} for plugin unit tests. Configure declared defaults once and per-site values
 * per test; {@link #site} answers defaults overlaid by the site's values, exactly like the kernel's (a stored blank
 * string does not mask a default), and {@link #secret} answers the clear value you set.
 *
 * <pre>{@code
 * FakePluginSettings settings = new FakePluginSettings()
 *         .defaults(Map.of("enabled", true, "limit", 20))
 *         .site("acme", Map.of("limit", 50))
 *         .secret("acme", "smtpPassword", "s3cret");
 * MyWidget widget = new MyWidget(settings);
 * }</pre>
 *
 * Thread-safe. Like the kernel, an unsafe site id (one that could not be a folder name) throws
 * {@link IllegalArgumentException}.
 */
public final class FakePluginSettings implements PluginSettings {

    /** The kernel's site-id rule: a letter or digit, then letters, digits, {@code . _ -}. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private volatile Map<String, Object> defaults = Map.of();
    private final Map<String, Map<String, Object>> sites = new ConcurrentHashMap<>();
    private final Map<String, String> secrets = new ConcurrentHashMap<>();

    /** The declared {@code siteSettings:} defaults (replaces any set before). */
    public FakePluginSettings defaults(Map<String, ?> declaredDefaults) {
        this.defaults = Map.copyOf(declaredDefaults);
        return this;
    }

    /** The values stored for {@code siteId} (replaces any set before). */
    public FakePluginSettings site(String siteId, Map<String, ?> stored) {
        sites.put(requireSafe(siteId), new LinkedHashMap<>(stored));
        return this;
    }

    /** A secret's clear value for {@code siteId}. */
    public FakePluginSettings secret(String siteId, String key, String clearValue) {
        secrets.put(requireSafe(siteId) + "|" + key, clearValue);
        return this;
    }

    @Override
    public SettingsView site(String siteId) {
        Map<String, Object> values = new LinkedHashMap<>(defaults);
        sites.getOrDefault(requireSafe(siteId), Map.of()).forEach((key, value) -> {
            boolean blankOverDefault = value instanceof String text && text.isBlank() && defaults.containsKey(key);
            if (value != null && !blankOverDefault) {
                values.put(key, value);
            }
        });
        return new SettingsView(values);
    }

    @Override
    public Optional<String> secret(String siteId, String key) {
        String value = secrets.get(requireSafe(siteId) + "|" + key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static String requireSafe(String siteId) {
        if (siteId == null || siteId.equals(".") || siteId.equals("..") || !SAFE_ID.matcher(siteId).matches()) {
            throw new IllegalArgumentException("unsafe site id: " + siteId);
        }
        return siteId;
    }
}
