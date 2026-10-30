package mx.quest.runway.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BH — the startup crash-recovery sweep: an interrupted restore (site swapped out to {@code .restore-work/<id>/old}
 * but the staged copy never swapped in) is rolled forward; a work dir left beside a live site is merely cleared.
 */
class BackupRestoreRecoveryTest {

    @TempDir
    Path external;
    private StorageLayout layout;
    private BackupRestoreRecovery recovery;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        layout = StorageLayout.from(props);
        recovery = new BackupRestoreRecovery(layout);
    }

    private Path workOld(String siteId) {
        return layout.sites().resolve(BackupService.WORK_DIR).resolve(siteId).resolve("old");
    }

    @Test
    void anInterruptedRestoreIsRolledForwardOnStartup() throws IOException {
        // A crash between (siteDir -> old) and (staging -> siteDir): the site is gone, its only copy is `old`.
        Path old = workOld("demo");
        Files.createDirectories(old);
        Files.writeString(old.resolve("site.yml"), "id: demo\n", StandardCharsets.UTF_8);
        assertFalse(Files.exists(layout.siteDir("demo")), "precondition: the live site is absent");

        recovery.recover();

        assertEquals("id: demo\n", Files.readString(layout.siteDir("demo").resolve("site.yml"), StandardCharsets.UTF_8),
                "the site is rolled forward from the preserved copy");
        assertFalse(Files.exists(layout.sites().resolve(BackupService.WORK_DIR).resolve("demo")),
                "the scratch work dir is cleared");
    }

    @Test
    void leftoverScratchBesideALiveSiteIsClearedWithoutOverwritingIt() throws IOException {
        // The live site exists AND a stale work dir lingers (the swap completed, cleanup did not).
        Path siteDir = layout.siteDir("demo");
        Files.createDirectories(siteDir);
        Files.writeString(siteDir.resolve("site.yml"), "id: demo\nname: LIVE\n", StandardCharsets.UTF_8);
        Path old = workOld("demo");
        Files.createDirectories(old);
        Files.writeString(old.resolve("site.yml"), "id: demo\nname: STALE\n", StandardCharsets.UTF_8);

        recovery.recover();

        assertEquals("id: demo\nname: LIVE\n", Files.readString(siteDir.resolve("site.yml"), StandardCharsets.UTF_8),
                "the live site is never overwritten by leftover scratch");
        assertFalse(Files.exists(layout.sites().resolve(BackupService.WORK_DIR).resolve("demo")),
                "the scratch work dir is cleared");
    }

    /** The swap completed but the crash came before the logs were carried: they are merged, older lines first. */
    @Test
    void aCompletedSwapGetsItsLogsMergedOnStartup() throws IOException {
        Path siteDir = layout.siteDir("demo");
        Files.createDirectories(siteDir.resolve("logs"));
        Files.writeString(siteDir.resolve("site.yml"), "id: demo\nname: RESTORED\n", StandardCharsets.UTF_8);
        Files.writeString(siteDir.resolve("logs/audit-2026-10-30.log"), "AFTER\n", StandardCharsets.UTF_8);
        Path old = workOld("demo");
        Files.createDirectories(old.resolve("logs"));
        Files.writeString(old.resolve("site.yml"), "id: demo\nname: BEFORE\n", StandardCharsets.UTF_8);
        Files.writeString(old.resolve("logs/audit-2026-10-30.log"), "BEFORE\n", StandardCharsets.UTF_8);
        Files.writeString(old.resolve("logs/audit-2026-10-29.log"), "YESTERDAY\n", StandardCharsets.UTF_8);

        recovery.recover();

        assertEquals("id: demo\nname: RESTORED\n", Files.readString(siteDir.resolve("site.yml")), "the restore stands");
        assertEquals("BEFORE\nAFTER\n", Files.readString(siteDir.resolve("logs/audit-2026-10-30.log")),
                "a day both have is joined in order");
        assertEquals("YESTERDAY\n", Files.readString(siteDir.resolve("logs/audit-2026-10-29.log")));
        assertFalse(Files.exists(layout.sites().resolve(BackupService.WORK_DIR).resolve("demo")));
    }

    /**
     * A log write recreated the site folder between the two renames, and the process died: that folder holds only
     * logs. It must not count as the site (which would delete `old`, the only copy); the site is rolled forward and
     * the late lines follow its own.
     */
    @Test
    void aSiteFolderHoldingOnlyLateLogsIsNotMistakenForTheSite() throws IOException {
        Path siteDir = layout.siteDir("demo");
        Files.createDirectories(siteDir.resolve("logs"));
        Files.writeString(siteDir.resolve("logs/access-2026-10-30.log"), "LATE\n", StandardCharsets.UTF_8);
        Path old = workOld("demo");
        Files.createDirectories(old.resolve("logs"));
        Files.writeString(old.resolve("site.yml"), "id: demo\n", StandardCharsets.UTF_8);
        Files.writeString(old.resolve("logs/access-2026-10-30.log"), "EARLIER\n", StandardCharsets.UTF_8);

        recovery.recover();

        assertEquals("id: demo\n", Files.readString(siteDir.resolve("site.yml")), "the original site is back");
        assertEquals("EARLIER\nLATE\n", Files.readString(siteDir.resolve("logs/access-2026-10-30.log")));
        assertFalse(Files.exists(layout.sites().resolve(BackupService.WORK_DIR).resolve("demo")));
    }

    /** A rollback put the site back but could not merge the lines written meanwhile: they follow its own. */
    @Test
    void lateLinesLeftByARollbackAreMergedAfterTheSitesOwn() throws IOException {
        Path siteDir = layout.siteDir("demo");
        Files.createDirectories(siteDir.resolve("logs"));
        Files.writeString(siteDir.resolve("site.yml"), "id: demo\n", StandardCharsets.UTF_8);
        Files.writeString(siteDir.resolve("logs/access-2026-10-30.log"), "OWN\n", StandardCharsets.UTF_8);
        Path late = layout.sites().resolve(BackupService.WORK_DIR).resolve("demo").resolve(BackupService.LATE_DIR);
        Files.createDirectories(late.resolve("logs"));
        Files.writeString(late.resolve("logs/access-2026-10-30.log"), "LATE\n", StandardCharsets.UTF_8);

        recovery.recover();

        assertEquals("OWN\nLATE\n", Files.readString(siteDir.resolve("logs/access-2026-10-30.log")));
        assertFalse(Files.exists(layout.sites().resolve(BackupService.WORK_DIR).resolve("demo")));
    }

    @Test
    void noWorkDirIsANoOp() {
        recovery.recover(); // must not throw when sites/.restore-work does not exist
        assertFalse(Files.exists(layout.sites().resolve(BackupService.WORK_DIR)));
    }
}
