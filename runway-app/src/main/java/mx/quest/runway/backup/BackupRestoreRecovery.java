package mx.quest.runway.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import mx.quest.runway.storage.StorageLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Recovers from a restore interrupted mid-swap (a crash / power loss between {@code siteDir → old} and
 * {@code staging → siteDir}). At that instant the live site is momentarily absent while its only copy sits in
 * {@code sites/.restore-work/<id>/old}; on the next boot this sweep <b>rolls the interrupted rollback
 * forward</b> — for any work dir whose site is missing, it moves {@code old} back into place — then clears the
 * leftover scratch. A work dir whose recovery move itself fails is left untouched (its {@code old} preserved
 * for manual recovery), never deleted.
 *
 * <p>The site's logs and stats ride in the work dir until a restore has swapped and merged them, so the sweep
 * finishes that too, with the same routine the restore uses ({@link BackupService#settle}): a site folder holding no
 * {@code site.yml} — one a log write recreated mid-swap — is never mistaken for the site.
 *
 * <p>The scratch lives under {@code sites/.restore-work/}, a hidden name {@link
 * mx.quest.runway.storage.PathSafety#isSafeId} rejects, so it is never mistaken for a tenant.
 */
@Component
public class BackupRestoreRecovery {

    private static final Logger log = LoggerFactory.getLogger(BackupRestoreRecovery.class);

    private final StorageLayout layout;

    public BackupRestoreRecovery(StorageLayout layout) {
        this.layout = layout;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        Path workRoot = layout.sites().resolve(BackupService.WORK_DIR);
        if (!Files.isDirectory(workRoot)) {
            return;
        }
        List<Path> workDirs;
        try (Stream<Path> ids = Files.list(workRoot)) {
            workDirs = ids.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            log.warn("restore-recovery sweep could not list {}", workRoot, e);
            return;
        }
        for (Path workDir : workDirs) {
            String siteId = workDir.getFileName().toString();
            Path siteDir = layout.siteDir(siteId);
            try {
                if (BackupService.settle(siteDir, workDir)) {
                    log.warn("recovered site '{}' from an interrupted restore", siteId);
                }
            } catch (IOException | UncheckedIOException e) {
                // Recovery failed — DO NOT delete this work dir: it may hold the only copy of the site, or its logs.
                log.error("could not recover site '{}' automatically — its content is preserved at {}",
                        siteId, workDir, e);
                continue;
            }
            BackupService.deleteRecursively(workDir); // settled — the leftovers (a staging copy) are safe to drop
        }
    }
}
