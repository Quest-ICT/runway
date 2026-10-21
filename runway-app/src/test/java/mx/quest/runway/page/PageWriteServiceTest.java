package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.TemplateCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Page persistence: {@link PageWriteService} (create/save/delete/rename, meta-last ordering, dual-cache
 * eviction, traversal safety) and {@link PageAuthoringValidator} (fail-closed slug/path/route checks). A
 * plain unit test: real storage + real {@link PageStore}/{@link SiteStore}/validator over a temp dir, with
 * only {@link TemplateCacheService} mocked so parsed-template eviction can be asserted.
 */
class PageWriteServiceTest {

    private static final String SITE = "acme";

    @TempDir
    Path external;
    private StorageLayout layout;
    private PageStore pages;
    private TemplateCacheService cache;
    private PageWriteService writer;
    private Path templates;

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        sites.save(new Site(SITE, "Acme", List.of("acme.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH, Locale.forLanguageTag("es")), null, SiteStatus.ACTIVE));
        pages = new PageStore(layout, yaml);
        cache = mock(TemplateCacheService.class);
        writer = new PageWriteService(layout, yaml, pages, cache, new PageAuthoringValidator(sites, pages));
        templates = layout.siteDir(SITE).resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("_master.html"), "<html><head></head><body></body></html>");
    }

    private Page page(String slug, String path, boolean draft) {
        return new Page(slug, path, Map.of(), List.of(), Map.of("en", "T"), Map.of(), "", "_master", draft, false, null, null);
    }

    @Test
    void concurrentSavesOfTheSamePageNeverInterleaveBodyAndMeta() throws Exception {
        // Each writer stamps its own id into BOTH the body and the meta title; the per-page lock must ensure the
        // surviving pair is from ONE writer, never body-from-A + meta-from-B.
        int writers = 32;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < writers; i++) {
            final int k = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    Page p = new Page("home", "/", Map.of(), List.of(), Map.of("en", "T-" + k),
                            Map.of(), "", "_master", false, false, null, null);
                    writer.save(SITE, p, "BODY-" + k, "<thlogic></thlogic>");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get();
        }
        pool.shutdown();

        String body = Files.readString(templates.resolve("home.html"));
        String title = pages.find(SITE, "home").orElseThrow().title().get("en");
        assertEquals(body.substring("BODY-".length()), title.substring("T-".length()),
                "the surviving .html body and .meta.yml title are from one writer — the lock forbids a mix");
    }

    @Test
    void saveWritesAllThreeFilesTheCleanMetaAndEvicts() throws IOException {
        writer.save(SITE, page("home", "/", false), "<html>body</html>", "<thlogic></thlogic>");

        assertTrue(Files.isRegularFile(templates.resolve("home.html")));
        assertTrue(Files.isRegularFile(templates.resolve("home.th.xml")));
        assertTrue(Files.isRegularFile(templates.resolve("home.meta.yml")));
        // Routable + slug re-applied from the file name; the YAML omits the slug (withoutSlug + NON_NULL).
        Page loaded = pages.find(SITE, "home").orElseThrow();
        assertEquals("home", loaded.slug());
        assertFalse(loaded.draft());
        assertFalse(Files.readString(templates.resolve("home.meta.yml")).contains("slug:"),
                "the slug is the file name, never serialized into the meta body");
        verify(cache).evictPage(SITE, "home"); // parsed-template cache cleared
    }

    @Test
    void savingWithoutLogicRemovesAnExistingThxml() {
        writer.save(SITE, page("p", "/p", false), "<html/>", "<thlogic></thlogic>");
        assertTrue(Files.isRegularFile(templates.resolve("p.th.xml")));
        writer.save(SITE, page("p", "/p", false), "<html/>", null);
        assertFalse(Files.isRegularFile(templates.resolve("p.th.xml")), "a now-absent .th.xml is removed");
    }

