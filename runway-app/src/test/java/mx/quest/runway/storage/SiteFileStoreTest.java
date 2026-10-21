package mx.quest.runway.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shared per-site file brick: crash-safe writes, size caps, and hard site confinement. */
class SiteFileStoreTest {

    @TempDir
    Path external;
    private StorageLayout layout;
    private SiteFileStore store;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        layout = StorageLayout.from(props);
        store = new SiteFileStore(layout);
    }

    @Test
    void writeThenReadRoundTripsBytesAndText() throws IOException {
        store.write("demo", "static/doc/report.pdf", "PDF".getBytes(StandardCharsets.UTF_8), 1_000);
        assertArrayEquals("PDF".getBytes(StandardCharsets.UTF_8), store.read("demo", "static/doc/report.pdf").orElseThrow());

        store.writeText("demo", "static/css/site.css", "body{}", 1_000);
        assertEquals("body{}", store.readText("demo", "static/css/site.css").orElseThrow());
        assertTrue(store.exists("demo", "static/css/site.css"));
        assertEquals(6, store.size("demo", "static/css/site.css"));
    }

    @Test
    void aWriteLandsInsideTheAddressedSiteFolderOnly() throws IOException {
        store.write("demo", "favicon.svg", new byte[] {1, 2, 3}, 1_000);
        Path onDisk = layout.siteDir("demo").resolve("favicon.svg");
        assertTrue(Files.isRegularFile(onDisk), "written under sites/demo/");
        assertFalse(store.exists("other", "favicon.svg"), "another site never sees it");
    }

    @Test
    void anOversizeWriteIsRejectedBeforeItTouchesDisk() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> store.write("demo", "big.bin", new byte[2_000], 1_000));
        assertTrue(ex.getMessage().contains("over the"), ex.getMessage());
        assertFalse(store.exists("demo", "big.bin"), "nothing written");
    }

    @Test
    void aTraversalRelPathIsRejectedOnWriteAndQuietOnRead() {
        // Write edge fails loudly...
        assertThrows(IllegalArgumentException.class,
                () -> store.write("demo", "../evil.txt", new byte[] {9}, 1_000));
        // ...read edge stays quiet (clean empty, not a 500 on a public asset path).
        assertTrue(store.read("demo", "../../secret").isEmpty());
        assertFalse(store.exists("demo", "../../secret"));
        assertEquals(-1, store.size("demo", "../../secret"));
    }

    @Test
    void anUnsafeSiteIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> store.write("../other", "x.txt", new byte[] {1}, 1_000));
    }

    @Test
    void aSymlinkIsNeverReadListedOrReportedPresent() throws IOException {
        Path secret = external.resolve("secret.txt");
        Files.writeString(secret, "PRIVATE", StandardCharsets.UTF_8);
        Path linkInSite = layout.siteDir("demo").resolve("static/doc");
        Files.createDirectories(linkInSite);
        try {
            Files.createSymbolicLink(linkInSite.resolve("looks-legit.txt"), secret);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return; // filesystem without symlink support — nothing to prove
        }
        assertTrue(store.read("demo", "static/doc/looks-legit.txt").isEmpty(), "a symlink is never followed");
        assertFalse(store.exists("demo", "static/doc/looks-legit.txt"));
        assertEquals(List.of(), store.list("demo", "static/doc"), "symlinks omitted from listings");
    }

    /**
     * A folder link that stays inside the site passed the real-path check, so a caller's subtree rule
     * (Static files keeps to {@code static/}) was met by the path as written while the I/O reached {@code pages/}. No
     * link inside the site is followed, on any edge: public reads included, so page sources are not served either.
     */
    @Test
    void aFolderLinkThatStaysInsideTheSiteIsNotFollowedEither() throws IOException {
        Path demoDir = layout.siteDir("demo");
        Files.createDirectories(demoDir.resolve("pages"));
        Files.writeString(demoDir.resolve("pages/index.html"), "<p>draft</p>", StandardCharsets.UTF_8);
        Files.createDirectories(demoDir.resolve("static"));
        try {
            Files.createSymbolicLink(demoDir.resolve("static/pg"), Path.of("../pages"));
        } catch (UnsupportedOperationException | IOException unsupported) {
            return; // filesystem without symlink support — nothing to prove
        }
        assertTrue(store.read("demo", "static/pg/index.html").isEmpty(), "a page source is not served through it");
        assertTrue(store.resolveServable("demo", "static/pg/index.html").isEmpty());
        assertFalse(store.exists("demo", "static/pg/index.html"));
        assertFalse(store.delete("demo", "static/pg/index.html"));
        assertTrue(Files.exists(demoDir.resolve("pages/index.html")), "nothing deleted through it");
        assertThrows(IllegalArgumentException.class,
                () -> store.write("demo", "static/pg/planted.css", new byte[] {1}, 1_000));
        assertFalse(Files.exists(demoDir.resolve("pages/planted.css")));
        assertEquals(List.of(), store.listDirectory("demo", "static/pg"));
        assertTrue(store.read("demo", "pages/index.html").isPresent(), "the real path still works");
    }

    @Test
    void aSymlinkedIntermediateDirectoryCannotEscapeTheSiteFolder() throws IOException {
        // Plant sites/demo/assets -> sites/other (a symlinked DIRECTORY, not a leaf).
        Path otherSite = layout.siteDir("other");
        Files.createDirectories(otherSite);
        Files.writeString(otherSite.resolve("private.txt"), "OTHER-TENANT-SECRET", StandardCharsets.UTF_8);
        Path demoDir = layout.siteDir("demo");
        Files.createDirectories(demoDir);
        try {
            Files.createSymbolicLink(demoDir.resolve("assets"), otherSite);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return; // filesystem without symlink support — nothing to prove
        }
        // Read through the symlinked directory must NOT serve the other tenant's file.
        assertTrue(store.read("demo", "assets/private.txt").isEmpty(), "a symlinked dir never leaks another site");
        assertFalse(store.exists("demo", "assets/private.txt"));
        // Write through the symlinked directory must be refused, and must NOT land in the other site.
        assertThrows(IllegalArgumentException.class,
                () -> store.write("demo", "assets/planted.txt", new byte[] {6, 6, 6}, 1_000));
        assertFalse(Files.exists(otherSite.resolve("planted.txt")), "nothing escaped into the other site");
    }

    @Test
    void listReturnsSubtreeRelativeRegularFilesSorted() throws IOException {
        store.write("demo", "static/doc/a.pdf", new byte[] {1}, 1_000);
        store.write("demo", "static/doc/sub/b.txt", new byte[] {2}, 1_000);
        store.write("demo", "static/css/site.css", new byte[] {3}, 1_000); // outside the subtree
        assertEquals(List.of("a.pdf", "sub/b.txt"), store.list("demo", "static/doc"));
        assertEquals(List.of(), store.list("demo", "nope"), "absent subtree is empty, not an error");
    }

    @Test
    void deleteRemovesThePresentFileAndReportsWhetherItExisted() throws IOException {
        store.write("demo", "static/doc/x.txt", new byte[] {1}, 1_000);
        assertTrue(store.delete("demo", "static/doc/x.txt"));
        assertFalse(store.exists("demo", "static/doc/x.txt"));
        assertFalse(store.delete("demo", "static/doc/x.txt"), "second delete is a no-op false");
        assertFalse(store.delete("demo", "../escape"), "unsafe path deletes nothing");
    }

    @Test
    void anAbsentFileReadsEmpty() {
        assertTrue(store.read("demo", "static/doc/missing.pdf").isEmpty());
        assertTrue(store.readText("demo", "static/doc/missing.pdf").isEmpty());
        assertFalse(store.exists("demo", "static/doc/missing.pdf"));
    }
}
