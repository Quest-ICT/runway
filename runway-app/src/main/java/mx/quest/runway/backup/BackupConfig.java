package mx.quest.runway.backup;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables {@link BackupProperties} so {@link BackupService} can apply the stored-backup retention policy.
 * {@link BackupService} itself is a {@code @Service}; this only publishes the bound properties bean.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BackupProperties.class)
public class BackupConfig {
}