    @Test
    void overwriteEvictsTheRecordCacheSoFindReflectsTheNewMeta() {
        writer.save(SITE, page("x", "/x", false), "<html/>", null);
        assertTrue(pages.find(SITE, "x").orElseThrow().published(), "cached as published");
        writer.save(SITE, page("x", "/x", true), "<html/>", null);
        assertTrue(pages.find(SITE, "x").orElseThrow().draft(), "record cache evicted → the draft flag is seen");
    }

    @Test
    void createRefusesToOverwriteAnExistingPage() {
        writer.create(SITE, page("dup", "/dup", false), "<html/>", null);
        PageAuthoringException ex = assertThrows(PageAuthoringException.class,
                () -> writer.create(SITE, page("dup", "/dup", false), "<html/>", null));
        assertEquals("already-exists", ex.code());
    }

    @Test
    void deleteRemovesAllThreeFilesAndEvicts() {
        writer.save(SITE, page("gone", "/gone", false), "<html/>", "<thlogic></thlogic>");
        org.mockito.Mockito.clearInvocations(cache); // isolate the delete's eviction from the save's
        assertTrue(writer.delete(SITE, "gone"));
        assertFalse(Files.exists(templates.resolve("gone.html")));
        assertFalse(Files.exists(templates.resolve("gone.th.xml")));
        assertFalse(Files.exists(templates.resolve("gone.meta.yml")));
        assertTrue(pages.find(SITE, "gone").isEmpty());
        verify(cache).evictPage(SITE, "gone");
    }

    @Test
    void anUnsafeSlugIsRejectedAndNothingIsWritten() {
        PageAuthoringException ex = assertThrows(PageAuthoringException.class,
                () -> writer.save(SITE, page("../evil", "/x", false), "<html/>", null));
        assertEquals("unsafe-slug", ex.code());
        assertTrue(isEmptyOfPages(), "a rejected write leaves no page files");
    }

    @Test
    void aReservedScratchPrefixSlugIsRejected() {
        PageAuthoringException ex = assertThrows(PageAuthoringException.class,
                () -> writer.save(SITE, page("rw-preview-1", "/x", false), "<html/>", null));
        assertEquals("reserved-slug", ex.code());
    }

    @Test
    void anUnsafeDefaultPathIsRejected() {
        PageAuthoringException ex = assertThrows(PageAuthoringException.class,
                () -> writer.save(SITE, page("ok", "/__admin", false), "<html/>", null));
        assertEquals("unsafe-path", ex.code());
    }

    @Test
    void aSiblingRouteCollisionIsRejected() {
        writer.save(SITE, page("home", "/", false), "<html/>", null);
        PageAuthoringException ex = assertThrows(PageAuthoringException.class,
                () -> writer.save(SITE, page("landing", "/", false), "<html/>", null));
        assertEquals("path-collision", ex.code());
    }

    @Test
    void samePathWithDisjointLocalesIsStillRejected() {
        // The router matches pathFor(lookupLocale) across all published pages with NO hasLocale filter, so a
        // page at "/" serving only [en] and one at "/" serving only [es] both resolve to "/" → 500. Reject it.
        writer.save(SITE, new Page("en-home", "/", Map.of(), List.of(Locale.ENGLISH),
                Map.of("en", "T"), Map.of(), "", "_master", false, false, null, null), "<html/>", null);
        PageAuthoringException ex = assertThrows(PageAuthoringException.class, () ->
                writer.save(SITE, new Page("es-home", "/", Map.of(), List.of(Locale.forLanguageTag("es")),
                        Map.of("es", "T"), Map.of(), "", "_master", false, false, null, null), "<html/>", null));
        assertEquals("path-collision", ex.code());
    }

    @Test
    void aDraftMayShareARouteWithoutCollision() {
        writer.save(SITE, page("home", "/", false), "<html/>", null);
        // A draft does not route, so it is not checked against the published set.
        writer.save(SITE, page("draft-home", "/", true), "<html/>", null);
        assertTrue(pages.find(SITE, "draft-home").orElseThrow().draft());
    }

