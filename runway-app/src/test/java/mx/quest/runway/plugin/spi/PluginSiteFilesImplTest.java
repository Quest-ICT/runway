package mx.quest.runway.plugin.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import mx.quest.runway.spi.model.FileEntry;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link PluginSiteFilesImpl}: a plugin reaches its own subtree {@code sites/<id>/plugins/<pluginId>/} and nothing
 * else — not through a crafted path, not through a symbolic link planted inside the subtree, not through a link
 * standing in for the subtree itself — and the size, listing and delete contracts hold.
 */
class PluginSiteFilesImplTest {

    @TempDir
    Path external;
    private StorageLayout layout;
    private PluginSiteFilesImpl files;
    private Path site;
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        layout = StorageLayout.from(props);
        files = new PluginSiteFilesImpl("contact", layout, 64);
        site = layout.siteDir("acme");
        Files.createDirectories(site);
        Files.writeString(site.resolve("users.yml"), "SECRET-USERS");
        root = site.resolve("plugins").resolve("contact");
    }

    // --- the happy path -----------------------------------------------------------------------------------

    @Test
    void writesReadsAndListsInsideTheOwnSubtree() throws IOException {
        files.writeText("acme", "submissions.tsv", "a\tb\n");
        files.writeText("acme", "archive/2026/old.tsv", "x");
        assertEquals("a\tb\n", Files.readString(root.resolve("submissions.tsv")), "the file lands in plugins/contact/");
        assertEquals(Optional.of("a\tb\n"), files.readText("acme", "submissions.tsv"));
        List<FileEntry> top = files.list("acme", "");
        assertEquals(List.of("archive", "submissions.tsv"), top.stream().map(FileEntry::name).toList(), "by name");
        assertTrue(top.get(0).directory());
        assertEquals(0, top.get(0).size());
        assertEquals(4, top.get(1).size());
        assertEquals(List.of("old.tsv"), files.list("acme", "archive/2026").stream().map(FileEntry::name).toList());
        FileEntry stat = files.stat("acme", "archive/2026/old.tsv").orElseThrow();
        assertFalse(stat.directory());
        assertEquals(1, stat.size());
        assertTrue(files.stat("acme", "archive").orElseThrow().directory());
    }

    @Test
    void absentThingsReadAsEmptyNotAsErrors() throws IOException {
        assertEquals(Optional.empty(), files.readText("acme", "none.txt"));
        assertEquals(List.of(), files.list("acme", ""), "no subtree yet");
        assertEquals(List.of(), files.list("acme", "nope"));
        assertEquals(Optional.empty(), files.stat("acme", "nope"));
        assertFalse(files.delete("acme", "nope"));
        assertFalse(files.deleteTree("acme", "nope"));
        assertEquals(Optional.empty(), files.readText("ghost-site", "a.txt"), "an unknown site has no files");
    }

    @Test
    void aFileWhoseFolderCannotBeSearchedIsAnErrorNotAbsent() throws IOException {
        files.writeText("acme", "dir/token.yml", "token: x");
        Path dir = root.resolve("dir");
        Set<PosixFilePermission> before = Files.getPosixFilePermissions(dir);
        Files.setPosixFilePermissions(dir, Set.of());
        try {
            assumeFalse(Files.isReadable(dir.resolve("token.yml")), "running as a user that ignores permissions");
            // A caller that took "cannot tell" for "absent" would act as if the file were gone (the database plugin
            // read a missing token as a recreated site and retired its data).
            assertThrows(IOException.class, () -> files.readText("acme", "dir/token.yml"));
            assertThrows(IOException.class, () -> files.stat("acme", "dir/token.yml"));
            assertThrows(IOException.class, () -> files.list("acme", "dir/sub"));
            assertThrows(IOException.class, () -> files.list("acme", "dir"), "a folder that cannot be listed");
        } finally {
            Files.setPosixFilePermissions(dir, before);
        }
        assertEquals(Optional.of("token: x"), files.readText("acme", "dir/token.yml"));
    }

    @Test
    void aWriteReplacesTheFileAtomicallyAndLeavesNoTemporaryBehind() throws IOException {
        files.writeText("acme", "a.txt", "one");
        files.writeText("acme", "a.txt", "two");
        assertEquals(Optional.of("two"), files.readText("acme", "a.txt"));
        try (var children = Files.list(root)) {
            assertEquals(List.of("a.txt"), children.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aWriteForASiteThatDoesNotExistFailsInsteadOfCreatingItsFolder() {
        IOException failure = assertThrows(IOException.class, () -> files.writeText("deleted", "late.tsv", "x"));
        assertTrue(failure.getMessage().contains("does not exist"), failure.getMessage());
        assertFalse(Files.exists(layout.siteDir("deleted")), "no stray site folder for a later site to inherit");
    }

    @Test
    void deleteRemovesOneRegularFileAndDeleteTreeAWholeFolder() throws IOException {
        files.writeText("acme", "a.txt", "1");
        files.writeText("acme", "dir/b.txt", "2");
        files.writeText("acme", "dir/sub/c.txt", "3");
        assertFalse(files.delete("acme", "dir"), "delete is for regular files only");
        assertTrue(files.delete("acme", "a.txt"));
        assertFalse(Files.exists(root.resolve("a.txt")));
        assertTrue(files.deleteTree("acme", "dir"));
        assertFalse(Files.exists(root.resolve("dir")));
        assertTrue(Files.isDirectory(root), "the subtree root stays");
        files.writeText("acme", "single.txt", "s");
        assertTrue(files.deleteTree("acme", "single.txt"), "a file is deleted too");
    }

    @Test
    void theSubtreeRootItselfCannotBeDeletedThroughDeleteTree() {
        assertThrows(IllegalArgumentException.class, () -> files.deleteTree("acme", ""));
    }

    // --- limits -------------------------------------------------------------------------------------------

    @Test
    void aWriteOverTheCapIsRefusedAndNothingIsWritten() {
        String big = "x".repeat(65); // cap is 64 bytes in this fixture
        assertThrows(IllegalArgumentException.class, () -> files.writeText("acme", "big.txt", big));
        assertFalse(Files.exists(root.resolve("big.txt")));
    }

    @Test
    void theCapCountsUtf8BytesNotCharacters() {
        String accents = "é".repeat(33); // 33 characters, 66 bytes
        assertThrows(IllegalArgumentException.class, () -> files.writeText("acme", "acc.txt", accents));
    }

    @Test
    void aFileOverTheCapPlacedByHandIsNeverReadWhole() throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("huge.txt"), "y".repeat(1000));
        assertThrows(IOException.class, () -> files.readText("acme", "huge.txt"));
    }

    @Test
    void listingIsCappedAtTenThousandEntries() throws IOException {
        Path dir = root.resolve("many");
        Files.createDirectories(dir);
        for (int i = 0; i < PluginSiteFilesImpl.MAX_LIST_ENTRIES + 5; i++) {
            Files.createFile(dir.resolve(String.format("f%05d", i)));
        }
        List<FileEntry> listed = files.list("acme", "many");
        assertEquals(PluginSiteFilesImpl.MAX_LIST_ENTRIES, listed.size());
        assertEquals("f00000", listed.get(0).name(), "the first by name are kept");
    }

    @Test
    void aWriteOntoAFolderIsAnIoErrorNotASilentReplace() throws IOException {
        Files.createDirectories(root.resolve("folder/inner"));
        assertThrows(IOException.class, () -> files.writeText("acme", "folder", "x"));
        assertTrue(Files.isDirectory(root.resolve("folder/inner")));
    }

    // --- lexical confinement ------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"../users.yml", "../../other/users.yml", "/etc/passwd", "a/../../users.yml", "..",
            ".", "a/./b", "a//b", "a/", "a\\b", "..\\users.yml", "a\u0000b", "line\nbreak", ""})
    void unsafeRelativePathsAreRefusedBeforeAnyIo(String path) {
        assertThrows(IllegalArgumentException.class, () -> files.writeText("acme", path, "x"));
        assertThrows(IllegalArgumentException.class, () -> files.readText("acme", path));
        assertThrows(IllegalArgumentException.class, () -> files.delete("acme", path));
        assertThrows(IllegalArgumentException.class, () -> files.stat("acme", path));
        assertEquals("SECRET-USERS", read(site.resolve("users.yml")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "../acme", "a/b", "", ".hidden|x", "with space"})
    void unsafeSiteIdsAreRefused(String siteId) {
        assertThrows(IllegalArgumentException.class, () -> files.readText(siteId, "a.txt"));
        assertThrows(IllegalArgumentException.class, () -> files.writeText(siteId, "a.txt", "x"));
        assertThrows(IllegalArgumentException.class, () -> files.list(siteId, ""));
    }

    @Test
    void theSettingsFileBesideTheSubtreeIsUnreachable() throws IOException {
        Files.createDirectories(site.resolve("plugins"));
        Files.writeString(site.resolve("plugins/contact.yml"), "smtpPassword: ENC(x)");
        assertThrows(IllegalArgumentException.class, () -> files.readText("acme", "../contact.yml"));
        assertThrows(IllegalArgumentException.class, () -> files.writeText("acme", "../contact.yml", "pwned"));
        assertEquals("smtpPassword: ENC(x)", read(site.resolve("plugins/contact.yml")));
    }

    @Test
    void anotherPluginsSubtreeIsUnreachable() throws IOException {
        PluginSiteFilesImpl search = new PluginSiteFilesImpl("search", layout, 64);
        search.writeText("acme", "no-results.tsv", "terms");
        assertThrows(IllegalArgumentException.class, () -> files.readText("acme", "../search/no-results.tsv"));
        assertEquals(Optional.empty(), files.readText("acme", "no-results.tsv"), "each plugin sees only its own");
    }

    // --- symbolic links -----------------------------------------------------------------------------------

    @Test
    void aSymlinkedDirectoryInsideTheSubtreeCannotCarryAWriteOrReadOut() throws IOException {
        Files.createDirectories(root);
        Files.createSymbolicLink(root.resolve("escape"), site); // plugins/contact/escape -> the site folder
        assertThrows(IllegalArgumentException.class, () -> files.writeText("acme", "escape/users.yml", "pwned"));
        assertThrows(IllegalArgumentException.class, () -> files.readText("acme", "escape/users.yml"));
        assertThrows(IllegalArgumentException.class, () -> files.list("acme", "escape"));
        assertThrows(IllegalArgumentException.class, () -> files.delete("acme", "escape/users.yml"));
        assertEquals("SECRET-USERS", read(site.resolve("users.yml")));
    }

    @Test
    void theSubtreeItselfBeingASymlinkIsRefused() throws IOException {
        Files.createDirectories(site.resolve("plugins"));
        Files.createSymbolicLink(root, site); // plugins/contact -> the site folder: users.yml would be "inside"
        assertThrows(IllegalArgumentException.class, () -> files.readText("acme", "users.yml"));
        assertThrows(IllegalArgumentException.class, () -> files.writeText("acme", "users.yml", "pwned"));
        assertThrows(IllegalArgumentException.class, () -> files.list("acme", ""));
        assertEquals("SECRET-USERS", read(site.resolve("users.yml")));
    }

    @Test
    void aSymlinkedPluginsFolderIsRefused() throws IOException {
        Path elsewhere = external.resolve("elsewhere");
        Files.createDirectories(elsewhere.resolve("contact"));
        Files.createSymbolicLink(site.resolve("plugins"), elsewhere);
        assertThrows(IllegalArgumentException.class, () -> files.writeText("acme", "a.txt", "x"));
        assertFalse(Files.exists(elsewhere.resolve("contact/a.txt")));
    }

    @Test
    void anotherSitesFolderIsUnreachableThroughALink() throws IOException {
        Path other = layout.siteDir("other");
        Files.createDirectories(other);
        Files.writeString(other.resolve("users.yml"), "OTHER-USERS");
        Files.createDirectories(root);
        Files.createSymbolicLink(root.resolve("other"), other);
        assertThrows(IllegalArgumentException.class, () -> files.readText("acme", "other/users.yml"));
        assertThrows(IllegalArgumentException.class, () -> files.writeText("acme", "other/users.yml", "x"));
        assertEquals("OTHER-USERS", read(other.resolve("users.yml")));
    }

    @Test
    void aSymlinkAtTheLeafReadsAsAbsentAndIsNeverListedOrDeletedThrough() throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("real.txt"), "inside");
        Files.createSymbolicLink(root.resolve("alias.txt"), root.resolve("real.txt"));    // a link that stays inside
        Files.createSymbolicLink(root.resolve("users-link"), site.resolve("users.yml"));   // a link that leaves
        assertEquals(Optional.empty(), files.readText("acme", "alias.txt"), "a link at the leaf reads as absent");
        assertEquals(Optional.empty(), files.stat("acme", "alias.txt"));
        assertThrows(IllegalArgumentException.class, () -> files.readText("acme", "users-link"));
        assertEquals(List.of("real.txt"), files.list("acme", "").stream().map(FileEntry::name).toList(),
                "links are not listed");
        assertFalse(files.delete("acme", "alias.txt"), "delete removes regular files only");
        assertTrue(Files.isSymbolicLink(root.resolve("alias.txt")));
    }

    @Test
    void deleteTreeRemovesLinksAsLinksAndNeverFollowsThem() throws IOException {
        Path outside = external.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.txt"), "KEEP");
        files.writeText("acme", "dir/a.txt", "a");
        Files.createSymbolicLink(root.resolve("dir/link-dir"), outside);
        Files.createSymbolicLink(root.resolve("dir/link-file"), outside.resolve("keep.txt"));
        assertTrue(files.deleteTree("acme", "dir"));
        assertFalse(Files.exists(root.resolve("dir"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals("KEEP", read(outside.resolve("keep.txt")), "the link's target is untouched");
    }

    @Test
    void deleteTreeOnALinkDoesNothing() throws IOException {
        Path outside = external.resolve("outside2");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.txt"), "KEEP");
        Files.createDirectories(root);
        // The link points back inside the subtree, so the path passes confinement; it is still a link, so it is left.
        Files.createDirectories(root.resolve("target"));
        Files.createSymbolicLink(root.resolve("dirlink"), root.resolve("target"));
        assertFalse(files.deleteTree("acme", "dirlink"));
        assertTrue(Files.isDirectory(root.resolve("target")));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
