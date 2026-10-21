package mx.quest.runway.backup;

import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the automatic all-sites backup on the configured cron ({@code runway.backups.schedule.cron}). Each site is
 * backed up <b>in isolation</b> — one site's failure is logged and does not stop the others — and the retention
 * policy + optional off-host mirror apply to each just as a manual backup does (this reuses
 * {@link BackupService#create(String)}). Created only when {@code runway.backups.schedule.enabled=true} (see
 * {@link ScheduledBackupConfig}), so no backup is scheduled when automatic backups are off. It runs on the
 * platform's shared scheduler pool ({@link mx.quest.runway.platform.SchedulingConfig}).
 */
public class ScheduledBackupRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduledBackupRunner.class);

    private final BackupService backups;
    private final SiteStore sites;

    public ScheduledBackupRunner(BackupService backups, SiteStore sites) {
        this.backups = backups;
        this.sites = sites;
    }

    /**
     * Backs up every configured site. Invoked by Spring's scheduler on the cron; also callable directly (tests).
     *
     * @return the number of sites successfully backed up this run
     */
    @Scheduled(cron = "${runway.backups.schedule.cron:0 30 2 * * *}")
    public int runAll() {
        java.util.List<Site> all = sites.findAll();
        int ok = 0;
        int failed = 0;
        for (Site site : all) {
            try {
                backups.create(site.id());
                ok++;
            } catch (Exception e) { // isolate: one site's failure must not stop the scheduled run
                failed++;
                log.warn("scheduled backup failed for site '{}': {}", site.id(), e.toString());
            }
        }
        log.info("scheduled backup run complete: {} succeeded, {} failed (of {} site(s))", ok, failed, all.size());
        return ok;
    }
}