    @Test
    void aMissingMasterIsRejectedForAPageThatCarriesLogic() throws IOException {
        Files.delete(templates.resolve("_master.html"));
        PageAuthoringException ex = assertThrows(PageAuthoringException.class,
                () -> writer.save(SITE, page("p", "/p", false), "<html/>", "<thlogic></thlogic>"));
        assertEquals("missing-master", ex.code());
    }

    @Test
    void aStandaloneBarePageNeedsNoMaster() throws IOException {
        Files.delete(templates.resolve("_master.html"));
        writer.save(SITE, page("solo", "/solo", false), "<html>standalone</html>", null);
        assertTrue(Files.isRegularFile(templates.resolve("solo.html")));
        assertFalse(Files.exists(templates.resolve("solo.th.xml")));
        assertTrue(pages.find(SITE, "solo").isPresent());
    }

    @Test
    void renameMovesTheFilesAndRepointsTheDerivedPath() {
        writer.create(SITE, page("old", null, false), "<html>keep</html>", "<thlogic></thlogic>");
        assertEquals("/old", pages.find(SITE, "old").orElseThrow().path());
        writer.rename(SITE, "old", "fresh");
        assertTrue(pages.find(SITE, "old").isEmpty());
        Page renamed = pages.find(SITE, "fresh").orElseThrow();
        assertEquals("/fresh", renamed.path(), "the derived path follows the new slug");
        assertEquals("<html>keep</html>", writer.readBody(SITE, "fresh").orElseThrow());
    }

    @Test
    void renameKeepsACustomPath() {
        writer.create(SITE, page("old2", "/custom", false), "<html/>", null);
        writer.rename(SITE, "old2", "new2");
        assertEquals("/custom", pages.find(SITE, "new2").orElseThrow().path());
    }

    @Test
    void renameRefusesAnExistingTarget() {
        writer.create(SITE, page("a", "/a", false), "<html/>", null);
        writer.create(SITE, page("b", "/b", false), "<html/>", null);
        PageAuthoringException ex = assertThrows(PageAuthoringException.class, () -> writer.rename(SITE, "a", "b"));
        assertEquals("already-exists", ex.code());
    }

    @Test
    void anUnsafeMasterNameIsRejected() {
        PageAuthoringException ex = assertThrows(PageAuthoringException.class, () -> writer.save(SITE,
                new Page("ok", "/ok", Map.of(), List.of(), Map.of("en", "T"), Map.of(), "", "../evil", false, false, null, null),
                "<html/>", null));
        assertEquals("unsafe-master", ex.code());
    }

    @Test
    void scratchPagesAreExcludedFromTheListing() {
        writer.writeScratch(SITE, page("rw-preview-abc", "/x", true), "<html/>", null);
        assertTrue(Files.exists(templates.resolve("rw-preview-abc.meta.yml")), "the scratch file exists on disk");
        assertTrue(pages.findAll(SITE).stream().noneMatch(p -> p.slug().startsWith("rw-preview-")),
                "a preview scratch page never shows in the listing");
    }

    @Test
    void readBodyAndReadLogicReturnTheSourceOrEmpty() {
        writer.save(SITE, page("src", "/src", false), "<html>B</html>", "<thlogic>L</thlogic>");
        assertEquals("<html>B</html>", writer.readBody(SITE, "src").orElseThrow());
        assertEquals("<thlogic>L</thlogic>", writer.readLogic(SITE, "src").orElseThrow());
        assertTrue(writer.readLogic(SITE, "nope").isEmpty());
    }

    private boolean isEmptyOfPages() {
        return pages.findAll(SITE).isEmpty();
    }

