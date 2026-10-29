package mx.quest.database.mariadb;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the MariaDB provider; the database plugin collects it by type. */
@Configuration(proxyBeanMethods = false)
public class MariaDbProviderConfig {

    @Bean
    public MariaDbProvider databaseMariadbProvider() {
        return new MariaDbProvider();
    }
}
