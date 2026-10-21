package mx.quest.runway.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginSiteData;
import mx.quest.runway.plugin.RunningPlugins;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.BackupParticipant;
import mx.quest.runway.spi.model.BackupReader;
import mx.quest.runway.spi.model.BackupWriter;
import mx.quest.runway.spi.model.SiteRef;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.Yaml;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A <b>transfer copy</b> leaves out the personal data plugins keep in the site folder — every
 * path a loaded plugin declares under {@code siteData.personal}, and the whole folder of a plugin that is not loaded
 * (fail-safe: without its descriptor nobody can tell what is personal) — and its manifest names what it left out.
 * A <b>full backup</b> keeps everything, and a participant's export failure still fails it.
 *
 * <p>Asserted on the archive entries a recipient would unpack, as the other transfer tests are.
 */
class TransferPersonalDataTest {

    @TempDir
    Path external;
    private StorageLayout layout;
    private Path siteDir;

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        props.setBackups(external.resolve("backups"));
        layout = StorageLayout.from(props);
        siteDir = layout.siteDir("acme");
        write("site.yml", "id: acme\nname: Acme\n");
        write("templates/index.html", "<p>content</p>");
        write("plugins/contact.yml", "recipient: owner@acme.test\n");
        write("plugins/contact/submissions.tsv", "1\tAna\tana@x.test\tHello\n");
        write("plugins/contact/templates/form.txt", "not personal");
        write("plugins/search.yml", "recordNoResults: true\n");
        write("plugins/search/no-results.tsv", "secret search term\n");
        write("plugins/search/cache/a.bin", "derived");
        write("plugins/demo/notes/one.yml", "a note");
        write("plugins/demo/notes/deep/two.yml", "another");
        write("plugins/demo/keep.txt", "keep");
        write("plugins/retired/data.tsv", "a plugin that is no longer installed kept visitor data here\n");
        write("plugins/stray.txt", "a file directly in plugins/");
    }

    private void write(String rel, String content) throws IOException {
        Path file = siteDir.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static LoadedPlugin plugin(Path installDir, String id, String... personal) {
        PluginDescriptor descriptor = new PluginDescriptor(id, id, "1.0.0", null, true, false, List.of(), List.of(),
                List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), null, List.of(), null,
                new PluginSiteData(List.of(personal)));
        return new LoadedPlugin(descriptor, installDir.resolve(id));
    }

    private BackupService service(RunningPlugins running, List<BackupParticipant> participants) {
        return new BackupService(layout, new SiteStore(layout, new YamlStore()),
                new BackupParticipantRegistry(participants), event -> { }, new BackupProperties(), running);
    }

    private RunningPlugins loaded() {
        Path plugins = external.resolve("plugins");
        return () -> List.of(plugin(plugins, "contact", "submissions.tsv"),
                plugin(plugins, "search", "no-results.tsv"),
                plugin(plugins, "demo", "notes/"));
    }

    private static Map<String, String> entries(byte[] archive) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                out.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static byte[] archive(BackupService svc, BackupService.BackupPurpose purpose) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        svc.backup("acme", out, purpose);
        return out.toByteArray();
    }

    private static BackupManifest manifest(Map<String, String> entries) {
        return Yaml.read(new ByteArrayInputStream(entries.get("manifest.yml").getBytes(StandardCharsets.UTF_8)),
                BackupManifest.class);
    }

    @Test
    void aTransferCopyLeavesOutDeclaredPersonalPathsAndUnloadedPluginFolders() throws IOException {
        Map<String, String> entries = entries(archive(service(loaded(), List.of()),
                BackupService.BackupPurpose.TRANSFER));
        assertFalse(entries.containsKey("site/plugins/contact/submissions.tsv"), "a declared personal file");
        assertFalse(entries.containsKey("site/plugins/search/no-results.tsv"));
        assertFalse(entries.containsKey("site/plugins/demo/notes/one.yml"), "a declared folder, recursively");
        assertFalse(entries.containsKey("site/plugins/demo/notes/deep/two.yml"));
        assertFalse(entries.containsKey("site/plugins/retired/data.tsv"), "an unloaded plugin's whole folder");
        assertTrue(entries.containsKey("site/plugins/contact/templates/form.txt"), "undeclared files travel");
        assertTrue(entries.containsKey("site/plugins/search/cache/a.bin"));
        assertTrue(entries.containsKey("site/plugins/demo/keep.txt"));
        assertTrue(entries.containsKey("site/plugins/contact.yml"), "settings files keep the secret-blanking rule");
        assertTrue(entries.containsKey("site/plugins/stray.txt"));
        assertTrue(entries.containsKey("site/templates/index.html"));
        for (String text : entries.values()) {
            assertFalse(text.contains("ana@x.test") || text.contains("secret search term")
                    || text.contains("kept visitor data"), "no personal value survives anywhere");
        }
        assertEquals(List.of("plugins/contact/submissions.tsv", "plugins/demo/notes/", "plugins/retired/",
                "plugins/search/no-results.tsv"), manifest(entries).omitted(), "the manifest says what was left out");
    }

    @Test
    void withNoLoadedPluginsEveryPluginFolderIsLeftOut() throws IOException {
        Map<String, String> entries = entries(archive(service(null, List.of()), BackupService.BackupPurpose.TRANSFER));
        assertTrue(entries.keySet().stream().noneMatch(name -> name.matches("site/plugins/[^/]+/.*")), entries.keySet()
                .toString());
        assertTrue(entries.containsKey("site/plugins/contact.yml"));
        assertEquals(List.of("plugins/contact/", "plugins/demo/", "plugins/retired/", "plugins/search/"),
                manifest(entries).omitted());
    }

    @Test
    void aFullBackupKeepsEverythingAndOmitsNothing() throws IOException {
        Map<String, String> entries = entries(archive(service(loaded(), List.of()),
                BackupService.BackupPurpose.RESTORE));
        assertEquals("1\tAna\tana@x.test\tHello\n", entries.get("site/plugins/contact/submissions.tsv"));
        assertTrue(entries.containsKey("site/plugins/search/no-results.tsv"));
        assertTrue(entries.containsKey("site/plugins/demo/notes/deep/two.yml"));
        assertTrue(entries.containsKey("site/plugins/retired/data.tsv"));
        assertEquals(List.of(), manifest(entries).omitted());
    }

    @Test
    void theBackupScreenPreviewMatchesWhatTheCopyOmits() throws IOException {
        BackupService svc = service(loaded(), List.of());
        Map<String, String> entries = entries(archive(svc, BackupService.BackupPurpose.TRANSFER));
        assertEquals(manifest(entries).omitted(), svc.transferOmissions("acme"));
        assertThrows(IllegalArgumentException.class, () -> svc.transferOmissions("../acme"));
        assertEquals(List.of(), svc.transferOmissions("nosuchsite"));
    }

    @Test
    void aTransferCopyRestoresAndTheOmittedFilesAreSimplyAbsent() throws IOException {
        BackupService svc = service(loaded(), List.of());
        byte[] transfer = archive(svc, BackupService.BackupPurpose.TRANSFER);
        svc.restore("acme", new ByteArrayInputStream(transfer));
        assertFalse(Files.exists(siteDir.resolve("plugins/contact/submissions.tsv")));
        assertTrue(Files.exists(siteDir.resolve("plugins/contact/templates/form.txt")));
        assertTrue(Files.exists(siteDir.resolve("templates/index.html")));
    }

    @Test
    void anOlderManifestWithoutTheKeyReadsAsNothingOmitted() {
        BackupManifest old = Yaml.read(new ByteArrayInputStream(
                "siteId: acme\nspiVersion: 0.4.0\ncreatedAt: x\npurpose: TRANSFER\n".getBytes(StandardCharsets.UTF_8)),
                BackupManifest.class);
        assertEquals(List.of(), old.omitted());
        assertEquals(1, old.formatVersion(), "an additive key: the format version is unchanged");
    }

    @Test
    void aParticipantExportFailureStillFailsTheFullBackup() {
        BackupParticipant broken = new BackupParticipant() {
            @Override
            public String key() {
                return "broken";
            }

            @Override
            public void export(BackupWriter writer, SiteRef site) throws IOException {
                throw new IOException("disk on fire");
            }

            @Override
            public void importInto(BackupReader reader, SiteRef site) {
            }
        };
        BackupService svc = service(loaded(), List.of(broken));
        IOException failure = assertThrows(IOException.class,
                () -> archive(svc, BackupService.BackupPurpose.RESTORE));
        assertTrue(failure.getMessage().contains("broken"), failure.getMessage());
    }

    // --- the declaration itself ----------------------------------------------------------------------------

    @Test
    void personalPathsAreMatchedExactlyOrAsAFolderPrefix() {
        PluginSiteData data = new PluginSiteData(List.of("submissions.tsv", "archive/", " ", "logs/x.tsv"));
        assertEquals(List.of("submissions.tsv", "archive/", "logs/x.tsv"), data.personal(), "blank entries dropped");
        assertTrue(data.isPersonal("submissions.tsv"));
        assertFalse(data.isPersonal("submissions.tsv.bak"), "a file declaration is exact");
        assertFalse(data.isPersonal("old/submissions.tsv"));
        assertTrue(data.isPersonal("archive/2026/a.tsv"));
        assertFalse(data.isPersonal("archive"), "a folder declaration covers what is under it");
        assertFalse(data.isPersonal("archived/a.tsv"));
        assertEquals("plugins/contact/submissions.tsv", BackupService.personalDataOmission(
                "plugins/contact/submissions.tsv", Map.of("contact", data)));
        assertEquals(null, BackupService.personalDataOmission("plugins/contact.yml", Map.of()));
        assertEquals("plugins/x/", BackupService.personalDataOmission("plugins/x/a", Map.of()));
    }

    @Test
    void aMalformedDeclarationBlocksThePluginWithAReason() {
        for (String bad : List.of("/etc/passwd", "../users.yml", "a/../b", "a\\b", "/", "a//b", "./x")) {
            PluginDescriptor descriptor = PluginDescriptor.read(new ByteArrayInputStream(
                    ("id: contact\nversion: 2.0.0\nsiteData:\n  personal:\n    - \"" + bad.replace("\\", "\\\\")
                            + "\"\n").getBytes(StandardCharsets.UTF_8)));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, descriptor::validate, bad);
            assertTrue(failure.getMessage().contains("siteData.personal"), failure.getMessage());
        }
    }

    @Test
    void theDescriptorReadsTheDeclarationAndAnOlderDescriptorHasNone() {
        PluginDescriptor declared = PluginDescriptor.read(new ByteArrayInputStream(
                "id: contact\nversion: 2.0.0\nsiteData:\n  personal: [submissions.tsv, uploads/]\n"
                        .getBytes(StandardCharsets.UTF_8)));
        declared.validate();
        assertEquals(List.of("submissions.tsv", "uploads/"), declared.siteData().personal());
        PluginDescriptor older = PluginDescriptor.read(new ByteArrayInputStream(
                "id: contact\nversion: 1.1.2\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of(), older.siteData().personal());
    }
}
