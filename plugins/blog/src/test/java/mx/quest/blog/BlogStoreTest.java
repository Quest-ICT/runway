package mx.quest.blog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link BlogStore} over the kernel's {@code PluginSiteFiles} (the testkit's {@link TempDirSiteFiles}, which applies
 * the kernel's confinement rules): the {@code entries/<id>/entry.yml} + {@code body-<lang>.md} layout under the
 * site's own folder, the metadata/body split, id/lang safety, the derived {@code updatedAt} — and that entries
 * <b>written by blog 1.3.1</b> (the fixture under {@code src/test/resources/fixtures/blog-1.3.1}, produced by the
 * 1.3.1 store itself) load unchanged, and are written back byte for byte.
 */
class BlogStoreTest {

    static final Path FIXTURE = Path.of("src/test/resources/fixtures/blog-1.3.1/entries");

    private static BlogStore store(Path sites) throws IOException {
        Files.createDirectories(sites.resolve("acme"));
        Files.createDirectories(sites.resolve("globex"));
        return new BlogStore(new TempDirSiteFiles(sites, "blog"));
    }

    private static BlogEntry entry(String id) {
        return new BlogEntry(id, Map.of("en", "Hello", "es", "Hola"), Map.of("en", "First post"),
                List.of("en", "es"), null, Map.of("es", "hola-mundo"), null, true, "2026-07-17", null, null);
    }

