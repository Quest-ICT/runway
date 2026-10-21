package mx.quest.runway.platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.crypto.AesGcmSecretCipher;
import mx.quest.runway.plugin.PlaceholderText;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.SettingType;
import mx.quest.runway.security.RunwaySecurityProperties;
import mx.quest.runway.storage.Yaml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Loads the console-managed kernel settings ({@code settings:} in {@code external/platform.yml}) as a property source,
 * before the context refreshes, so every {@code @ConfigurationProperties} bean binds them like any other value.
 *
 * <h2>Rank</h2>
 * Just above the shipped defaults (the classpath {@code application.yml}) and below everything the host sets: its
 * {@code external/application.yml}, environment variables, system properties and command-line arguments. So the
 * host's value always wins, and {@link HostSettings} can tell the form which fields that
 * locks.
 *
 * <h2>Only declared keys</h2>
 * A key {@link KernelSettings} does not declare is ignored with a warning: the file is the console's, and it must
 * never become a way to set any property. A stored secret ({@code ENC(...)}) is decrypted here with the host's
 * {@code runway.security.secret-key}; without the key it is left out, with a warning, rather than bound encrypted.
 *
 * <h2>Never a placeholder</h2>
 * The values bind like any other property, so a stored {@code ${...}} would be resolved: one setting could pull in
 * another property, such as the master key, and send it out in a public header. The console refuses <code>${</code> on
 * save ({@link KernelSettingsService}); a value that reaches the file another way (a hand edit, an older release) is
 * escaped here ({@link PlaceholderText}), so it reads as the text written: a <code>${</code> is never resolved. (A
 * {@code #{...}} is not a placeholder; it matters only to {@code @Value} and {@code @Scheduled}, and their keys here
 * are numbers or a cron, which the console checks on save.)
 */
public class KernelSettingsInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext>,
        Ordered {

    /** The property source's name. */
    public static final String SOURCE = "platform-settings";

    private static final Logger log = LoggerFactory.getLogger(KernelSettingsInitializer.class);
    private static final String EXTERNAL_PROPERTY = "runway.storage.external";

    @Override
    public int getOrder() {
        // Unordered, like the plugin loader: the test framework's own initializers (dynamic properties such as the
        // data folder) must have run first, and an earlier order ran before them and read the default folder.
        // Nothing reads these values before the context refreshes, so running late costs nothing.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment env = context.getEnvironment();
        Path file = Path.of(env.getProperty(EXTERNAL_PROPERTY, "external"), "platform.yml");
        Map<String, Object> values = load(file, env);
        if (values.isEmpty()) {
            return;
        }
        install(env.getPropertySources(), new MapPropertySource(SOURCE, values));
        log.info("loaded {} console-managed kernel setting(s) from {}", values.size(), file);
    }

    /** Puts {@code source} just above the shipped defaults, else last. */
    static void install(MutablePropertySources sources, PropertySource<?> source) {
        for (PropertySource<?> existing : sources) {
            if (HostSettings.isShippedDefaults(existing)) {
                sources.addBefore(existing.getName(), source);
                return;
            }
        }
        sources.addLast(source);
    }

    /** The declared settings stored in {@code file}, ready to bind (secrets decrypted); empty when none. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> load(Path file, ConfigurableEnvironment env) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(file)) {
            root = Yaml.read(in, Map.class);
        } catch (IOException | RuntimeException e) {
            log.warn("could not read {} — console-managed kernel settings not loaded: {}", file, e.toString());
            return Map.of();
        }
        if (root == null || !(root.get("settings") instanceof Map<?, ?> stored)) {
            return Map.of();
        }
        Map<String, PluginSettingDef> declared = KernelSettings.all();
        Map<String, Object> out = new LinkedHashMap<>();
        AesGcmSecretCipher cipher = null;
        for (Map.Entry<?, ?> entry : ((Map<Object, Object>) stored).entrySet()) {
            String key = String.valueOf(entry.getKey());
            PluginSettingDef def = declared.get(key);
            if (def == null) {
                log.warn("{}: ignoring settings key '{}': not a console-managed kernel setting", file, key);
                continue;
            }
            Object value = entry.getValue();
            String text = value instanceof List<?> list ? String.join(",", list.stream().map(String::valueOf).toList())
                    : String.valueOf(value);
            if (SettingType.of(def.type()) == SettingType.SECRET) {
                if (cipher == null) {
                    RunwaySecurityProperties security = new RunwaySecurityProperties();
                    security.setSecretKey(env.getProperty("runway.security.secret-key"));
                    cipher = new AesGcmSecretCipher(security);
                }
                if (cipher.isEncrypted(text)) {
                    if (!cipher.isAvailable()) {
                        log.warn("{}: '{}' is stored encrypted and runway.security.secret-key is not usable (unset, "
                                + "or itself in the ENC(...) form); not loaded", file, key);
                        continue;
                    }
                    try {
                        text = cipher.decrypt(text);
                    } catch (RuntimeException unreadable) {
                        // A wrong key or a corrupted value: start without this setting, as with no key at all,
                        // rather than refuse to start over one console-managed value.
                        log.warn("{}: '{}' cannot be decrypted (wrong runway.security.secret-key or corrupted "
                                + "value); not loaded", file, key);
                        continue;
                    }
                }
            }
            if (text.contains(KernelSettingsService.PLACEHOLDER)) {
                log.warn("{}: '{}' contains '${' — kept as literal text, the '${' is never resolved", file, key);
                text = literal(text);
            }
            out.put(key, text);
        }
        return out;
    }

    /**
     * {@code text} escaped so the {@code Environment} and the binder read it exactly as written: each <code>${</code>
     * Spring would resolve becomes <code>\${</code>, and an unterminated one is left alone ({@link PlaceholderText}).
     */
    static String literal(String text) {
        return PlaceholderText.literal(text);
    }
}
