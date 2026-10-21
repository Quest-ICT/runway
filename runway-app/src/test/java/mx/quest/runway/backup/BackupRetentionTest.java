package mx.quest.runway.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import mx.quest.runway.backup.BackupService.StoredBackup;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stored-backup retention: a new backup prunes the site's older ones per the count/age policy. */
class BackupRetentionTest {

    @TempDir
    Path external;
    private StorageLayout layout;
    private Path backupsDir;

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        props.setBackups(external.resolve("backups"));
        layout = StorageLayout.from(props);
        Path siteDir = layout.siteDir("demo");
        Files.createDirectories(siteDir.resolve("templates"));
        Files.writeString(siteDir.resolve("site.yml"), "id: demo\nname: Demo\n", StandardCharsets.UTF_8);
        Files.writeString(siteDir.resolve("templates/_master.html"), "MASTER", StandardCharsets.UTF_8);
        backupsDir = layout.backups().resolve("demo");
        Files.createDirectories(backupsDir);
    }

    private BackupService serviceWith(int maxCount, int maxAgeDays) {
        SiteStore sites = new SiteStore(layout, new YamlStore());
        BackupProperties props = new BackupProperties();
        props.getRetention().setMaxCount(maxCount);
        props.getRetention().setMaxAgeDays(maxAgeDays);
        return new BackupService(layout, sites, new BackupParticipantRegistry(List.of()), event -> { }, props);
    }

    /** Seeds a stored backup file with a fixed lexical stamp and last-modified time. */
    private void seed(String stamp, Instant modified) throws IOException {
        Path f = backupsDir.resolve("backup-" + stamp + ".zip");
        Files.writeString(f, "OLD", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(f, FileTime.from(modified));
    }

    @Test
    void countBasedRetentionKeepsOnlyTheNewestN() throws IOException {
        for (int i = 1; i <= 5; i++) {
            seed("20260101-00000" + i + "-000", Instant.now()); // older lexical stamps than the fresh one
        }
        BackupService svc = serviceWith(3, 0);

        String fresh = svc.create("demo"); // writes a 2026-07-ish stamp, then prunes

        List<StoredBackup> kept = svc.list("demo");
        assertEquals(3, kept.size(), "count retention should cap at maxCount");
        assertTrue(kept.stream().anyMatch(b -> b.name().equals(fresh)), "the just-created backup is never pruned");
        assertFalse(kept.stream().anyMatch(b -> b.name().equals("backup-20260101-000001-000.zip")),
                "the oldest backup should be pruned");
    }

    @Test
    void ageBasedRetentionDropsBackupsOlderThanMaxAge() throws IOException {
        seed("20260101-000001-000", Instant.now().minus(10, ChronoUnit.DAYS)); // stale
        seed("20260101-000002-000", Instant.now()); // recent
        BackupService svc = serviceWith(0, 3); // age-only: keep <= 3 days

        svc.create("demo");

        List<StoredBackup> kept = svc.list("demo");
        assertFalse(kept.stream().anyMatch(b -> b.name().equals("backup-20260101-000001-000.zip")),
                "a backup older than maxAgeDays should be pruned");
        assertTrue(kept.stream().anyMatch(b -> b.name().equals("backup-20260101-000002-000.zip")),
                "a recent backup should be kept");
    }

    @Test
    void retentionOffLeavesEveryBackupInPlace() throws IOException {
        for (int i = 1; i <= 4; i++) {
            seed("20260101-00000" + i + "-000", Instant.now().minus(100, ChronoUnit.DAYS));
        }
        BackupService svc = serviceWith(0, 0); // both dimensions off

        svc.create("demo");

        assertEquals(5, svc.list("demo").size(), "with retention off nothing is pruned");
    }
}
