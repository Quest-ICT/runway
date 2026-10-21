package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.site.EditorialLevel;
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
 * Pending editorial work on disk: {@link PendingRevisionStore} and the {@link PageWriteService} rules
 * around it. The load-bearing promise is that a pending revision is invisible to the live site: it never touches
 * the live files, is never listed or routed by {@link PageStore}, evicts no cache, and travels with the page only
 * as far as the page goes (deleted with it; a rename is refused while it exists).
 */
class PendingRevisionTest {

    private static final String SITE = "acme";

    @TempDir
    Path external;
    private StorageLayout layout;
    private YamlStore yaml;
    private SiteStore sites;
    private PageStore pages;
    private TemplateCacheService cache;
    private PageWriteService writer;
    private PendingRevisionStore pending;
    private Path templates;

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        layout = StorageLayout.from(props);
        yaml = new YamlStore();
        sites = new SiteStore(layout, yaml);
        sites.save(new Site(SITE, "Acme", List.of("acme.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE));
        pages = new PageStore(layout, yaml);
        cache = mock(TemplateCacheService.class);
        writer = new PageWriteService(layout, yaml, pages, cache, new PageAuthoringValidator(sites, pages));
        pending = writer.pending();
        templates = layout.siteDir(SITE).resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("_master.html"), "<html><head></head><body></body></html>");
        writer.save(SITE, page("home", "/", false, "Home"), "<p>LIVE</p>", null);
        writer.save(SITE, page("other", "/other", false, "Other"), "<p>other</p>", null);
    }

    private static Page page(String slug, String path, boolean draft, String title) {
        return new Page(slug, path, Map.of(), List.of(), Map.of("en", title), Map.of(), "", "_master", draft, false,
                null, null);
    }

    @Test
    void aRevisionLeavesTheLiveFilesByteAndTimeIdenticalAndEvictsNothing() throws IOException {
        Path liveHtml = templates.resolve("home.html");
        Path liveMeta = templates.resolve("home.meta.yml");
        Files.setLastModifiedTime(liveHtml, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        byte[] htmlBefore = Files.readAllBytes(liveHtml);
        byte[] metaBefore = Files.readAllBytes(liveMeta);
        FileTime mtime = Files.getLastModifiedTime(liveHtml);
        clearInvocations(cache);

        writer.saveRevision(SITE, page("home", "/", false, "Home v2"), "<p>REVISED</p>", "<thlogic/>");

        assertArrayEquals(htmlBefore, Files.readAllBytes(liveHtml), "the live body is untouched");
        assertArrayEquals(metaBefore, Files.readAllBytes(liveMeta), "the live meta is untouched");
        assertEquals(mtime, Files.getLastModifiedTime(liveHtml), "the live mtime (sitemap lastmod) is untouched");
        verify(cache, never()).evictPage(SITE, "home");
        assertEquals("Home", pages.find(SITE, "home").orElseThrow().title().get("en"), "the live record reads as before");
        assertEquals("<p>LIVE</p>", writer.readBody(SITE, "home").orElseThrow());

        PendingRevisionStore.Revision rev = pending.revision(SITE, "home").orElseThrow();
        assertEquals("home", rev.page().slug());
        assertEquals("Home v2", rev.page().title().get("en"));
        assertEquals("<p>REVISED</p>", rev.html());
        assertEquals("<thlogic/>", rev.thxml());
        assertTrue(Files.isRegularFile(templates.resolve(".pending/home/revision.meta.yml")));
    }

    @Test
    void theKernelPageListAndRoutingNeverSeeAPendingRevision() {
        writer.saveRevision(SITE, page("home", "/moved", false, "Home v2"), "<p>REVISED</p>", null);
        pages.evictSite(SITE);
        assertEquals(List.of("home", "other"), pages.findAll(SITE).stream().map(Page::slug).toList(),
                "the .pending folder contributes no page");
        assertTrue(pages.findByPath(SITE, "/moved", Locale.ENGLISH).isEmpty(), "the revision's path does not route");
        assertEquals("home", pages.findByPath(SITE, "/", Locale.ENGLISH).orElseThrow().slug(), "the live path still does");
        assertTrue(new MasterTemplates(layout).list(SITE).stream().noneMatch(n -> n.contains("pending")));
        assertTrue(new FragmentTemplates(layout, cache).list(SITE).stream()
                .noneMatch(f -> f.name().contains("pending") || f.name().contains("revision")));
    }

    @Test
    void aRevisionWithoutLogicHasNullLogicAndRewritingDropsAStaleLogicFile() {
        writer.saveRevision(SITE, page("home", "/", false, "v2"), "<p>a</p>", "<thlogic/>");
        writer.saveRevision(SITE, page("home", "/", false, "v3"), "<p>b</p>", "  ");
        PendingRevisionStore.Revision rev = pending.revision(SITE, "home").orElseThrow();
        assertNull(rev.thxml());
        assertEquals("<p>b</p>", rev.html());
        assertFalse(Files.exists(templates.resolve(".pending/home/revision.th.xml")));
    }

    @Test
    void aRevisionIsValidatedLikeALiveSave() {
        // Another page owns /other: a revision moving home onto it would be an ambiguous route once approved.
        PageAuthoringException collision = assertThrows(PageAuthoringException.class,
                () -> writer.saveRevision(SITE, page("home", "/other", false, "x"), "<p/>", null));
        assertEquals("path-collision", collision.code());
        PageAuthoringException missing = assertThrows(PageAuthoringException.class,
                () -> writer.saveRevision(SITE, page("ghost", "/ghost", false, "x"), "<p/>", null));
        assertEquals("no-such-page", missing.code());
        Page noMaster = new Page("home", "/", Map.of(), List.of(), Map.of("en", "x"), Map.of(), "", "_nomaster",
                false, false, null, null);
        PageAuthoringException master = assertThrows(PageAuthoringException.class,
                () -> writer.saveRevision(SITE, noMaster, "<p/>", "<thlogic/>"));
        assertEquals("missing-master", master.code());
        assertFalse(pending.exists(SITE, "home"), "a refused revision writes nothing");
    }

    @Test
    void reviewStateRoundTripsAndClipsTheNote() {
        Instant at = Instant.parse("2026-10-17T15:00:00Z");
        pending.writeReview(SITE, "home", PageReview.submitted("ed", at));
        PageReview read = pending.review(SITE, "home").orElseThrow();
        assertTrue(read.isSubmitted());
        assertEquals("ed", read.submittedBy());
        assertEquals(at, read.submittedAt());
        assertNull(read.note());

        String essay = "x".repeat(PageReview.MAX_NOTE + 500);
        pending.writeReview(SITE, "home", read.returned("boss", at.plusSeconds(60), "  " + essay + "  "));
        PageReview returned = pending.review(SITE, "home").orElseThrow();
        assertTrue(returned.isReturned());
        assertEquals(PageReview.MAX_NOTE, returned.note().length(), "the note is clipped");
        assertEquals("ed", returned.submittedBy(), "who submitted it is kept");
        assertEquals("boss", returned.returnedBy());

        pending.clearReview(SITE, "home");
        assertTrue(pending.review(SITE, "home").isEmpty());
        assertFalse(pending.exists(SITE, "home"), "an empty page folder is removed with its last file");
    }

    @Test
    void anUnreadableReviewFileReadsAsNoReviewInsteadOfBreakingTheScreen() throws IOException {
        Path file = templates.resolve(".pending/home/review.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "state: [unclosed\n  - : :");
        assertTrue(pending.review(SITE, "home").isEmpty());
        // An unknown state value reads as RETURNED: out of the queue, nothing lost.
        Files.writeString(file, "state: banana\nsubmittedBy: ed\n");
        assertTrue(pending.review(SITE, "home").orElseThrow().isReturned());
    }

    @Test
    void pendingSlugsListsOnlySafePageFoldersAndUnsafeIdsAreInert() throws IOException {
        writer.saveRevision(SITE, page("home", "/", false, "v2"), "<p/>", null);
        pending.writeReview(SITE, "other", PageReview.submitted("ed", Instant.now()));
        Files.writeString(templates.resolve(".pending/stray.txt"), "not a folder");
        assertEquals(Set.of("home", "other"), pending.pendingSlugs(SITE));
        assertEquals(Set.of(), pending.pendingSlugs("../etc"));
        assertTrue(pending.review("acme", "../../site").isEmpty());
        assertFalse(pending.hasRevision("acme", ".."));
        assertThrows(PageAuthoringException.class,
                () -> pending.writeReview(SITE, "../x", PageReview.submitted("e", Instant.now())));
    }

    @Test
    void theFingerprintFollowsTheContentUnderReview() {
        String liveKey = pending.fingerprint(SITE, "home");
        assertEquals(liveKey, pending.fingerprint(SITE, "home"), "stable for unchanged files");
        writer.saveRevision(SITE, page("home", "/", false, "v2"), "<p>A</p>", null);
        String revKey = pending.fingerprint(SITE, "home");
        assertNotEquals(liveKey, revKey, "with a revision, the revision is what is fingerprinted");
        writer.saveRevision(SITE, page("home", "/", false, "v2"), "<p>B</p>", null);
        assertNotEquals(revKey, pending.fingerprint(SITE, "home"), "an edit of the revision changes it");
        pending.discard(SITE, "home");
        assertEquals(liveKey, pending.fingerprint(SITE, "home"), "back to the live files once discarded");
    }

    @Test
    void deletingAPageDeletesItsPendingWork() {
        writer.saveRevision(SITE, page("home", "/", false, "v2"), "<p/>", null);
        pending.writeReview(SITE, "home", PageReview.submitted("ed", Instant.now()));
        assertTrue(writer.delete(SITE, "home"));
        assertFalse(pending.exists(SITE, "home"));
        assertFalse(Files.exists(templates.resolve(".pending/home")));
    }

    @Test
    void renamingIsRefusedWhileWorkIsPendingAndAllowedAfter() {
        pending.writeReview(SITE, "other", PageReview.submitted("ed", Instant.now()));
        PageAuthoringException refused = assertThrows(PageAuthoringException.class,
                () -> writer.rename(SITE, "other", "renamed"));
        assertEquals("review-pending", refused.code());
        assertTrue(pages.find(SITE, "other").isPresent(), "nothing moved");
        pending.discard(SITE, "other");
        writer.rename(SITE, "other", "renamed");
        assertTrue(pages.find(SITE, "renamed").isPresent());
    }

    @Test
    void aSiteRecordWithoutTheKeyIsAdvisoryAndTheLevelRoundTrips() throws IOException {
        assertEquals(EditorialLevel.ADVISORY, sites.find(SITE).orElseThrow().editorial());
        sites.save(sites.find(SITE).orElseThrow().withEditorial(EditorialLevel.REVIEWED));
        assertTrue(Files.readString(layout.siteDir(SITE).resolve("site.yml")).contains("editorial: \"reviewed\"")
                || Files.readString(layout.siteDir(SITE).resolve("site.yml")).contains("editorial: reviewed"));
        sites.evict(SITE);
        assertEquals(EditorialLevel.REVIEWED, sites.find(SITE).orElseThrow().editorial());
        // Every other with… keeps the level (the class of bug where a rebuild drops a field).
        Site s = sites.find(SITE).orElseThrow();
        assertEquals(EditorialLevel.REVIEWED, s.withStatus(SiteStatus.SUSPENDED).editorial());
        assertEquals(EditorialLevel.REVIEWED, s.withMaintenance(true, "m").editorial());
        assertEquals(EditorialLevel.REVIEWED, s.withConfig("n", Locale.ENGLISH, List.of(Locale.ENGLISH)).editorial());
        assertEquals(EditorialLevel.REVIEWED, s.withHosts(List.of("x.test")).editorial());
        // A hand-edited typo reads as advisory rather than failing the site.
        Path file = layout.siteDir(SITE).resolve("site.yml");
        Files.writeString(file, Files.readString(file).replaceAll("editorial: .*", "editorial: reviwed"));
        sites.evict(SITE);
        assertEquals(EditorialLevel.ADVISORY, sites.find(SITE).orElseThrow().editorial());
    }

    @Test
    void editorialLevelParsing() {
        assertEquals(EditorialLevel.ADVISORY, EditorialLevel.fromKey(null));
        assertEquals(EditorialLevel.ADVISORY, EditorialLevel.fromKey(" "));
        assertEquals(EditorialLevel.ENFORCED, EditorialLevel.fromKey("Enforced"));
        assertEquals(EditorialLevel.REVIEWED, EditorialLevel.fromKey("reviewed"));
        assertTrue(EditorialLevel.parseStrict("bogus").isEmpty());
        assertEquals(EditorialLevel.ENFORCED, EditorialLevel.parseStrict("enforced").orElseThrow());
        assertFalse(EditorialLevel.ADVISORY.enforcesChecks());
        assertTrue(EditorialLevel.ENFORCED.enforcesChecks());
        assertFalse(EditorialLevel.ENFORCED.requiresReview());
        assertTrue(EditorialLevel.REVIEWED.enforcesChecks());
        assertTrue(EditorialLevel.REVIEWED.requiresReview());
    }
}
