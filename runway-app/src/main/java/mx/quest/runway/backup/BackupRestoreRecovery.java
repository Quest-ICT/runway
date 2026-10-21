package mx.quest.runway.backup;

import java.io.IOException;
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
            Path old = workDir.resolve("old");
            Path siteDir = layout.siteDir(siteId);
            if (Files.exists(old) && !Files.exists(siteDir)) {
                try {
                    Files.move(old, siteDir);
                    log.warn("recovered site '{}' from an interrupted restore", siteId);
                } catch (IOException e) {
                    // Recovery failed — DO NOT delete this work dir; `old` is the only copy of the site.
                    log.error("could not recover site '{}' automatically — its content is preserved at {}",
                            siteId, old, e);
                    continue;
                }
            }
            BackupService.deleteRecursively(workDir); // recovered or not needed — the leftovers are safe to drop
        }
    }
}
