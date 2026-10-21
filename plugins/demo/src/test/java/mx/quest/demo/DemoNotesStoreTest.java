package mx.quest.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The notes store over the kernel's per-site files (the testkit's {@code TempDirSiteFiles}, with its confinement). */
class DemoNotesStoreTest {

    @TempDir
    Path sites;
    private DemoNotesStore store;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(sites.resolve("acme"));
        store = new DemoNotesStore(new TempDirSiteFiles(sites, "runway-demo"));
    }

    private Path file(String site) {
        return sites.resolve(site).resolve("plugins").resolve("runway-demo").resolve(DemoNotesStore.FILE);
    }

    @Test
    void theNotesLiveInThePluginsOwnFolderOfTheSite() {
        store.append("acme", "hello", Note.ORIGIN_CONSOLE);
        assertTrue(Files.isRegularFile(file("acme")), "sites/acme/plugins/runway-demo/notes.yml");
    }

    @Test
    void readsShowTheWelcomeNoteButNeverWriteAFile() {
        assertEquals(1, store.count("acme"));
        Note welcome = store.list("acme").getFirst();
        assertEquals(DemoNotesStore.WELCOME_ID, welcome.id());
        assertEquals(Note.ORIGIN_SYSTEM, welcome.origin());
        assertEquals("", welcome.createdAt(), "a note that is only shown has no time");
        assertEquals(List.of(welcome), store.recent("acme", 10));
        assertFalse(Files.exists(file("acme")), "a read wrote nothing");
        assertFalse(Files.exists(file("acme").getParent()), "not even the plugin's folder");
    }

    @Test
    void theFirstAppendKeepsTheWelcomeNoteTheSiteWasShowing() {
        assertEquals(2, store.append("acme", "first", Note.ORIGIN_CONSOLE), "the welcome and the new note");
        assertTrue(Files.isRegularFile(file("acme")));
        List<Note> notes = store.list("acme");
        assertEquals(2, notes.size());
        Note welcome = notes.getFirst();
        assertEquals(DemoNotesStore.WELCOME_ID, welcome.id(), "the same note the site was showing");
        assertEquals(DemoNotesStore.welcome().text(), welcome.text());
        assertFalse(welcome.createdAt().isEmpty(), "stored with the time of the first write");
        assertEquals(welcome.createdAt(), notes.get(1).createdAt());
        assertEquals("first", notes.get(1).text());
        assertEquals(3, store.append("acme", "second", Note.ORIGIN_CONSOLE), "the welcome is written once");
    }

    @Test
    void appendKeepsOrderOriginAndStripsAndCapsTheText() {
        store.append("acme", "  first  ", Note.ORIGIN_CONSOLE);
        store.append("acme", "x".repeat(DemoNotesStore.MAX_TEXT + 50), Note.ORIGIN_VISITOR);
        List<Note> notes = store.list("acme");
        assertEquals(3, notes.size(), "the welcome, then the two notes");
        assertEquals("first", notes.get(1).text());
        assertEquals(Note.ORIGIN_CONSOLE, notes.get(1).origin());
        assertEquals(DemoNotesStore.MAX_TEXT, notes.get(2).text().length());
        assertEquals(Note.ORIGIN_VISITOR, notes.get(2).origin());
        assertEquals(List.of(notes.get(2), notes.get(1), notes.get(0)), store.recent("acme", 5),
                "recent is newest first");
        assertEquals(List.of(notes.get(2)), store.recent("acme", 1));
    }

    @Test
    void theFileIsBoundedToTheNewestNotes() {
        for (int i = 0; i < DemoNotesStore.MAX_NOTES + 3; i++) {
            store.append("acme", "note " + i, Note.ORIGIN_CONSOLE);
        }
        List<Note> notes = store.list("acme");
        assertEquals(DemoNotesStore.MAX_NOTES, notes.size());
        assertEquals("note 3", notes.getFirst().text(), "the oldest were dropped");
    }

    @Test
    void sitesAreIsolated() throws IOException {
        Files.createDirectories(sites.resolve("other"));
        store.append("acme", "only acme", Note.ORIGIN_CONSOLE);
        assertFalse(store.list("other").stream().anyMatch(n -> n.text().equals("only acme")));
    }

    @Test
    void anUnknownKeyInTheFileIsIgnoredAndAMissingOriginReadsAsSystem() throws IOException {
        Files.createDirectories(file("acme").getParent());
        Files.writeString(file("acme"), "notes:\n- id: n-1\n  text: old\n  createdAt: '2026-01-01T00:00:00Z'\n"
                + "  colour: blue\nfutureKey: 1\n", StandardCharsets.UTF_8);
        List<Note> notes = store.list("acme");
        assertEquals(1, notes.size());
        assertEquals("old", notes.getFirst().text());
        assertEquals(Note.ORIGIN_SYSTEM, notes.getFirst().origin());
    }

    @Test
    void anUnreadableFileDegradesReadsButNeverLetsAnAppendOverwriteIt() throws IOException {
        Files.createDirectories(file("acme").getParent());
        Files.writeString(file("acme"), "notes: [ this is: not: valid", StandardCharsets.UTF_8);
        assertEquals(List.of(), store.list("acme"), "a console read degrades to empty");
        assertEquals(0, store.count("acme"));
        assertThrows(UncheckedIOException.class, () -> store.append("acme", "x", Note.ORIGIN_CONSOLE),
                "an append refuses rather than rewriting the file from nothing");
        assertEquals("notes: [ this is: not: valid", Files.readString(file("acme")), "the file is untouched");
    }

    @Test
    void aSiteWhoseFolderIsGoneCannotBeWrittenAndReadsTheUnsavedWelcome() {
        assertThrows(UncheckedIOException.class, () -> store.append("ghost", "x", Note.ORIGIN_CONSOLE));
        assertEquals(List.of(DemoNotesStore.welcome()), store.list("ghost"), "the welcome is shown, unsaved");
        assertFalse(Files.exists(sites.resolve("ghost")), "nothing was created for a site that does not exist");
    }

    @Test
    void anUnsafeSiteIdIsRefusedBeforeAnyIo() {
        assertThrows(IllegalArgumentException.class, () -> store.append("../acme", "x", Note.ORIGIN_CONSOLE));
    }
}