    /**
     * Provenance is EDITORIAL: renaming a page changes its address, not who wrote it or when it first went out.
     *
     * <p>This is the shape that silently rots. Both mutation paths rebuild the {@code Page} field by field, and
     * the compatibility constructor they used defaults author/publishedAt to {@code null} — so adding the fields
     * without touching these call sites would have compiled cleanly and quietly un-attributed every page the
     * moment it was renamed or unpublished, with nothing to notice it.
     */
    @Test
    void renamePreservesAuthorAndPublicationDate() {
        Instant published = Instant.parse("2026-02-03T00:00:00Z");
        writer.save(SITE, new Page("old", "/old", Map.of(), List.of(), Map.of("en", "T"), Map.of(), "",
                "_master", false, false, null, null, Map.of(), "Ada Lovelace", published), "<p>x</p>", null);

        writer.rename(SITE, "old", "new");

        Page renamed = pages.find(SITE, "new").orElseThrow();
        assertEquals("Ada Lovelace", renamed.author(), "a rename must not un-attribute the page");
        assertEquals(published, renamed.publishedAt(), "a rename must not reset the publication date");
    }

    /** Toggling published/draft is a visibility change; it must not touch authorship or the publication date. */
    @Test
    void publishingAndUnpublishingPreserveAuthorAndPublicationDate() {
        Instant published = Instant.parse("2026-02-03T00:00:00Z");
        writer.save(SITE, new Page("post", "/post", Map.of(), List.of(), Map.of("en", "T"), Map.of(), "",
                "_master", false, false, null, null, Map.of(), "Ada Lovelace", published), "<p>x</p>", null);

        writer.setDraft(SITE, "post", true);
        Page hidden = pages.find(SITE, "post").orElseThrow();
        assertEquals("Ada Lovelace", hidden.author());
        assertEquals(published, hidden.publishedAt());

        writer.setDraft(SITE, "post", false);
        Page shown = pages.find(SITE, "post").orElseThrow();
        assertEquals("Ada Lovelace", shown.author());
        assertEquals(published, shown.publishedAt());
    }

    /**
     * {@code nosnippet} survives a rename and a visibility toggle.
     *
     * <p>Third time this exact shape has been guarded on this record — provenance was dropped by these same two
     * rebuilds, and the blog's publish toggle would have dropped its author. A record rebuilt field by field
     * silently loses whatever the author of that line forgot, and the compiler cannot help once a
     * compatibility constructor exists to absorb the shorter call.
     */
    @Test
    void renameAndVisibilityTogglesPreserveNosnippet() {
        writer.save(SITE, new Page("quiet", "/quiet", Map.of(), List.of(), Map.of("en", "T"), Map.of(), "",
                "_master", false, false, null, null, Map.of(), null, null, true), "<p>x</p>", null);

        writer.rename(SITE, "quiet", "hushed");
        assertTrue(pages.find(SITE, "hushed").orElseThrow().nosnippet(),
                "a rename must not silently re-enable snippets");

        writer.setDraft(SITE, "hushed", true);
        assertTrue(pages.find(SITE, "hushed").orElseThrow().nosnippet(),
                "unpublishing must not silently re-enable snippets");
    }

    /**
     * The same guard for {@code gone} — and the reason the two rebuilds now go through the record itself.
     *
     * <p>{@code setDraft} is the operation that <em>activates</em> a tombstone, so of all the transformations
     * this is the one that must not drop the flag: the operator ticks "removed permanently", unpublishes, and
     * the rebuild that runs on that very click is the one that would have discarded it.
     */
    @Test
    void renameAndUnpublishPreserveTheRemovedMarker() {
        writer.save(SITE, new Page("retired", "/retired", Map.of(), List.of(), Map.of("en", "T"), Map.of(), "",
                "_master", false, false, null, null, Map.of(), null, null, false, true), "<p>x</p>", null);

        writer.setDraft(SITE, "retired", true);
        assertTrue(pages.find(SITE, "retired").orElseThrow().gone(),
                "unpublishing is the click that arms the tombstone — it cannot be the click that disarms it");

        writer.rename(SITE, "retired", "retired-2024");
        assertTrue(pages.find(SITE, "retired-2024").orElseThrow().gone());
    }
}
