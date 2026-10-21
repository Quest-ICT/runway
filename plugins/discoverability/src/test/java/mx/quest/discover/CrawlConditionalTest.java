package mx.quest.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The membership-aware crawl validator: {@code Last-Modified} must advance on ANY document change — including
 * removals, which a naive {@code max(updatedAt)} can never see (deleting an item only removes an mtime from
 * the pool) — while staying stable for unchanged documents so revalidating crawlers actually get their 304s.
 * Time is injected via the class's clock hook; {@code reset()} simulates a restart.
 */
class CrawlConditionalTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.forLanguageTag("es");
    private static final SiteView SITE = new SiteView("acme", "Acme", EN, List.of(EN), null);
    private static final Instant T0 = Instant.parse("2026-07-17T10:00:00Z");

    private Instant now;

    @BeforeEach
    void freezeClock() {
        CrawlConditional.reset();
        now = T0;
        CrawlConditional.clock = () -> now;
    }

    @AfterEach
    void restoreClock() {
        CrawlConditional.reset();
    }

    private static CatalogEntry item(String id, Instant updatedAt) {
        return Entries.of("blog", new ContentRef(id, "/blog/" + id, Map.of(), List.of(EN),
                Map.of("en", id), Map.of(), "", "__site__/acme/e", true, updatedAt, false, null, null, Map.of()), SITE);
    }

    private static RouteRequest revalidating(Instant lastModified) {
        Map<String, String> headers = Map.of("If-Modified-Since",
                CrawlConditional.cacheHeaders(lastModified, null).get("Last-Modified"));
        return new RouteRequest(SITE, EN, "GET", "/sitemap.xml", Map.of(), headers, new byte[0]);
    }

    @Test
    void unchangedDocumentKeepsItsValidatorSoRevalidationYields304() {
        List<CatalogEntry> items = List.of(item("a", T0.minusSeconds(3600)));
        Instant first = CrawlConditional.lastModified("acme", items);
        now = T0.plusSeconds(300);
        Instant later = CrawlConditional.lastModified("acme", items);
        assertEquals(first, later, "an unchanged document keeps a stable validator");
        assertTrue(CrawlConditional.notModified(revalidating(first), later), "a revalidating crawler gets 304");
    }

    @Test
    void aRemovalAdvancesTheValidatorSoStaleCopiesAreRefetched() {
        // The naive max(updatedAt) blind spot: deleting an item only REMOVES an mtime — the max never moves.
        CatalogEntry a = item("a", T0.minusSeconds(7200));
        CatalogEntry b = item("b", T0.minusSeconds(3600));
        Instant before = CrawlConditional.lastModified("acme", List.of(a, b));

        now = T0.plusSeconds(600); // the entry is deleted ten minutes later
        Instant after = CrawlConditional.lastModified("acme", List.of(a));
        assertTrue(after.isAfter(before), "a removal must advance the validator");
        assertFalse(CrawlConditional.notModified(revalidating(before), after),
                "a crawler holding the pre-removal copy gets a fresh 200, never a stale 304");
    }

    @Test
    void anAdditionWithoutUpdatedAtStillAdvances() {
        Instant before = CrawlConditional.lastModified("acme", List.of(item("a", T0.minusSeconds(3600))));
        now = T0.plusSeconds(60);
        Instant after = CrawlConditional.lastModified("acme",
                List.of(item("a", T0.minusSeconds(3600)), item("new", null)));
        assertTrue(after.isAfter(before), "a new null-updatedAt item is still a document change");
    }

    @Test
    void aFutureMtimeCannotPinTheValidator() {
        // Clock skew / a touched file: a future updatedAt would otherwise mask every later real change.
        Instant seeded = CrawlConditional.lastModified("acme", List.of(item("a", T0.plusSeconds(86400))));
        assertFalse(seeded.isAfter(now), "the validator is clamped to now, never in the future");
        now = T0.plusSeconds(120);
        Instant advanced = CrawlConditional.lastModified("acme",
                List.of(item("a", T0.plusSeconds(86400)), item("b", null)));
        assertTrue(advanced.isAfter(seeded), "later changes still advance past the clamped value");
    }

    @Test
    void aSameSecondChangeIsReDetectedOnceTheClockMoves() {
        // A change landing in the same second as the previous advance cannot advance a second-granular
        // validator — the state must NOT absorb it (or it would 304 forever); the next request re-detects it.
        Instant seeded = CrawlConditional.lastModified("acme", List.of(item("a", null), item("b", null)));
        Instant sameSecond = CrawlConditional.lastModified("acme", List.of(item("a", null)));
        assertEquals(seeded, sameSecond, "within the same second the validator cannot advance yet");
        now = T0.plusSeconds(1);
        Instant next = CrawlConditional.lastModified("acme", List.of(item("a", null)));
        assertTrue(next.isAfter(seeded), "the held change advances as soon as the clock moves");
    }

    @Test
    void restartSeedsWithNowSoAcrossRestartRemovalsAreNeverMasked() {
        Instant before = CrawlConditional.lastModified("acme",
                List.of(item("a", T0.minusSeconds(7200)), item("b", T0.minusSeconds(3600))));
        CrawlConditional.reset(); // the app restarts; the entry was deleted while it was down
        CrawlConditional.clock = () -> now;
        now = T0.plusSeconds(900);
        Instant after = CrawlConditional.lastModified("acme", List.of(item("a", T0.minusSeconds(7200))));
        assertTrue(after.isAfter(before), "seeding with now (not the content mtime) covers the restart gap");
        assertFalse(CrawlConditional.notModified(revalidating(before), after),
                "a crawler from before the restart re-fetches and drops the removed URL");
    }

    /**
     * A change of the host-computed URLs alone — the site starts serving a second locale, so every item gains an
     * alternate — reshapes the sitemap while no item's id, path, mtime or indexability moves. 1.x left it out of the
     * fingerprint, so a revalidating crawler kept the old document; since 2.0.0 the URLs are part of it.
     */
    @Test
    void aChangeOfTheComputedUrlsAloneAdvancesTheValidator() {
        ContentRef ref = new ContentRef("a", "/a", Map.of(), List.of(EN, ES), Map.of("en", "a"), Map.of(), "",
                "__site__/acme/a", true, T0.minusSeconds(3600), false, null, null, Map.of());
        Instant before = CrawlConditional.lastModified("acme", List.of(Entries.of("pages", ref, SITE)));
        now = T0.plusSeconds(60);
        SiteView bilingual = new SiteView("acme", "Acme", EN, List.of(EN, ES), null);
        Instant after = CrawlConditional.lastModified("acme", List.of(Entries.of("pages", ref, bilingual)));
        assertTrue(after.isAfter(before), "a new alternate is a document change");
        assertFalse(CrawlConditional.notModified(revalidating(before), after));
    }

    /** …and so does a canonical override appearing (it moves the item out of the sitemap). */
    @Test
    void aNewCanonicalOverrideAdvancesTheValidator() {
        ContentRef plain = new ContentRef("a", "/a", Map.of(), List.of(EN), Map.of("en", "a"), Map.of(), "",
                "__site__/acme/a", true, null, false, null, null, Map.of());
        ContentRef elsewhere = new ContentRef("a", "/a", Map.of(), List.of(EN), Map.of("en", "a"), Map.of(), "",
                "__site__/acme/a", true, null, false, "https://other.test/a", null, Map.of());
        Instant before = CrawlConditional.lastModified("acme", List.of(Entries.of("pages", plain, SITE)));
        now = T0.plusSeconds(60);
        Instant after = CrawlConditional.lastModified("acme", List.of(Entries.of("pages", elsewhere, SITE)));
        assertTrue(after.isAfter(before));
    }

    // --- document-level facts (2.1.0) -------------------------------------------------------------------------

    /**
     * llms.txt prints the site name and {@code geo.summary}, which no catalog item carries; until 2.1.0 the validator
     * folded the items only, so editing either changed the document while a revalidating crawler kept its copy.
     */
    @Test
    void aChangedDocumentFactAdvancesTheValidator() {
        List<CatalogEntry> items = List.of(item("a", T0.minusSeconds(3600)));
        Instant before = CrawlConditional.lastModified("acme", CrawlConditional.LLMS, items,
                List.of("Acme", "We build things."));
        now = T0.plusSeconds(60);
        assertEquals(before, CrawlConditional.lastModified("acme", CrawlConditional.LLMS, items,
                List.of("Acme", "We build things.")), "unchanged facts keep the validator");
        Instant after = CrawlConditional.lastModified("acme", CrawlConditional.LLMS, items,
                List.of("Acme", "We build better things."));
        assertEquals(now, after, "a fact carries no mtime of its own, so the change advances to now");
        assertFalse(CrawlConditional.notModified(revalidating(before), after));
    }

    /** Facts are length-prefixed: moving characters from one fact to the next is a different document. */
    @Test
    void factsCannotAliasByShiftingCharactersAcrossTheBoundary() {
        List<CatalogEntry> items = List.of(item("a", T0.minusSeconds(3600)));
        Instant before = CrawlConditional.lastModified("acme", CrawlConditional.LLMS, items, List.of("Acme", "Co"));
        now = T0.plusSeconds(60);
        Instant after = CrawlConditional.lastModified("acme", CrawlConditional.LLMS, items, List.of("AcmeC", "o"));
        assertTrue(after.isAfter(before), "['Acme','Co'] and ['AcmeC','o'] are different documents");
        now = T0.plusSeconds(120);
        Instant nulled = CrawlConditional.lastModified("acme", CrawlConditional.LLMS, items,
                java.util.Arrays.asList("AcmeC", null));
        assertTrue(nulled.isAfter(after), "a fact that disappears is a change too (null folds as empty)");
    }

    /**
     * Each document keeps its own validator: llms.txt folds facts the sitemap does not, and one shared record per
     * site would let the two documents advance each other's validator on every alternate request.
     */
    @Test
    void theSitemapAndLlmsTxtKeepSeparateValidators() {
        List<CatalogEntry> items = List.of(item("a", T0.minusSeconds(3600)));
        Instant sitemap = CrawlConditional.lastModified("acme", items);
        now = T0.plusSeconds(60);
        CrawlConditional.lastModified("acme", CrawlConditional.LLMS, items, List.of("Acme", "One"));
        now = T0.plusSeconds(120);
        CrawlConditional.lastModified("acme", CrawlConditional.LLMS, items, List.of("Acme", "Two"));
        now = T0.plusSeconds(180);
        assertEquals(sitemap, CrawlConditional.lastModified("acme", items),
                "llms.txt's changes do not move the sitemap's validator");
        assertEquals(sitemap, CrawlConditional.lastModified("acme", CrawlConditional.SITEMAP, items, List.of()),
                "the two-argument form is the sitemap's");
    }
}
