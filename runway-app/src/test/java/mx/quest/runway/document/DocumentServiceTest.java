package mx.quest.runway.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import mx.quest.runway.document.DocumentService.DocumentEntry;
import mx.quest.runway.storage.SiteFileStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The documents service: upload/list/delete, disable side-state, name safety, and site scoping. */
class DocumentServiceTest {

    @TempDir
    Path external;
    private StorageLayout layout;
    private DocumentService docs;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        layout = StorageLayout.from(props);
        docs = new DocumentService(new SiteFileStore(layout), layout, new YamlStore(), new mx.quest.runway.site.SiteStorageCeilings(
                new mx.quest.runway.platform.PlatformSettingsStore(layout, new YamlStore()), layout, 0));
    }

    @Test
    void addThenListShowsTheDocumentUnderStaticDoc() throws IOException {
        docs.add("acme", "report.pdf", "PDF-BYTES".getBytes());
        assertTrue(Path.of(layout.siteDir("acme").toString(), "static", "doc", "report.pdf").toFile().isFile());

        List<DocumentEntry> list = docs.list("acme");
        assertEquals(1, list.size());
        assertEquals("report.pdf", list.get(0).path());
        assertEquals(9, list.get(0).sizeBytes());
        assertFalse(list.get(0).disabled());
    }

    @Test
    void anUploadFilenameIsReducedToASafeLeaf() throws IOException {
        docs.add("acme", "../../etc/passwd", "x".getBytes()); // directory parts dropped -> "passwd"
        assertEquals(List.of("passwd"), docs.list("acme").stream().map(DocumentEntry::path).toList());
        assertFalse(Path.of(external.toString(), "etc", "passwd").toFile().exists(), "nothing escaped the site");
    }

    @Test
    void unusableNamesAreRejectedAndUrlUnsafeCharsAreSanitized() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> docs.add("acme", "   ", new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> docs.add("acme", "...", new byte[] {1}));
        // Spaces and URL-problematic chars are replaced with '_' so the download link never breaks.
        docs.add("acme", "my report#1.pdf", new byte[] {1});
        assertTrue(docs.list("acme").stream().anyMatch(d -> d.path().equals("my_report_1.pdf")));
    }

    @Test
    void setDisabledRejectsAnEmbeddedSlash() {
        // Documents are a flat namespace; a subdir-bearing path is rejected outright (not just leading '/' / '..').
        assertThrows(IllegalArgumentException.class, () -> docs.setDisabled("acme", "sub/report.pdf", true));
    }

    @Test
    void disableThenEnableTogglesTheFlagWithoutDeleting() throws IOException {
        docs.add("acme", "report.pdf", "x".getBytes());
        docs.setDisabled("acme", "report.pdf", true);
        assertTrue(docs.isDisabled("acme", "report.pdf"));
        assertTrue(docs.list("acme").get(0).disabled());
        assertTrue(Path.of(layout.siteDir("acme").toString(), "static", "doc", "report.pdf").toFile().isFile(),
                "disable does not delete");

        docs.setDisabled("acme", "report.pdf", false);
        assertFalse(docs.isDisabled("acme", "report.pdf"));
    }

    @Test
    void deleteRemovesTheFileAndClearsAnyDisabledFlag() throws IOException {
        docs.add("acme", "report.pdf", "x".getBytes());
        docs.setDisabled("acme", "report.pdf", true);
        assertTrue(docs.delete("acme", "report.pdf"));
        assertTrue(docs.list("acme").isEmpty());
        assertFalse(docs.isDisabled("acme", "report.pdf"), "the disabled flag was cleared on delete");
    }

    @Test
    void documentsAreScopedToTheirSite() throws IOException {
        docs.add("other", "secret.pdf", "x".getBytes());
        assertTrue(docs.list("acme").isEmpty(), "acme never sees another site's document");
    }

    @Test
    void aDisabledPathCannotEscapeViaTraversal() {
        assertThrows(IllegalArgumentException.class, () -> docs.setDisabled("acme", "../x", true));
        assertThrows(IllegalArgumentException.class, () -> docs.delete("acme", "../../x"));
    }

    @Test
    void aSiteChangeEventEvictsTheDisabledCache() throws IOException {
        docs.add("acme", "report.pdf", "x".getBytes());
        docs.setDisabled("acme", "report.pdf", true);
        assertTrue(docs.isDisabled("acme", "report.pdf")); // populates the cache

        // Simulate a backup restore replacing doc-state.yml on disk (re-enabling the doc).
        java.nio.file.Files.writeString(layout.siteDir("acme").resolve("doc-state.yml"), "paths: []\n");
        assertTrue(docs.isDisabled("acme", "report.pdf"), "the stale cached value is still returned");

        docs.onSiteChanged(new mx.quest.runway.site.SiteChangedEvent("acme",
                mx.quest.runway.site.SiteChangedEvent.Kind.RESTORED));
        assertFalse(docs.isDisabled("acme", "report.pdf"), "the event evicted the cache; the on-disk state is seen");
    }

    @Test
    void aNonWholesaleSiteEventDoesNotEvictTheDisabledCache() throws IOException {
        docs.add("acme", "report.pdf", "x".getBytes());
        docs.setDisabled("acme", "report.pdf", true);
        assertTrue(docs.isDisabled("acme", "report.pdf")); // populates the cache

        // A suspend/resume does not rewrite doc-state.yml, so it must not trigger a needless re-scan.
        java.nio.file.Files.writeString(layout.siteDir("acme").resolve("doc-state.yml"), "paths: []\n");
        docs.onSiteChanged(new mx.quest.runway.site.SiteChangedEvent("acme",
                mx.quest.runway.site.SiteChangedEvent.Kind.SUSPENDED));
        assertTrue(docs.isDisabled("acme", "report.pdf"),
                "a non-wholesale event keeps the cache; only delete/restore (and the self-evicting enable/disable) drop it");
    }

    @Test
    void displaySizeShowsBytesKilobytesAndMegabytes() {
        assertEquals("512 B", new DocumentEntry("a", 512, false).displaySize());
        assertEquals("1.5 KB", new DocumentEntry("a", 1536, false).displaySize());
        assertEquals("2.0 MB", new DocumentEntry("a", 2L * 1024 * 1024, false).displaySize());
    }
}
