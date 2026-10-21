package mx.quest.runway.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Off-host backup mirroring (+ its retention) and the scheduled all-sites runner. */
class ScheduledAndOffsiteBackupTest {

    @TempDir
    Path external;
    @TempDir
    Path offsite;
    private StorageLayout layout;
    private SiteStore sites;

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        props.setBackups(external.resolve("backups"));
        layout = StorageLayout.from(props);
        sites = new SiteStore(layout, new YamlStore());
        seedSite("demo");
    }

    private void seedSite(String id) throws IOException {
        Path siteDir = layout.siteDir(id);
        Files.createDirectories(siteDir.resolve("templates"));
        Files.writeString(siteDir.resolve("templates/_master.html"), "MASTER", StandardCharsets.UTF_8);
        sites.save(new Site(id, id, List.of(id + ".test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE));
    }

    private BackupService service(String offsiteDir, int maxCount) {
        BackupProperties props = new BackupProperties();
        props.setOffsiteDir(offsiteDir);
        props.getRetention().setMaxCount(maxCount);
        return new BackupService(layout, sites, new BackupParticipantRegistry(List.of()), event -> { }, props);
    }

    @Test
    void aCreatedBackupIsMirroredOffHost() throws IOException {
        BackupService svc = service(offsite.toString(), 0);
        String name = svc.create("demo");

        assertTrue(Files.exists(external.resolve("backups").resolve("demo").resolve(name)), "local copy exists");
        assertTrue(Files.exists(offsite.resolve("demo").resolve(name)), "off-host copy exists");
    }

    @Test
    void offHostCopiesArePrunedByRetention() throws IOException {
        // Seed three older off-host copies by hand, then create one under maxCount=2.
        Path offDir = offsite.resolve("demo");
        Files.createDirectories(offDir);
        for (int i = 1; i <= 3; i++) {
            Files.writeString(offDir.resolve("backup-20260101-00000" + i + "-000.zip"), "OLD");
        }
        BackupService svc = service(offsite.toString(), 2);
        svc.create("demo");

        long remaining;
        try (var s = Files.list(offDir)) {
            remaining = s.filter(p -> p.getFileName().toString().endsWith(".zip")).count();
        }
        assertEquals(2, remaining, "off-host copies are capped at maxCount (newest kept)");
    }

    @Test
    void anUnwritableOffHostTargetDoesNotFailTheLocalBackup() throws IOException {
        // Point the off-host dir under an existing regular FILE, so createDirectories cannot succeed.
        Path blocker = external.resolve("a-file");
        Files.writeString(blocker, "x");
        BackupService svc = service(blocker.resolve("nope").toString(), 0);

        String name = svc.create("demo"); // must not throw
        assertTrue(Files.exists(external.resolve("backups").resolve("demo").resolve(name)),
                "the local backup still succeeds when the off-host mirror fails");
    }

    @Test
    void withNoOffHostConfiguredNothingIsMirrored() throws IOException {
        BackupService svc = service("", 0);
        svc.create("demo");
        assertTrue(Files.list(offsite).findAny().isEmpty(), "off-host dir untouched when unconfigured");
    }

    @Test
    void theScheduledRunnerBacksUpEverySite() throws IOException {
        seedSite("blog");
        BackupService svc = service("", 0);
        ScheduledBackupRunner runner = new ScheduledBackupRunner(svc, sites);

        int ok = runner.runAll();

        assertEquals(2, ok, "both sites were backed up");
        assertEquals(1, svc.list("demo").size());
        assertEquals(1, svc.list("blog").size());
    }
}