    /** Copies the 1.3.1 entries into a site's plugin subtree, exactly where 1.3.1 kept them. */
    static Path install131(Path sites, String siteId) throws IOException {
        Path entries = sites.resolve(siteId).resolve("plugins/blog/entries");
        try (Stream<Path> walk = Files.walk(FIXTURE)) {
            for (Path from : walk.toList()) {
                Path to = entries.resolve(FIXTURE.relativize(from).toString());
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(from, to);
                }
            }
        }
        return entries;
    }

    @Test
    void saveAndReadRoundTripWithBodies(@TempDir Path sites) throws IOException {
        BlogStore store = store(sites);
        store.save("acme", entry("hello-world"), Map.of("en", "# Hi\n\nBody.", "es", "# Hola"));

        BlogEntry read = store.read("acme", "hello-world").orElseThrow();
        assertEquals("hello-world", read.id(), "the folder name is the id (never stored in the YAML)");
        assertEquals("Hello", read.title().get("en"));
        assertEquals("hola-mundo", read.localizedSlugs().get("es"));
        assertEquals("hello-world", read.effectiveSlug(), "no explicit slug → the id");
        assertTrue(read.published());
        assertNotNull(read.updatedAt(), "updatedAt derived from file times");
        assertEquals("# Hi\n\nBody.", store.readBody("acme", "hello-world", "en"));
        assertEquals("", store.readBody("acme", "hello-world", "fr"), "no body for that language → empty");
        assertEquals(List.of("en", "es"), store.bodyLangs("acme", "hello-world"));

        // The files land inside the site's own folder, in the plugin's subtree — the backup-completeness guarantee.
        Path dir = sites.resolve("acme/plugins/blog/entries/hello-world");
        assertTrue(Files.isRegularFile(dir.resolve("entry.yml")));
        assertTrue(Files.isRegularFile(dir.resolve("body-en.md")));
        assertFalse(Files.readString(dir.resolve("entry.yml")).contains("hello-world"),
                "the id is never written into the YAML");
    }

    @Test
    void entriesWrittenBy131LoadUnchanged(@TempDir Path sites) throws IOException {
        BlogStore store = store(sites);
        install131(sites, "acme");

        assertEquals(List.of("launch", "no-author", "hello-world", "draft-post"),
                store.list("acme").stream().map(BlogEntry::id).toList(),
                "all four, newest publication date first, undated last");

        BlogEntry hello = store.read("acme", "hello-world").orElseThrow();
        assertEquals(Map.of("en", "Hello world", "es", "Hola mundo"), hello.title());
        assertEquals(Map.of("en", "The very first post.", "es", "La primera entrada."), hello.description());
        assertEquals(List.of("en", "es"), hello.locales());
        assertEquals(Map.of("es", "hola-mundo"), hello.localizedSlugs());
        assertTrue(hello.published());
        assertEquals("2026-07-17", hello.publishedOn());
        assertEquals("Jane Doe", hello.author());
        assertNotNull(hello.updatedAt());
        assertEquals("# Hello\n\nSome **bold** words and a [link](/about).\n",
                store.readBody("acme", "hello-world", "en"));
        assertEquals(List.of("en", "es"), store.bodyLangs("acme", "hello-world"));

        BlogEntry unattributed = store.read("acme", "no-author").orElseThrow();
        assertNull(unattributed.author(), "an entry 1.3.1 wrote without an author loads unattributed");
        assertEquals("2026-08-01", unattributed.publishedOn());
        assertTrue(unattributed.published());

        BlogEntry launch = store.read("acme", "launch").orElseThrow();
        assertEquals("v2", launch.slug());
        assertEquals("/announcing-v2", launch.customPath());
        assertEquals("Richard Roe", launch.author());

        BlogEntry draft = store.read("acme", "draft-post").orElseThrow();
        assertFalse(draft.published());
        assertNull(draft.publishedOn());
    }

    @Test
    void savingAn131EntryBackWritesTheSameBytes(@TempDir Path sites) throws IOException {
        BlogStore store = store(sites);
        Path entries = install131(sites, "acme");
        for (String id : List.of("hello-world", "no-author", "launch", "draft-post")) {
            String before = Files.readString(entries.resolve(id).resolve("entry.yml"), StandardCharsets.UTF_8);
            store.save("acme", store.read("acme", id).orElseThrow(), null);
            assertEquals(before, Files.readString(entries.resolve(id).resolve("entry.yml"), StandardCharsets.UTF_8),
                    id + ": entry.yml is written back exactly as the 1.3.1 store wrote it");
        }
    }

    @Test
    void aHandWrittenEntryWithMissingAndUnknownKeysStillLoads(@TempDir Path sites) throws IOException {
        BlogStore store = store(sites);
        Path dir = sites.resolve("acme/plugins/blog/entries/by-hand");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("entry.yml"), "title:\n  en: \"By hand\"\nfutureKey: 42\n");
        BlogEntry entry = store.read("acme", "by-hand").orElseThrow();
        assertEquals("By hand", entry.title().get("en"));
        assertFalse(entry.published(), "an omitted 'published:' defaults (lenient), never fails the entry");
    }

    @Test
    void anEmptyEntryFileIsAbsent(@TempDir Path sites) throws IOException {
        BlogStore store = store(sites);
        Path dir = sites.resolve("acme/plugins/blog/entries/empty");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("entry.yml"), "");
        assertTrue(store.read("acme", "empty").isEmpty());
        assertTrue(store.list("acme").isEmpty());
    }

    @Test
    void updatedAtIsTheNewestFileTimeOfTheEntry(@TempDir Path sites) throws IOException {
        BlogStore store = store(sites);
        store.save("acme", entry("post"), Map.of("en", "body"));
        Path dir = sites.resolve("acme/plugins/blog/entries/post");
        Files.setLastModifiedTime(dir.resolve("entry.yml"), FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(dir.resolve("body-en.md"), FileTime.from(Instant.parse("2026-03-01T00:00:00Z")));
        assertEquals(Instant.parse("2026-03-01T00:00:00Z"), store.read("acme", "post").orElseThrow().updatedAt(),
                "a body-only edit moves updatedAt (and so the ETag and <lastmod>)");
        Files.setLastModifiedTime(dir.resolve("entry.yml"), FileTime.from(Instant.parse("2026-05-01T00:00:00Z")));
        assertEquals(Instant.parse("2026-05-01T00:00:00Z"), store.read("acme", "post").orElseThrow().updatedAt());
    }

    @Test
    void aBlankBodyDeletesThatLanguagesFile(@TempDir Path sites) throws IOException {
        BlogStore store = store(sites);
        store.save("acme", entry("post"), Map.of("en", "text", "es", "texto"));
        Map<String, String> bodies = new java.util.HashMap<>();
        bodies.put("es", "  ");
        bodies.put("fr", null);
        store.save("acme", entry("post"), bodies);
        assertEquals("text", store.readBody("acme", "post", "en"), "untouched language kept");
        assertEquals("", store.readBody("acme", "post", "es"), "blanked language's file removed");
        assertEquals(List.of("en"), store.bodyLangs("acme", "post"));
    }

    @Test
    void deleteRemovesTheWholeEntry(@TempDir Path sites) throws IOException {
        BlogStore store = store(sites);
        store.save("acme", entry("gone"), Map.of("en", "x", "es", "y"));
        store.save("acme", entry("kept"), Map.of("en", "z"));
        store.delete("acme", "gone");
        assertTrue(store.read("acme", "gone").isEmpty());
        assertFalse(Files.exists(sites.resolve("acme/plugins/blog/entries/gone")));
        assertTrue(store.read("acme", "kept").isPresent(), "only that entry");
        store.delete("acme", "never-existed");
        store.delete("acme", "../escape");
        assertTrue(Files.isDirectory(sites.resolve("acme/plugins/blog/entries")), "unsafe ids are a no-op");
    }

    @Test
    void unsafeIdsNeverTouchThePathAndUnsafeLangsThrowBeforeWriting(@TempDir Path sites) throws IOException {
        BlogStore store = store(sites);
        assertTrue(store.read("acme", "../escape").isEmpty(), "a traversal id is an empty read, not a probe");
        assertTrue(store.read("acme", "has:colon").isEmpty(), "':' is out — blog:<id> must stay unambiguous");
        assertEquals("", store.readBody("acme", "x", "../en"), "a traversal lang reads empty");
        assertEquals("", store.readBody("acme", "../x", "en"), "a traversal id reads empty");
        assertEquals(List.of(), store.bodyLangs("acme", "../x"));
        assertThrows(IllegalArgumentException.class,
                () -> store.save("acme", entry("post"), Map.of("../en", "x")), "an unsafe lang never writes");
        assertTrue(store.read("acme", "post").isEmpty(), "…not even the metadata");
        assertThrows(IllegalArgumentException.class, () -> BlogStore.requireSafeId("Bad:Id"));
        assertThrows(IllegalArgumentException.class, () -> BlogStore.requireSafeId(null));
        assertThrows(IllegalArgumentException.class, () -> store.save("acme", entry("-bad"), null));
    }

    @Test
    void listSortsNewestFirstAndSkipsForeignFoldersAndFiles(@TempDir Path sites) throws IOException {
        BlogStore store = store(sites);
        store.save("acme", new BlogEntry("older", Map.of("en", "Old"), Map.of(), List.of("en"), null,
                Map.of(), null, true, "2026-01-01", null, null), null);
        store.save("acme", new BlogEntry("newer", Map.of("en", "New"), Map.of(), List.of("en"), null,
                Map.of(), null, true, "2026-07-01", null, null), null);
        store.save("acme", new BlogEntry("a-same-day", Map.of("en", "A"), Map.of(), List.of("en"), null,
                Map.of(), null, true, "2026-07-01", null, null), null);
        Files.createDirectories(sites.resolve("acme/plugins/blog/entries/.hidden-junk"));
        Files.writeString(sites.resolve("acme/plugins/blog/entries/stray-file"), "x");
        Files.createDirectories(sites.resolve("acme/plugins/blog/entries/no-metadata"));

        assertEquals(List.of("a-same-day", "newer", "older"),
                store.list("acme").stream().map(BlogEntry::id).toList(),
                "newest publication date first, then id; non-conforming folders, files and empty folders skipped");
    }

    @Test
    void aCorruptEntryIsSkippedNotFatal(@TempDir Path sites) throws IOException {
        BlogStore store = store(sites);
        store.save("acme", entry("good"), Map.of("en", "fine"));
        Path corrupt = sites.resolve("acme/plugins/blog/entries/broken");
        Files.createDirectories(corrupt);
        Files.writeString(corrupt.resolve("entry.yml"), "title: [unclosed");
        assertEquals(List.of("good"), store.list("acme").stream().map(BlogEntry::id).toList());
        assertTrue(store.read("acme", "broken").isEmpty());
    }

    @Test
    void aSymlinkedEntryFolderCannotLeadOutOfTheSubtree(@TempDir Path sites) throws IOException {
        BlogStore store = store(sites);
        store.save("acme", entry("good"), Map.of("en", "fine"));
        // Another site's data, reached through a link planted inside acme's blog folder.
        store.save("globex", entry("secret"), Map.of("en", "globex only"));
        Files.createSymbolicLink(sites.resolve("acme/plugins/blog/entries/secret"),
                sites.resolve("globex/plugins/blog/entries/secret"));
        assertEquals(List.of("good"), store.list("acme").stream().map(BlogEntry::id).toList(),
                "a link is never listed");
        assertThrows(IllegalArgumentException.class, () -> store.read("acme", "secret"),
                "reading through the link is refused by the kernel's real-path rule");
    }

    @Test
    void sitesAreIsolated(@TempDir Path sites) throws IOException {
        BlogStore store = store(sites);
        store.save("acme", entry("post"), Map.of("en", "acme text"));
        assertTrue(store.list("globex").isEmpty());
        assertEquals("", store.readBody("globex", "post", "en"));
    }

    @Test
    void aSiteWithNoBlogFolderListsNothing(@TempDir Path sites) throws IOException {
        assertTrue(store(sites).list("acme").isEmpty());
    }
}
