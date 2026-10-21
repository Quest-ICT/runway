package mx.quest.runway.platform;

import java.util.Optional;
import mx.quest.runway.plugin.SettingType;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

/**
 * Which settings the server itself sets — in the host's {@code external/application.yml}, an environment variable, a
 * JVM system property or a command-line argument — and so win over the console's value (one home
 * per setting; where the host sets one by hand, the host's wins and the console shows the field locked).
 *
 * <p>Reads only the property sources ranked above the console's own ({@link KernelSettingsInitializer#SOURCE}),
 * using Spring's relaxed names, so {@code RUNWAY_CSP_MODE} counts as setting {@code runway.csp.mode}. The shipped
 * defaults (the classpath {@code application.yml}) rank below and never lock anything.
 */
@Component
public class HostSettings {

    private final ConfigurableEnvironment environment;

    public HostSettings(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    /**
     * Where the server sets {@code key}, described for the operator ("external/application.yml", "an environment
     * variable", …), or empty when it does not.
     */
    public Optional<String> origin(String key) {
        ConfigurationPropertyName name = ConfigurationPropertyName.adapt(key, '.');
        // Only a declared list setting is looked up by its first item: Spring never binds key[0] into a scalar, so a
        // scalar the host wrote as a one-item list sets nothing and must not show locked.
        boolean list = KernelSettings.find(key).map(def -> SettingType.of(def.type()) == SettingType.LIST)
                .orElse(false);
        ConfigurationPropertyName firstItem = list ? ConfigurationPropertyName.adapt(key + "[0]", '.') : null;
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (KernelSettingsInitializer.SOURCE.equals(source.getName()) || isShippedDefaults(source)) {
                return Optional.empty(); // everything from here down ranks below the console's value
            }
            if (source.getName().startsWith("plugin-settings:")) {
                continue; // a plugin's own runway.plugin.<id>.* keys; never a kernel key
            }
            ConfigurationPropertySource adapted = ConfigurationPropertySource.from(source);
            // A list written as a YAML list has indexed keys only (key[0], key[1], also RUNWAY_..._0): the bare key
            // is not there, its first item is. Only an indexed child counts: a named child is another key (a log
            // level for mx.quest.runway does not set the level of mx.quest).
            if (adapted != null && (adapted.getConfigurationProperty(name) != null
                    || (firstItem != null && adapted.getConfigurationProperty(firstItem) != null))) {
                return Optional.of(describe(source));
            }
        }
        return Optional.empty();
    }

    /** Whether the host sets {@code key}. */
    public boolean setOnHost(String key) {
        return origin(key).isPresent();
    }

    static boolean isShippedDefaults(PropertySource<?> source) {
        String name = source.getName();
        return name.contains("class path resource [application.yml]")
                || name.contains("class path resource [application.properties]");
    }

    private static String describe(PropertySource<?> source) {
        String name = source.getName();
        if (name.contains("application.yml") || name.contains("application.properties")) {
            return "external/application.yml";
        }
        if (name.equals("systemEnvironment")) {
            return "an environment variable";
        }
        if (name.equals("systemProperties")) {
            return "a JVM system property (-D)";
        }
        if (name.equals("commandLineArgs")) {
            return "a command-line argument";
        }
        return name;
    }
}
