package mx.quest.runway.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.Locale;
import mx.quest.runway.backup.BackupService.StoredBackup;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.BackupParticipant;
import mx.quest.runway.spi.model.BackupReader;
import mx.quest.runway.spi.model.BackupWriter;
import mx.quest.runway.spi.model.SiteRef;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The backup/restore engine: a complete round-trip, participant data, zip-slip safety, stored backups. */
class BackupServiceTest {

    /** A participant that round-trips a blob of out-of-folder data through the archive. */
    private static final class FakeParticipant implements BackupParticipant {
        String exported = "PLUGIN_STATE_V1";
        String imported;

        @Override
        public String key() {
            return "search";
        }

        @Override
        public void export(BackupWriter out, SiteRef site) throws IOException {
            try (OutputStream o = out.put("index.dat")) {
                o.write(exported.getBytes(StandardCharsets.UTF_8));
            }
        }

        @Override
        public void importInto(BackupReader in, SiteRef site) throws IOException {
            if (in.has("index.dat")) {
                try (InputStream i = in.open("index.dat")) {
                    imported = new String(i.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
    }

    @TempDir
    Path external;
    private StorageLayout layout;
    private Path siteDir;
    private FakeParticipant fake;
    private BackupService svc;

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        props.setBackups(external.resolve("backups")); // keep stored backups in the temp tree, not the module dir
        layout = StorageLayout.from(props);
        SiteStore sites = new SiteStore(layout, new YamlStore());
        fake = new FakeParticipant();
        svc = new BackupService(layout, sites, new BackupParticipantRegistry(List.of(fake)), event -> { });
        siteDir = layout.siteDir("demo");
        write(siteDir.resolve("site.yml"), "id: demo\nname: Demo\n");
        write(siteDir.resolve("templates/_master.html"), "MASTER_V1");
        write(siteDir.resolve("users.yml"), "USERS_V1");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** A service over the same layout whose per-entry decompression cap is {@code cap} bytes (zip-bomb tests). */
    private BackupService withEntryCap(long cap) {
        BackupProperties props = new BackupProperties();
        props.setMaxEntryBytes(cap);
        return new BackupService(layout, new SiteStore(layout, new YamlStore()),
                new BackupParticipantRegistry(List.of(fake)), event -> { }, props);
    }

    @Test
    void backupThenRestoreRoundTripsTheWholeSiteAndParticipantData() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("demo", archive);

        // Simulate a mistake: mangle a file, delete another, lose the plugin state.
        write(siteDir.resolve("templates/_master.html"), "MASTER_BROKEN");
        Files.delete(siteDir.resolve("users.yml"));
        fake.imported = null;

        svc.restore("demo", new ByteArrayInputStream(archive.toByteArray()));

        assertEquals("MASTER_V1", read(siteDir.resolve("templates/_master.html")), "content restored");
        assertEquals("USERS_V1", read(siteDir.resolve("users.yml")), "a deleted file is restored");
        assertEquals("id: demo\nname: Demo\n", read(siteDir.resolve("site.yml")), "the site record travels too");
        assertEquals("PLUGIN_STATE_V1", fake.imported, "the participant's out-of-folder data is restored");
    }

    @Test
    void theArchiveHasAManifestSiteTreeAndPluginData() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("demo", archive);
        boolean manifest = false, siteFile = false, pluginData = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals("manifest.yml")) manifest = true;
                if (e.getName().equals("site/templates/_master.html")) siteFile = true;
                if (e.getName().equals("plugin-data/search/index.dat")) pluginData = true;
            }
        }
        assertTrue(manifest, "manifest.yml present");
        assertTrue(siteFile, "site tree under site/");
        assertTrue(pluginData, "participant data under plugin-data/<key>/");
    }

    @Test
    void aZipSlipEntryIsRejectedAndTheSiteSurvives() throws IOException {
        ByteArrayOutputStream evil = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(evil)) {
            z.putNextEntry(new ZipEntry("manifest.yml"));
            z.write("siteId: demo".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("site/../../evil.txt")); // traversal
            z.write("PWNED".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        assertThrows(RuntimeException.class,
                () -> svc.restore("demo", new ByteArrayInputStream(evil.toByteArray())));
        assertFalse(Files.exists(external.resolve("evil.txt")), "the traversal file was never written");
        assertEquals("MASTER_V1", read(siteDir.resolve("templates/_master.html")), "the original site is untouched");
    }

    @Test
    void storedBackupsAreCreatedListedDownloadedAndDeletedSafely() throws IOException {
        String name = svc.create("demo");
        assertTrue(name.startsWith("backup-") && name.endsWith(".zip"));

        List<StoredBackup> list = svc.list("demo");
        assertEquals(1, list.size());
        assertEquals(name, list.get(0).name());
        assertTrue(list.get(0).sizeBytes() > 0);
        assertTrue(svc.file("demo", name).isPresent());

        // Traversal-safe: a malicious name never resolves.
        assertTrue(svc.file("demo", "../../etc/passwd").isEmpty());
        assertFalse(svc.delete("demo", "../evil.zip"));

        assertTrue(svc.delete("demo", name));
        assertTrue(svc.list("demo").isEmpty());
    }

    @Test
    void anArchiveWithoutManifestOrSiteContentIsRejectedAndTheSiteSurvives() throws IOException {
        ByteArrayOutputStream bogus = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bogus)) {
            z.putNextEntry(new ZipEntry("readme.txt")); // not a manifest, not site/, not plugin-data/
            z.write("this is not a runway backup".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        IOException ex = assertThrows(IOException.class,
                () -> svc.restore("demo", new ByteArrayInputStream(bogus.toByteArray())));
        assertTrue(ex.getMessage().contains("valid Runway"), "rejected as not a Runway backup");
        assertEquals("MASTER_V1", read(siteDir.resolve("templates/_master.html")), "the site is untouched");
        assertTrue(Files.exists(siteDir.resolve("users.yml")), "no file was removed");
        assertFalse(Files.exists(layout.sites().resolve(".restore-work").resolve("demo")), "scratch cleaned up");
    }

    @Test
    void anArchiveMissingSiteYmlIsRejectedAndNothingLeaksIntoTheSite() throws IOException {
        ByteArrayOutputStream noRecord = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(noRecord)) {
            z.putNextEntry(new ZipEntry("manifest.yml"));
            z.write(("siteId: demo\nspiVersion: \"0.2.0\"\ncreatedAt: \"2026-01-01T00:00:00Z\"\nparticipants: []\n")
                    .getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("site/templates/orphan.html")); // site content, but no site.yml record
            z.write("ORPHAN".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        assertThrows(IOException.class,
                () -> svc.restore("demo", new ByteArrayInputStream(noRecord.toByteArray())));
        assertEquals("MASTER_V1", read(siteDir.resolve("templates/_master.html")), "the site is untouched");
        assertFalse(Files.exists(siteDir.resolve("templates/orphan.html")), "no staged file reached the live site");
    }

    @Test
    void restoreRejectsAnOversizePluginDataEntryAndTheSiteSurvives() throws IOException {
        // The heap-exhaustion vector: a plugin-data entry read into memory. Tiny compressed, inflates past the cap.
        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bomb)) {
            z.putNextEntry(new ZipEntry("manifest.yml"));
            z.write("siteId: demo\nparticipants: []\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("site/site.yml"));
            z.write("id: demo\nname: Demo\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("plugin-data/search/huge.dat"));
            z.write(new byte[64 * 1024]); // well past the 1 KiB cap below
            z.closeEntry();
        }
        IOException ex = assertThrows(IOException.class,
                () -> withEntryCap(1024).restore("demo", new ByteArrayInputStream(bomb.toByteArray())));
        assertTrue(ex.getMessage().contains("per-entry limit"), "rejected by the per-entry cap");
        assertEquals("MASTER_V1", read(siteDir.resolve("templates/_master.html")), "the live site is untouched");
        assertFalse(Files.exists(layout.sites().resolve(".restore-work").resolve("demo")), "scratch cleaned up");
    }

    @Test
    void restoreRejectsAnOversizeSiteFileAndTheSiteSurvives() throws IOException {
        // The disk-fill vector: a site/ entry streamed to staging. The attacker controls the entry name, so the
        // guard must bound this path too, not only plugin-data.
        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bomb)) {
            z.putNextEntry(new ZipEntry("manifest.yml"));
            z.write("siteId: demo\nparticipants: []\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("site/huge.bin"));
            z.write(new byte[64 * 1024]); // a single site entry over the cap
            z.closeEntry();
        }
        assertThrows(IOException.class,
                () -> withEntryCap(1024).restore("demo", new ByteArrayInputStream(bomb.toByteArray())));
        assertEquals("MASTER_V1", read(siteDir.resolve("templates/_master.html")), "the live site is untouched");
        assertFalse(Files.exists(siteDir.resolve("huge.bin")), "the oversize entry never reached the live site");
        assertFalse(Files.exists(layout.sites().resolve(".restore-work").resolve("demo")), "scratch cleaned up");
    }

    @Test
    void restoreRejectsAnOversizeUnrecognizedEntryAndTheSiteSurvives() throws IOException {
        // An entry named under neither site/ nor plugin-data/ was previously never read, so the next
        // getNextEntry() inflated it whole while skipping — an unbounded zip-bomb past validation. It must be
        // bound-drained and refused by the same per-entry cap.
        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bomb)) {
            z.putNextEntry(new ZipEntry("manifest.yml"));
            z.write("siteId: demo\nparticipants: []\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("site/site.yml"));
            z.write("id: demo\nname: Demo\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("bomb.dat")); // top-level, matches no prefix
            z.write(new byte[64 * 1024]); // past the 1 KiB cap below
            z.closeEntry();
        }
        IOException ex = assertThrows(IOException.class,
                () -> withEntryCap(1024).restore("demo", new ByteArrayInputStream(bomb.toByteArray())));
        assertTrue(ex.getMessage().contains("per-entry limit"), "the unrecognized entry is bound-drained by the cap");
        assertEquals("MASTER_V1", read(siteDir.resolve("templates/_master.html")), "the live site is untouched");
        assertFalse(Files.exists(layout.sites().resolve(".restore-work").resolve("demo")), "scratch cleaned up");
    }

    @Test
    void restoreExtractsASymlinkLikeEntryAsARegularFileNotAFollowedLink() throws IOException {
        // A backup entry that names an out-of-tree target must be materialized as an ordinary file, never a
        // symlink the platform would later follow out of the site folder. ZipInputStream never creates links —
        // this pins that guarantee so a future extraction rewrite can't regress it.
        ByteArrayOutputStream evil = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(evil)) {
            z.putNextEntry(new ZipEntry("manifest.yml"));
            z.write("siteId: demo\nparticipants: []\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("site/site.yml"));
            z.write("id: demo\nname: Demo\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("site/passwd-link"));
            z.write("/etc/passwd".getBytes(StandardCharsets.UTF_8)); // the classic symlink payload
            z.closeEntry();
        }
        svc.restore("demo", new ByteArrayInputStream(evil.toByteArray()));

        Path extracted = siteDir.resolve("passwd-link");
        assertFalse(Files.isSymbolicLink(extracted), "the restore never creates a symlink from an archive entry");
        assertTrue(Files.isRegularFile(extracted, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                "the entry became a regular file");
        assertEquals("/etc/passwd", read(extracted), "…holding the literal bytes, harmless");
    }

    @Test
    void restoreRefusesWhenTheWorkRootIsRedirectedBySymlink(@TempDir Path escape) throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("demo", archive); // a valid backup of the live site

        // Simulate a local attacker who redirected the restore-work root at a symlink to an out-of-tree dir
        // (the TOCTOU window between the work-dir delete and re-create). Extraction must never follow it.
        Path workRoot = layout.sites().resolve(BackupService.WORK_DIR);
        try {
            Files.createSymbolicLink(workRoot, escape);
        } catch (UnsupportedOperationException | IOException noSymlinks) {
            org.junit.jupiter.api.Assumptions.abort("filesystem does not support symlinks");
        }

        assertThrows(IOException.class,
                () -> svc.restore("demo", new ByteArrayInputStream(archive.toByteArray())),
                "a symlink-redirected work root is refused before any entry is extracted");
        assertEquals("MASTER_V1", read(siteDir.resolve("templates/_master.html")), "the live site is untouched");
        assertFalse(Files.exists(escape.resolve("demo").resolve("staging").resolve("site")),
                "no backup content escaped into the symlink target");
    }

    @Test
    void aNonPositiveCapFallsBackToTheDefaultInsteadOfRejectingEveryEntry() throws IOException {
        // A misconfigured cap of 0 must NOT reject every entry (which would break all restores) — it falls back
        // to the 256 MiB default, so a normal small backup still restores.
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(archive)) {
            z.putNextEntry(new ZipEntry("manifest.yml"));
            z.write("siteId: demo\nparticipants: []\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("site/site.yml"));
            z.write("id: demo\nname: Demo\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("site/templates/_master.html"));
            z.write("RESTORED".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        withEntryCap(0).restore("demo", new ByteArrayInputStream(archive.toByteArray()));
        assertEquals("RESTORED", read(siteDir.resolve("templates/_master.html")),
                "a 0 cap behaves as the default, not reject-all");
    }

    @Test
    void aSymlinkInsideTheSiteFolderIsNotArchived() throws IOException {
        Path secret = external.resolve("outside-secret.txt");
        Files.writeString(secret, "TOP_SECRET", StandardCharsets.UTF_8);
        Path link = siteDir.resolve("templates/leak.html");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return; // filesystem without symlink support — nothing to prove here
        }
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("demo", archive);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                assertFalse(e.getName().endsWith("leak.html"), "a symlink must never be followed into a backup");
            }
        }
    }

    @Test
    void createFromBackupClonesTheContentUnderANewIdentity() throws IOException {
        SiteStore store = new SiteStore(layout, new YamlStore());
        store.save(new Site("src", "Source", List.of("src.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), "https://src.test", SiteStatus.ACTIVE));
        write(layout.siteDir("src").resolve("templates/home.html"), "SRC_CONTENT");

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("src", archive);

        Site created = svc.createFromBackup("clone", "Cloned", List.of("clone.test"),
                new ByteArrayInputStream(archive.toByteArray()));

        assertEquals("clone", created.id());
        assertEquals("Cloned", created.name());
        assertEquals(List.of("clone.test"), created.hosts());
        assertEquals(Locale.ENGLISH, created.defaultLocale(), "locale carried over from the backup");
        assertEquals("SRC_CONTENT", read(layout.siteDir("clone").resolve("templates/home.html")), "content cloned");
        // site.yml was rewritten to the new id (a fresh reader sees 'clone', not 'src').
        assertEquals("clone", new SiteStore(layout, new YamlStore()).find("clone").orElseThrow().id());
        // The source site is untouched.
        assertTrue(Files.isRegularFile(layout.siteDir("src").resolve("site.yml")));
    }

    @Test
    void createFromBackupRollsBackAnInvalidBackupLeavingNoOrphan() throws IOException {
        // A site.yml that is a regular file (passes the archive check) but does not deserialize to a Site
        // (no defaultLocale) — the failure happens AFTER the swap, so the half-created folder must be undone.
        write(layout.siteDir("broken").resolve("site.yml"), "id: broken\nname: Broken\n");
        write(layout.siteDir("broken").resolve("templates/home.html"), "X");
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("broken", archive);

        assertThrows(RuntimeException.class, () -> svc.createFromBackup("fresh", "F", List.of(),
                new ByteArrayInputStream(archive.toByteArray())));
        assertFalse(new SiteStore(layout, new YamlStore()).exists("fresh"), "no orphan site remains after rollback");
        assertFalse(Files.exists(layout.siteDir("fresh")), "the half-created folder was removed");
    }

    @Test
    void createFromBackupRefusesAHostAnotherSiteAnswersOnBeforeStagingAnything() throws IOException {
        SiteStore store = new SiteStore(layout, new YamlStore());
        store.save(new Site("src", "Source", List.of("src.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("src", archive);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.createFromBackup("clone", "C", List.of("SRC.test"),
                        new ByteArrayInputStream(archive.toByteArray())));
        assertTrue(ex.getMessage().contains("src"), ex.getMessage());
        assertFalse(Files.exists(layout.siteDir("clone")), "nothing was staged");
        assertThrows(IllegalArgumentException.class, () -> svc.createFromBackup("clone", "C",
                List.of("bad host"), new ByteArrayInputStream(archive.toByteArray())), "a malformed host too");
    }

    @Test
    void aRestoreDropsOnlyTheHostsAnotherSiteWasGivenSinceTheBackup() throws IOException {
        SiteStore store = new SiteStore(layout, new YamlStore());
        store.save(new Site("a", "A", List.of("a.test", "moved.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("a", archive);

        // After the backup, moved.test leaves site a for site b.
        store.save(store.find("a").orElseThrow().withHosts(List.of("a.test")));
        store.save(new Site("b", "B", List.of("moved.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));

        svc.restore("a", new ByteArrayInputStream(archive.toByteArray()));

        SiteStore fresh = new SiteStore(layout, new YamlStore());
        assertEquals(List.of("a.test"), fresh.find("a").orElseThrow().hosts(), "the host b answers on is dropped");
        assertEquals(List.of("moved.test"), fresh.find("b").orElseThrow().hosts(), "b keeps it");
    }

    @Test
    void aSiteManagersRestoreKeepsHowTheSiteIsReachedAPlatformAdminsBringsTheBackupsBack() throws IOException {
        SiteStore store = new SiteStore(layout, new YamlStore());
        store.save(new Site("r", "R", List.of("old.test"), Locale.ENGLISH, List.of(Locale.ENGLISH),
                "https://evil.example", SiteStatus.ACTIVE)); // what an archive written by hand could say
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("r", archive);
        store.save(store.find("r").orElseThrow().withHosts(List.of("r.test")).withBaseUrl(null));

        svc.restore("r", new ByteArrayInputStream(archive.toByteArray()), true); // the site realm
        Site kept = new SiteStore(layout, new YamlStore()).find("r").orElseThrow();
        assertEquals(List.of("r.test"), kept.hosts());
        assertEquals(null, kept.baseUrl());

        svc.restore("r", new ByteArrayInputStream(archive.toByteArray())); // the application realm
        Site back = new SiteStore(layout, new YamlStore()).find("r").orElseThrow();
        assertEquals(List.of("old.test"), back.hosts());
        assertEquals("https://evil.example", back.baseUrl());
    }

    @Test
    void createFromBackupRejectsAnExistingId() throws IOException {
        new SiteStore(layout, new YamlStore()).save(new Site("src", "Source", List.of(), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("src", archive);
        // 'demo' already exists (seeded in setUp) — creating over it must be refused.
        assertThrows(IllegalStateException.class, () -> svc.createFromBackup("demo", "X", List.of(),
                new ByteArrayInputStream(archive.toByteArray())));
    }

    @Test
    void transientLogsAndStatsAreExcludedFromTheBackup() throws IOException {
        write(siteDir.resolve("logs/access-2026-07-02.log"), "SECRET-TRAFFIC");
        write(siteDir.resolve("stats/bandwidth.csv"), "1,2,3");
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("demo", archive);

        boolean sawContent = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                assertFalse(name.contains("/logs/") || name.contains("/stats/"),
                        "transient logs/stats must never ride the content backup: " + name);
                sawContent |= name.endsWith("site.yml");
            }
        }
        assertTrue(sawContent, "real site content is still archived");
    }

    /** A restore replaces the content, not the site's history: its audit log and stats stay as they were. */
    @Test
    void aRestoreKeepsTheLiveSitesLogsAndStats() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("demo", archive);
        write(siteDir.resolve("logs/audit-2026-10-30.log"), "LIVE-AUDIT");
        write(siteDir.resolve("stats/bandwidth.csv"), "LIVE-STATS");
        write(siteDir.resolve("templates/_master.html"), "MASTER_BROKEN");

        svc.restore("demo", new ByteArrayInputStream(archive.toByteArray()));

        assertEquals("MASTER_V1", read(siteDir.resolve("templates/_master.html")), "the content is restored");
        assertEquals("LIVE-AUDIT", read(siteDir.resolve("logs/audit-2026-10-30.log")), "the audit log survives");
        assertEquals("LIVE-STATS", read(siteDir.resolve("stats/bandwidth.csv")), "the stats survive");
    }

    /** A backup never writes logs or stats, so whatever an archive carries under those names is discarded. */
    @Test
    void aRestoreDiscardsLogsAndStatsCarriedByTheArchive() throws IOException {
        ByteArrayOutputStream original = new ByteArrayOutputStream();
        svc.backup("demo", original);
        ByteArrayOutputStream crafted = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(original.toByteArray()));
             ZipOutputStream out = new ZipOutputStream(crafted)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.putNextEntry(new ZipEntry(e.getName()));
                in.transferTo(out);
                out.closeEntry();
            }
            for (String planted : List.of("site/logs/audit-2026-10-30.log", "site/logs/audit-2026-10-29.log",
                    "site/stats/bandwidth.csv")) {
                out.putNextEntry(new ZipEntry(planted));
                out.write("PLANTED".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        // The live site has an audit log of its own, and no stats yet.
        write(siteDir.resolve("logs/audit-2026-10-30.log"), "LIVE-AUDIT");

        svc.restore("demo", new ByteArrayInputStream(crafted.toByteArray()));

        assertEquals("LIVE-AUDIT", read(siteDir.resolve("logs/audit-2026-10-30.log")), "the live log is kept");
        assertFalse(Files.exists(siteDir.resolve("logs/audit-2026-10-29.log")), "no planted day is added");
        assertFalse(Files.exists(siteDir.resolve("stats")), "no planted stats appear");
    }

    private Path work(String siteId) {
        return layout.sites().resolve(BackupService.WORK_DIR).resolve(siteId);
    }

    /** A merge that fails is kept, never deleted, and a repeated run finishes it without joining anything twice. */
    @Test
    void aFailedLogMergeIsKeptAndFinishedByTheNextRun() throws IOException {
        write(siteDir.resolve("logs/audit-2026-10-30.log"), "NEW\n");
        Path old = work("demo").resolve("old");
        write(old.resolve("site.yml"), "id: demo\n");
        write(old.resolve("logs/audit-2026-10-29.log"), "YESTERDAY\n");
        Path unreadable = old.resolve("logs/audit-2026-10-30.log");
        write(unreadable, "OLD\n");
        unreadable.toFile().setReadable(false);
        // As root (some CI containers) a file stays readable whatever its mode: the failure cannot be staged there.
        assumeFalse(Files.isReadable(unreadable), "the file can be made unreadable");
        try {
            assertThrows(IOException.class, () -> BackupService.settle(siteDir, work("demo")));
        } finally {
            assertTrue(unreadable.toFile().setReadable(true));
        }
        assertTrue(Files.exists(unreadable), "the unmerged log is kept");

        BackupService.settle(siteDir, work("demo"));

        assertEquals("OLD\nNEW\n", read(siteDir.resolve("logs/audit-2026-10-30.log")), "joined once, in order");
        assertEquals("YESTERDAY\n", read(siteDir.resolve("logs/audit-2026-10-29.log")), "moved once");
    }

    /** Logs a previous restore could not carry over are merged by the next restore of the site, not deleted. */
    @Test
    void theNextRestoreFinishesLogsAPreviousOneLeftBehind() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("demo", archive);
        write(work("demo").resolve("old/site.yml"), "id: demo\n");
        write(work("demo").resolve("old/logs/audit-2026-10-29.log"), "LEFT-BEHIND\n");

        svc.restore("demo", new ByteArrayInputStream(archive.toByteArray()));

        assertEquals("LEFT-BEHIND\n", read(siteDir.resolve("logs/audit-2026-10-29.log")));
        assertFalse(Files.exists(work("demo")), "the work folder is cleared once settled");
    }

    /** A new site starts its own logs: a leftover folder under its id (no site.yml) is not adopted. */
    @Test
    void aSiteCreatedFromABackupDoesNotAdoptLeftoverLogs() throws IOException {
        new SiteStore(layout, new YamlStore()).save(new Site("src", "Source", List.of("src.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), "https://src.test", SiteStatus.ACTIVE));
        write(layout.siteDir("src").resolve("templates/home.html"), "SRC_CONTENT");
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("src", archive);
        Path stray = layout.siteDir("clone");
        write(stray.resolve("logs/audit-2026-10-30.log"), "STRAY");

        svc.createFromBackup("clone", "Cloned", List.of("clone.test"), new ByteArrayInputStream(archive.toByteArray()));

        assertTrue(Files.exists(stray.resolve("site.yml")), "the site is created");
        assertFalse(Files.exists(stray.resolve("logs")), "no leftover log becomes the new site's journal");
    }

    // ---- a restore must not make every page look edited today ------------------------------------

    /**
     * A restored file keeps its original modification time.
     *
     * <p>A page's {@code <lastmod>} in the sitemap derives from its mtime, so a restore that stamped
     * everything "now" told every search engine the whole site had just been rewritten — falsifying the one
     * signal a sitemap exists to give, through an operation that changed no content at all. It also made a
     * restored site look freshly edited in the console's own listings.
     *
     * <p>The mtime is set a year back rather than merely "different", so the assertion cannot pass by
     * accident on a fast machine where "now" and "then" land in the same second, and the tolerance covers the
     * two-second granularity a ZIP timestamp can have.
     */
    @Test
    void aRestoredFileKeepsItsOriginalModificationTime() throws IOException {
        Path master = siteDir.resolve("templates/_master.html");
        FileTime original = FileTime.from(Instant.parse("2025-03-04T10:15:30Z"));
        Files.setLastModifiedTime(master, original);

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("demo", archive);
        write(master, "MASTER_BROKEN");            // touches the file, stamping it "now"
        svc.restore("demo", new ByteArrayInputStream(archive.toByteArray()));

        long drift = Math.abs(Files.getLastModifiedTime(master).toMillis() - original.toMillis());
        assertEquals("MASTER_V1", read(master), "content restored");
        assertTrue(drift <= 2000L, "restored mtime drifted by " + drift + " ms — a restore is not an edit");
    }

    /**
     * The times must be right at the moment the site becomes visible, not fixed up afterwards.
     *
     * <p>Restoring and then correcting would leave a window in which the site is live and advertising itself
     * as freshly rewritten — short, but a crawler mid-fetch does not know that. Checked by asserting the
     * whole restored tree, so no file is left behind by a path that writes outside the staged copy.
     */
    @Test
    void everyRestoredFileKeepsItsTimeNotJustTheOneWeLookedAt() throws IOException {
        FileTime original = FileTime.from(Instant.parse("2025-03-04T10:15:30Z"));
        for (Path file : List.of(siteDir.resolve("site.yml"), siteDir.resolve("templates/_master.html"),
                siteDir.resolve("users.yml"))) {
            Files.setLastModifiedTime(file, original);
        }

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("demo", archive);
        Files.delete(siteDir.resolve("users.yml"));
        svc.restore("demo", new ByteArrayInputStream(archive.toByteArray()));

        try (var walk = Files.walk(siteDir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                long drift = Math.abs(Files.getLastModifiedTime(file).toMillis() - original.toMillis());
                assertTrue(drift <= 2000L, file + " came back stamped now (drift " + drift + " ms)");
            }
        }
    }

    /**
     * An archive whose entry times are absent or implausible still restores.
     *
     * <p>Every archive written before this change carries the time the <em>backup</em> ran rather than the
     * file's own, and one produced by another tool may carry the DOS epoch or nothing usable at all. A
     * cosmetic freshness signal is not worth an operator's data recovery, so the restore proceeds and the
     * file takes whatever time the archive offers.
     *
     * <p>Built by rewriting a REAL archive's entry times rather than hand-assembling one, so the manifest and
     * layout stay exactly what the restore path expects and the test isolates the timestamp variable alone.
     */
    @Test
    void anArchiveWithImplausibleEntryTimesStillRestores() throws IOException {
        ByteArrayOutputStream original = new ByteArrayOutputStream();
        svc.backup("demo", original);

        ByteArrayOutputStream retimed = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(original.toByteArray()));
             ZipOutputStream out = new ZipOutputStream(retimed)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(0L);                  // the DOS epoch — as far from plausible as a ZIP allows
                out.putNextEntry(copy);
                in.transferTo(out);
                out.closeEntry();
            }
        }

        write(siteDir.resolve("templates/_master.html"), "MASTER_BROKEN");
        svc.restore("demo", new ByteArrayInputStream(retimed.toByteArray()));

        assertEquals("MASTER_V1", read(siteDir.resolve("templates/_master.html")),
                "the data recovery is what matters; the timestamp is a bonus");
    }

    // --- an export failure fails the backup and names the participant; an import failure is contained --

    private static BackupParticipant failing(boolean onExport, RuntimeException failure) {
        return new BackupParticipant() {
            @Override
            public String key() {
                return "broken";
            }

            @Override
            public void export(BackupWriter out, SiteRef site) {
                if (onExport) {
                    throw failure;
                }
            }

            @Override
            public void importInto(BackupReader in, SiteRef site) {
                throw failure;
            }
        };
    }

    @Test
    void aParticipantExportFailureFailsTheWholeBackupAndNamesTheParticipant() {
        BackupService broken = new BackupService(layout, new SiteStore(layout, new YamlStore()),
                new BackupParticipantRegistry(List.of(fake, failing(true, new IllegalStateException("disk gone")))),
                event -> { });
        IOException failure = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> broken.backup("demo", new ByteArrayOutputStream()));
        assertTrue(failure.getMessage().contains("participant 'broken'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("disk gone"), failure.getMessage());
    }

    @Test
    void aParticipantImportFailureIsContainedAndTheOthersStillImport() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        svc.backup("demo", archive);
        mx.quest.runway.plugin.ContributionGuard guard = mx.quest.runway.plugin.ContributionGuard.standalone();
        BackupService mixed = new BackupService(layout, new SiteStore(layout, new YamlStore()),
                new BackupParticipantRegistry(List.of(failing(false, new IllegalStateException("bad import")), fake),
                        guard), event -> { });
        fake.imported = null;
        write(siteDir.resolve("templates/_master.html"), "MASTER_BROKEN");

        mixed.restore("demo", new ByteArrayInputStream(archive.toByteArray()));

        assertEquals("MASTER_V1", read(siteDir.resolve("templates/_master.html")), "the site files are restored");
        assertEquals("PLUGIN_STATE_V1", fake.imported, "the next participant still imports");
        assertEquals(1, guard.health("kernel").failures(), "the failure is recorded under the containment policy");
    }
}
