package mx.quest.contact;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/**
 * The submission store over {@code PluginSiteFiles} (the testkit's {@link TempDirSiteFiles}, which applies the
 * kernel's confinement rules): append + newest-first read, field sanitization, the bound, per-site isolation, the
 * on-disk location shared with 1.x, and that an unreadable file is never overwritten.
 */
class ContactStoreTest {

    @TempDir
    Path sites;
    private TempDirSiteFiles files;
    private ContactStore store;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(sites.resolve("acme"));
        Files.createDirectories(sites.resolve("other"));
        files = new TempDirSiteFiles(sites, "contact");
        store = new ContactStore(files);
    }

    @Test
    void appendsAndReadsNewestFirst() {
        store.add("acme", "Ann", "ann@example.com", "First message", 1000L);
        store.add("acme", "Bob", "bob@example.com", "Second message", 2000L);

        assertEquals(2, store.count("acme"));
        List<ContactSubmission> recent = store.recent("acme", 10);
        assertEquals("Bob", recent.get(0).name(), "newest first");
        assertEquals("Ann", recent.get(1).name());
        assertEquals("ann@example.com", recent.get(1).email());
        assertEquals(1, store.recent("acme", 1).size(), "the limit is honoured");
    }

    @Test
    void writesTheSameFileAs1xSoExistingSubmissionsCarryOver() throws IOException {
        Path file = sites.resolve("acme/plugins/contact/submissions.tsv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "500\tOld\told@example.com\tfrom contact 1.1.2\n", StandardCharsets.UTF_8);

        store.add("acme", "New", "new@example.com", "after the upgrade", 900L);

        assertEquals(2, store.count("acme"), "the 1.x submission is read from the same file");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(List.of("500\tOld\told@example.com\tfrom contact 1.1.2", "900\tNew\tnew@example.com\tafter the upgrade"),
                lines, "appended in place, one TSV line each");
    }

    @Test
    void tabsAndNewlinesAreSanitizedSoTheTsvFormatHolds() {
        store.add("acme", "Eve\tEvil", "e@example.com", "line one\nline two\tindented", 1000L);
        ContactSubmission s = store.recent("acme", 10).get(0);
        assertEquals("Eve Evil", s.name(), "tabs stripped from the field");
        assertEquals("line one line two indented", s.message(), "newlines/tabs collapsed to spaces");
    }

    @Test
    void aFieldIsCappedAndTheFileKeepsOnlyTheMostRecentSubmissions() {
        store.add("acme", "Long", "l@example.com", "x".repeat(ContactStore.MAX_FIELD + 50), 1L);
        assertEquals(ContactStore.MAX_FIELD, store.recent("acme", 1).get(0).message().length());
        for (int i = 2; i <= ContactStore.MAX_SUBMISSIONS + 1; i++) {
            store.add("acme", "N" + i, "n@example.com", "m", i);
        }
        assertEquals(ContactStore.MAX_SUBMISSIONS, store.count("acme"), "bounded to the most recent");
        assertEquals("N" + (ContactStore.MAX_SUBMISSIONS + 1), store.recent("acme", 1).get(0).name());
        assertTrue(store.recent("acme", ContactStore.MAX_SUBMISSIONS).stream().noneMatch(s -> s.name().equals("Long")),
                "the oldest one was dropped");
    }

    @Test
    void submissionsAreIsolatedPerSite() {
        store.add("acme", "Ann", "a@example.com", "for acme", 1000L);
        assertEquals(1, store.count("acme"));
        assertEquals(0, store.count("other"), "another site sees none of acme's submissions");
        assertTrue(store.recent("other", 10).isEmpty());
    }

    @Test
    void anUnreadableFileIsNeverOverwrittenAndTheConsoleReadsDegradeToEmpty() throws IOException {
        // A file over the read cap cannot be read: an append must fail rather than rewrite it from nothing.
        ContactStore small = new ContactStore(new TempDirSiteFiles(sites, "contact", 64));
        Path file = sites.resolve("acme/plugins/contact/submissions.tsv");
        Files.createDirectories(file.getParent());
        String big = "1\tA\ta@example.com\t" + "y".repeat(200) + "\n";
        Files.writeString(file, big, StandardCharsets.UTF_8);

        assertThrows(UncheckedIOException.class, () -> small.add("acme", "B", "b@example.com", "hi", 2L));
        assertEquals(big, Files.readString(file, StandardCharsets.UTF_8), "the stored submissions are untouched");
        assertEquals(0, small.count("acme"), "the console count degrades to 0 instead of failing the screen");
        assertTrue(small.recent("acme", 10).isEmpty());
    }

    @Test
    void aSiteWithoutAFolderIsAnErrorNotASilentCreate() {
        assertThrows(UncheckedIOException.class, () -> store.add("ghost", "A", "a@example.com", "hi", 1L),
                "the kernel refuses to create a deleted site's folder; the store reports it");
        assertTrue(Files.notExists(sites.resolve("ghost")));
    }
}
