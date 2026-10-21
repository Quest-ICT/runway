package mx.quest.runway.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import mx.quest.runway.backup.ScheduledBackupRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * With automatic backups on, the backup runner's cron is the one kernel task scheduled — registered once, on the
 * same platform scheduler (the backup configuration no longer brings its own {@code @EnableScheduling}).
 */
@SpringBootTest(properties = {"runway.backups.schedule.enabled=true", "runway.backups.schedule.cron=0 0 3 * * *"})
class SchedulingWithBackupsTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void theBackupCronIsTheOnlyKernelTask() {
        assertEquals(Set.of(ScheduledBackupRunner.class.getName() + ".runAll"),
                SchedulingConfigTest.scheduledMethods(context));
    }
}
