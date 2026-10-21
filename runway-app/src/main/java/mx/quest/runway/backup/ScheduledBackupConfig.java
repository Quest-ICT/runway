package mx.quest.runway.backup;

import mx.quest.runway.site.SiteStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the automatic-backup runner <b>only when opted in</b> ({@code runway.backups.schedule.enabled=true}). When
 * off (the default) the {@link ScheduledBackupRunner} does not exist, so no backup is ever scheduled — automatic
 * backups are strictly opt-in, and a manual console backup is always available regardless.
 *
 * <p>Scheduling itself is enabled platform-wide by {@link mx.quest.runway.platform.SchedulingConfig}; it used to be
 * enabled here, which left every other {@code @Scheduled} method (plugins' periodic work) dead unless automatic
 * backups were on.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "runway.backups.schedule", name = "enabled", havingValue = "true")
public class ScheduledBackupConfig {

    @Bean
    public ScheduledBackupRunner scheduledBackupRunner(BackupService backups, SiteStore sites) {
        return new ScheduledBackupRunner(backups, sites);
    }
}
