package mx.quest.runway.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.Yaml;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The backup archive's {@code formatVersion}: written as 1, absent reads as 1 (every archive made before
 * the field existed), anything up to the supported version restores, and a newer one is refused before the live
 * site is touched — on restore, stored restore and create-site-from-backup alike.
 */
class BackupFormatVersionTest {

    /** A manifest exactly as an older release wrote it — before {@code formatVersion} existed. */
    private static final String PRE_FIELD_MANIFEST = """
            siteId: "demo"
            spiVersion: "0.3.4"
            createdAt: "2026-10-01T10:00:00Z"
            participants: []
            purpose: "RESTORE"
            """;

    @TempDir
    Path external;
    private StorageLayout layout;
    private SiteStore sites;
    private BackupService svc;
    private Path siteDir;

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        props.setBackups(external.resolve("backups"));
        layout = StorageLayout.from(props);
        sites = new SiteStore(layout, new YamlStore());
        svc = new BackupService(layout, sites, new BackupParticipantRegistry(List.of()), event -> { });
        siteDir = layout.siteDir("demo");
        write(siteDir.resolve("site.yml"), "id: demo\nname: Demo\n");
        write(siteDir.resolve("templates/home.html"), "LIVE");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String home() throws IOException {
        return Files.readString(siteDir.resolve("templates/home.html"), StandardCharsets.UTF_8);
    }

    /** A hand-built archive: the given manifest (first or last) plus a minimal site carrying {@code marker}. */
    private static byte[] archive(String manifest, boolean manifestLast, String marker) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        if (!manifestLast) {
            entries.put("manifest.yml", manifest);
        }
        entries.put("site/site.yml", "id: demo\nname: Demo\n");
        entries.put("site/templates/home.html", marker);
        if (manifestLast) {
            entries.put("manifest.yml", manifest);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static String manifestOf(byte[] archive) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals("manifest.yml")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("no manifest.yml");
    }

    private void assertUntouched() throws IOException {
        assertEquals("LIVE", home(), "the live site is untouched");
        assertFalse(Files.exists(layout.sites().resolve(".restore-work").resolve("demo")), "scratch cleaned up");
    }

    // --- writing -------------------------------------------------------------------------------------------------

    @Test
    void everyNewArchiveDeclaresTheSupportedFormat() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        svc.backup("demo", out);

