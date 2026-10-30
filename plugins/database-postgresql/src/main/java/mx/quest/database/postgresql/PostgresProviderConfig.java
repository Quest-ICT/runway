package mx.quest.database.postgresql;

import mx.quest.database.spi.ProviderSettings;
import mx.quest.database.spi.ProviderSettingsProps;
import mx.quest.runway.spi.service.PluginSettings;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the PostgreSQL provider; the database plugin collects it by type. The server's settings are this plugin's
 * own app-level settings ({@code runway.plugin.database-postgresql.*}, since database 2.0.0): the running ones as
 * bound at startup, the password through {@link PluginSettings#appSecret}, and the saved ones through
 * {@link PluginSettings#savedApp} for the connection test. With both providers installed the context holds two
 * {@link ProviderSettingsProps} beans, hence the qualifier.
 */
@Configuration(proxyBeanMethods = false)
public class PostgresProviderConfig {

    @Bean
    @ConfigurationProperties("runway.plugin.database-postgresql")
    public ProviderSettingsProps databasePostgresqlSettings() {
        return new ProviderSettingsProps();
    }

    @Bean
    public PostgresProvider databasePostgresqlProvider(
            @Qualifier("databasePostgresqlSettings") ProviderSettingsProps server, PluginSettings settings) {
        return new PostgresProvider(() -> server.toSettings(settings.appSecret(ProviderSettings.PASSWORD)),
                () -> ProviderSettings.read(settings::savedApp));
    }
}
