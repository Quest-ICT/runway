package mx.quest.runway.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the storage layer into the kernel: binds {@link StorageProperties}, exposes the resolved
 * {@link StorageLayout}, and the shared {@link YamlStore}. Always active — the kernel is always
 * filesystem-backed.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    /** The environment key of the plugin folder override (read before startup by the plugin loader too). */
    static final String PLUGINS_ROOT_PROPERTY = "runway.plugins.root";

    /**
     * The resolved layout. {@code runway.plugins.root} is read from the environment exactly as the plugin loader
     * reads it before the context starts, so the console installs where the loader discovers.
     */
    @Bean
    public StorageLayout storageLayout(StorageProperties properties, Environment environment) {
        return StorageLayout.from(properties, environment.getProperty(PLUGINS_ROOT_PROPERTY));
    }

    @Bean
    public YamlStore yamlStore() {
        return new YamlStore();
    }

    /** One startup warning per retired {@code runway.storage.*} key still set (they have no effect). */
    @Bean
    public RetiredStorageKeys retiredStorageKeys(Environment environment) {
        return new RetiredStorageKeys(environment);
    }
}