        String manifest = manifestOf(out.toByteArray());
        assertTrue(manifest.contains("formatVersion: 1"), manifest);
        BackupManifest parsed = Yaml.read(new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)),
                BackupManifest.class);
        assertEquals(BackupManifest.SUPPORTED_FORMAT_VERSION, parsed.formatVersion());
        assertEquals(1, BackupManifest.SUPPORTED_FORMAT_VERSION);
    }

    @Test
    void aTransferArchiveDeclaresItToo() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        svc.backup("demo", out, BackupService.BackupPurpose.TRANSFER);

        assertTrue(manifestOf(out.toByteArray()).contains("formatVersion: 1"));
    }

    // --- reading: absent and supported ---------------------------------------------------------------------------

    @Test
    void anAbsentFieldReadsAsFormatOne() {
        assertEquals(1, Yaml.read(new ByteArrayInputStream(PRE_FIELD_MANIFEST.getBytes(StandardCharsets.UTF_8)),
                BackupManifest.class).formatVersion());
        assertEquals(1, new BackupManifest("s", "0.1.0", "t", List.of()).formatVersion());
        assertEquals(1, new BackupManifest("s", "0.1.0", "t", List.of(), "RESTORE").formatVersion());
        assertEquals(1, new BackupManifest("s", "0.1.0", "t", List.of(), "RESTORE", null).formatVersion());
    }

    /** The cross-version promise in its first form: an archive written before the field existed still restores. */
    @Test
    void anArchiveWrittenBeforeTheFieldExistedRestores() throws IOException {
        svc.restore("demo", new ByteArrayInputStream(archive(PRE_FIELD_MANIFEST, false, "FROM_BETA13")));

        assertEquals("FROM_BETA13", home());
    }

    @Test
    void anExplicitFormatOneRestores() throws IOException {
        svc.restore("demo", new ByteArrayInputStream(archive(PRE_FIELD_MANIFEST + "formatVersion: 1\n", false,
                "V1")));

        assertEquals("V1", home());
    }

    /** Additive manifest keys are not a format bump: an older reader ignores what it does not know. */
    @Test
    void unknownManifestKeysAreIgnored() throws IOException {
        svc.restore("demo", new ByteArrayInputStream(archive(
                PRE_FIELD_MANIFEST + "formatVersion: 1\nwrittenBy: \"1.3.0\"\nchecksums: {a: b}\n", false, "V1X")));

        assertEquals("V1X", home());
    }

    // --- reading: newer ------------------------------------------------------------------------------------------

    @Test
    void aNewerFormatIsRefusedAndTheSiteIsUntouched() throws IOException {
        byte[] future = archive(PRE_FIELD_MANIFEST + "formatVersion: 2\n", false, "FROM_THE_FUTURE");

        UnsupportedBackupFormatException refused = assertThrows(UnsupportedBackupFormatException.class,
                () -> svc.restore("demo", new ByteArrayInputStream(future)));

        assertEquals(2, refused.found());
        assertEquals(1, refused.supported());
        assertInstanceOf(IOException.class, refused, "callers handling 'not a valid backup' still handle it");
        assertUntouched();
    }

    /** Refused however the archive is ordered — the check does not depend on the manifest coming first. */
    @Test
    void aNewerFormatIsRefusedWhenTheManifestComesLast() throws IOException {
        byte[] future = archive(PRE_FIELD_MANIFEST + "formatVersion: 5\n", true, "FROM_THE_FUTURE");

        assertThrows(UnsupportedBackupFormatException.class,
                () -> svc.restore("demo", new ByteArrayInputStream(future)));
        assertUntouched();
    }

    @Test
    void aStoredRestoreOfANewerFormatIsRefused() throws IOException {
        String name = svc.create("demo");
        Files.write(external.resolve("backups").resolve("demo").resolve(name),
                archive(PRE_FIELD_MANIFEST + "formatVersion: 2\n", false, "FROM_THE_FUTURE"));

        assertThrows(UnsupportedBackupFormatException.class, () -> svc.restoreStored("demo", name));
        assertUntouched();
    }

    @Test
    void creatingASiteFromANewerFormatIsRefusedAndNothingIsCreated() throws IOException {
        byte[] future = archive(PRE_FIELD_MANIFEST + "formatVersion: 2\n", false, "FROM_THE_FUTURE");

        assertThrows(UnsupportedBackupFormatException.class,
                () -> svc.createFromBackup("clone", "Clone", List.of("clone.test"), new ByteArrayInputStream(future)));

        assertFalse(sites.exists("clone"), "no site was created");
        assertFalse(Files.exists(layout.siteDir("clone")), "no folder was left behind");
        assertFalse(Files.exists(layout.sites().resolve(".restore-work").resolve("clone")), "scratch cleaned up");
    }

    // --- reading: nonsense ---------------------------------------------------------------------------------------

    @Test
    void aFormatBelowOneIsNotAValidBackup() throws IOException {
        byte[] bogus = archive(PRE_FIELD_MANIFEST + "formatVersion: 0\n", false, "BOGUS");

        IOException refused = assertThrows(IOException.class,
                () -> svc.restore("demo", new ByteArrayInputStream(bogus)));

        assertFalse(refused instanceof UnsupportedBackupFormatException, "0 is not 'newer', it is invalid");
        assertTrue(refused.getMessage().contains("not a valid Runway site backup"), refused.getMessage());
        assertUntouched();
    }
}
