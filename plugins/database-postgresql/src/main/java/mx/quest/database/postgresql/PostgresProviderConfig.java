package mx.quest.database.postgresql;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the PostgreSQL provider; the database plugin collects it by type. */
@Configuration(proxyBeanMethods = false)
public class PostgresProviderConfig {

    @Bean
    public PostgresProvider databasePostgresqlProvider() {
        return new PostgresProvider();
    }
}
