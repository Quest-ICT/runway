package mx.quest.searchlucene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.testkit.EventDispatch;
import mx.quest.runway.spi.testkit.FakeContentCatalog;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import mx.quest.search.SiteSearchCorpus;
import mx.quest.search.spi.SearchCorpus;
import mx.quest.search.spi.SearchHit;
import mx.quest.search.spi.SearchQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Lucene engine: tiered constant-score ranking (exact phrase &gt; near &gt; scattered), fuzzy matching,
 * per-locale stemming analyzers, heap and disk storage, {@code <mark>} highlighting, the 8 per-tenant tuning knobs
 * (read per query through {@code PluginSettings}), the index's freshness (the plugin's {@code SiteEventListener}, the
 * reindex hook) and the {@code SiteSearchEngine} SPI contract (never throws, empty index ⇒ no results, limit honored).
 *
 * <p>The corpus is the search plugin's own {@link SiteSearchCorpus} (search 2.0.0) over the testkit's
 * {@link FakeContentCatalog}, with the search plugin's settings in a second {@link FakePluginSettings} — so the
 * exclusion rule exercised here is the one the search plugin applies on a real deployment, not a copy of it.
 */
class LuceneSearchEngineTest {

    private static final String SITE = "acme";

    private FakeContentCatalog catalog;
    private FakePluginSettings searchSettings;
    private FakePluginSettings luceneSettings;
    private FakeSiteServices sites;
    private SearchCorpus corpus;
    private LuceneStorageProps storage;
    private LuceneSearchEngine engine;

    // ------------------------------------------------------------------------------------------------ fixtures

    /** A published English page (source {@code pages}, id = slug) at {@code /<id>}. */
    static CatalogEntry item(String id, String title, String description) {
        return item("pages", id, "en", title, description);
    }

    /** A published item of {@code source}, available in {@code lang} only, at {@code /<id>}. */
    static CatalogEntry item(String source, String id, String lang, String title, String description) {
        Locale locale = Locale.forLanguageTag(lang);
        ContentRef ref = new ContentRef(id, "/" + id, Map.of(), List.of(locale), Map.of(lang, title),
                Map.of(lang, description), "", "tpl", true, Instant.EPOCH, false, null, null, Map.of());
        return new CatalogEntry(source, ref, List.of(new LocalizedUrl(locale, "/" + id, null)), null);
    }

    private void site(String id, Locale defaultLocale) {
        sites.site(new SiteView(id, id, defaultLocale, List.of(defaultLocale), null));
        catalog.site(id);
    }

    /** Replaces the site's listing. */
    private void corpus(String siteId, CatalogEntry... items) {
        catalog.clear(siteId);
        for (CatalogEntry item : items) {
            catalog.entry(siteId, item);
        }
    }

    private void corpus(CatalogEntry... items) {
        corpus(SITE, items);
    }

    /** The item's raw indexable material — what the catalog's {@code indexableText} answers for it. */
    private void body(CatalogEntry item, String html) {
        catalog.text(SITE, item.compositeId(), html);
    }

    private void newEngine() {
        if (engine != null) {
            engine.close();
        }
        engine = new LuceneSearchEngine(corpus, sites, luceneSettings, storage);
    }

    @BeforeEach
    void setUp() {
        catalog = new FakeContentCatalog();
        searchSettings = new FakePluginSettings();
        luceneSettings = new FakePluginSettings();
        sites = new FakeSiteServices();
        corpus = new SiteSearchCorpus(catalog, searchSettings);
        storage = new LuceneStorageProps(); // heap by default
        site(SITE, Locale.ENGLISH);
        newEngine();
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    private List<SearchHit> search(String q, int limit) {
        return engine.search(new SearchQuery(SITE, q, Locale.ENGLISH, limit));
    }

    private static List<String> urls(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::url).toList();
    }

    // ------------------------------------------------------------------------------------------ SPI contract

    @Test
    void keyPriorityAndAvailability() {
        assertEquals("search/lucene", engine.key());
        assertEquals(100, engine.priority(), "outranks the built-in engine");
        assertTrue(engine.available(SITE), "always available — builds on demand");
    }

    @Test
    void emptyIndexReturnsNoResultsAndNeverThrows() {
        assertTrue(search("anything", 20).isEmpty());
    }

    @Test
    void aCorpusFailureDegradesToNoResultsRatherThanThrowing() {
        // The corpus blowing up must not propagate out of search() — the SPI never throws for a query.
        SearchCorpus failing = new SearchCorpus() {
            @Override
            public List<CatalogEntry> corpus(String siteId) {
                throw new IllegalStateException("catalog down");
            }

            @Override
            public String indexableText(String siteId, CatalogEntry entry, Locale locale) {
                throw new IllegalStateException("catalog down");
            }

            @Override
            public void refresh(String siteId) {
                throw new IllegalStateException("catalog down");
            }
        };
        try (LuceneSearchEngine broken = new LuceneSearchEngine(failing, sites, luceneSettings, storage)) {
            assertTrue(broken.search(new SearchQuery(SITE, "runway", Locale.ENGLISH, 20)).isEmpty(),
                    "a failure returns no results, not an exception");
            assertEquals(0, broken.indexedDocuments(SITE));
        }
    }

    @Test
    void anUnsafeOrUnknownSiteAnswersNothingAndNeverThrows() {
        corpus(item("guide", "Runway Guide", ""));
        assertTrue(engine.search(new SearchQuery("../etc", "runway", Locale.ENGLISH, 20)).isEmpty(),
                "an unsafe site id is refused by the services; the engine answers no results");
        assertEquals(0, engine.indexedDocuments("../etc"));
        assertTrue(engine.search(new SearchQuery("ghost", "runway", Locale.ENGLISH, 20)).isEmpty(),
                "an unknown site has an empty index");
        assertEquals(0, engine.reindex("ghost"));
    }

    // ------------------------------------------------------------------------- matched words (since 3.1.0)

    @Test
    void aHitCarriesTheSurfaceFormsTheStemmedQueryMatched() {
        site("es-site", Locale.forLanguageTag("es"));
        CatalogEntry mba = item("pages", "mba", "es", "Maestrías ejecutivas", "");
        corpus("es-site", mba, item("pages", "otra", "es", "Contacto", ""));
        catalog.text("es-site", mba.compositeId(), "<p>Nuestra maestría dura dos años.</p>");
        List<SearchHit> hits = engine.search(new SearchQuery("es-site", "maestría", Locale.forLanguageTag("es"), 10));
        assertEquals(List.of("/mba"), urls(hits));
        assertEquals(List.of("maestrías", "maestría"), hits.get(0).terms(),
                "the words as the page writes them, title first, so the target page can highlight them");
    }

    @Test
    void stopWordsOfTheQueryAreNotReported() {
        CatalogEntry guide = item("guide", "The Guide", "");
        corpus(guide);
        body(guide, "<p>The art of the deployment.</p>");
        List<SearchHit> hits = search("the deployment", 10);
        assertEquals(List.of("deployment"), hits.get(0).terms(), "'the' is a stop word: it matched nothing");
    }

    @Test
    void aTypoToleratedMatchReportsTheWordItFound() {
        CatalogEntry guide = item("guide", "Guide", "");
        corpus(guide);
        body(guide, "<p>A small framework.</p>");
        List<SearchHit> hits = search("framewrok", 10);
        assertEquals(List.of("/guide"), urls(hits), "fuzzy matching is on by default");
        assertEquals(List.of("framework"), hits.get(0).terms(), "the page's own spelling is the word to highlight");
    }

    @Test
    void typoToleranceDecidesWhetherNearTermsAreReported() {
        CatalogEntry guide = item("guide", "Guide", "");
        corpus(guide);
        body(guide, "<p>Deploy the deployment; deployer.</p>");
        // English stemming: deployment and deployer are "deploy", while deploy itself is "deploi", one edit away.
        assertEquals(List.of("deploy", "deployment", "deployer"), search("deployment", 10).get(0).terms(),
                "fuzzy on (the default): the near term is a match, so its word is reported");
        luceneSettings.site(SITE, Map.of("fuzzy", false));
        assertEquals(List.of("deployment", "deployer"), search("deployment", 10).get(0).terms(),
                "fuzzy off: only the words whose stem is the query's");
    }

    @Test
    void matchesTitleAndLinksToThePage() {
        corpus(item("guide", "Runway Guide", "How to deploy."),
                item("about", "About", "Our team."));
        List<SearchHit> hits = search("runway", 20);
        assertEquals(1, hits.size());
        assertEquals("Runway Guide", hits.get(0).title());
        assertEquals("/guide", hits.get(0).url(), "the hit links to the URL the kernel computed for the entry");
    }

    @Test
    void matchesBodyTextNotJustMetadata() {
        CatalogEntry post = item("post", "Weekly Notes", "News.");
        corpus(post);
        body(post, "<h1>Weekly Notes</h1><p>We shipped the new <b>lucene</b> engine.</p>");
        List<SearchHit> hits = search("lucene", 20);
        assertEquals(1, hits.size(), "a body-only term matches — full-text, not metadata-only");
        assertEquals("Weekly Notes", hits.get(0).title());
    }

    @Test
    void aContentSourceItemIsIndexedAlongsidePages() {
        // A blog entry (any content source's item) enters the SAME Lucene index as pages — same corpus, same
        // tiers, no engine-side special case.
        CatalogEntry entry = item("blog", "hello-world", "en", "Hello World", "The first entry.");
        corpus(item("guide", "Runway Guide", "Deploy runway."), entry);
        body(entry, "Runway makes multi-tenant hosting simple.");
        List<SearchHit> hits = search("runway", 20);
        assertEquals(2, hits.size(), "the blog entry and the page are both indexed");
        assertTrue(urls(hits).contains("/hello-world"), "the entry's hit links to the entry's own URL");
    }

    @Test
    void analysisIsTokenizedAndCaseInsensitive() {
        corpus(item("p", "The Runway Platform", ""));
        assertFalse(search("RUNWAY", 20).isEmpty(), "matching is case-insensitive (analyzed)");
        assertFalse(search("platform runway", 20).isEmpty(), "multi-term matches");
    }

    @Test
    void nonMatchesReturnNothing() {
        corpus(item("live", "Contact", "reach us"));
        assertTrue(search("runway", 20).isEmpty());
    }

    @Test
    void theSearchPluginsExclusionsReachTheIndexThroughItsCorpus() {
        // The exclude list is the SEARCH plugin's per-site setting, applied by its SearchCorpus — this engine never
        // reads it. "/Privacy" exercises the tolerated leading slash and the case fold; "hello-world" matches a
        // content-source item's own id, so it drops the blog entry too.
        searchSettings.site(SITE, Map.of("exclude", List.of("/Privacy", "hello-world")));
        CatalogEntry privacy = item("privacy", "Privacy", "Runway privacy policy.");
        corpus(privacy, item("guide", "Runway Guide", "Deploy runway."),
                item("blog", "hello-world", "en", "Runway Hello", "About runway."));
        body(privacy, "runway runway runway");
        List<SearchHit> hits = search("runway", 20);
        assertEquals(List.of("/guide"), urls(hits),
                "the excluded page AND the excluded source item are left out of the index");
        assertEquals(1, engine.indexedDocuments(SITE));
    }

    @Test
    void theLimitIsHonored() {
        corpus(item("a", "Runway A", ""), item("b", "Runway B", ""), item("c", "Runway C", ""));
        assertEquals(2, search("runway", 2).size());
    }

    @Test
    void equalScoresKeepTheCorpusOrder() {
        // Lucene breaks a score tie by document number, i.e. the order the corpus listed the entries in.
        corpus(item("c", "Runway", ""), item("a", "Runway", ""), item("b", "Runway", ""));
        assertEquals(List.of("/c", "/a", "/b"), urls(search("runway", 20)));
    }

    // --------------------------------------------------------------------------------------- locale and URL

    @Test
    void anItemMissingFromTheSiteDefaultLanguageIsIndexedAndLinkedInItsOwnLanguage() {
        // A Spanish-only entry on an English site: its title, body and URL are its Spanish ones (CatalogEntry.pathFor
        // its primary locale) — never an unprefixed default-language path, which 404s for such an item.
        ContentRef ref = new ContentRef("receta", "/receta", Map.of("es", "/es/receta"), List.of(Locale.forLanguageTag("es")),
                Map.of("es", "Receta de tamales"), Map.of("es", "Cocina"), "", "tpl", true, Instant.EPOCH, false,
                null, null, Map.of());
        CatalogEntry spanish = new CatalogEntry("blog", ref,
                List.of(new LocalizedUrl(Locale.forLanguageTag("es"), "/es/receta", null)), null);
        corpus(spanish);
        catalog.text(SITE, spanish.compositeId(), "<p>tamales verdes</p>");
        List<SearchHit> hits = search("tamales", 20);
        assertEquals(1, hits.size());
        assertEquals("/es/receta", hits.get(0).url(), "linked at the item's real (Spanish) address");
        assertEquals("Receta de tamales", hits.get(0).title());
    }

    @Test
    void anItemWithoutATitleIsShownUnderItsId() {
        ContentRef ref = new ContentRef("untitled-note", "/untitled-note", Map.of(), List.of(Locale.ENGLISH), Map.of(),
                Map.of(), "", "tpl", true, Instant.EPOCH, false, null, null, Map.of());
        CatalogEntry untitled = new CatalogEntry("blog", ref,
                List.of(new LocalizedUrl(Locale.ENGLISH, "/untitled-note", null)), null);
        corpus(untitled);
        body(untitled, "<p>zeppelin</p>");
        assertEquals("untitled-note", search("zeppelin", 20).get(0).title());
    }

    @Test
    void theIndexableTextIsAskedForInTheItemsPrimaryLocale() {
        List<Locale> asked = new ArrayList<>();
        SearchCorpus recording = new SearchCorpus() {
            @Override
            public List<CatalogEntry> corpus(String siteId) {
                return List.of(item("guide", "Guide", ""), item("blog", "nota", "es", "Nota", ""));
            }

            @Override
            public String indexableText(String siteId, CatalogEntry entry, Locale locale) {
                asked.add(locale);
                return "";
            }

            @Override
            public void refresh(String siteId) {
            }
        };
        try (LuceneSearchEngine e = new LuceneSearchEngine(recording, sites, luceneSettings, storage)) {
            assertEquals(2, e.indexedDocuments(SITE));
        }
        assertEquals(List.of(Locale.ENGLISH, Locale.forLanguageTag("es")), asked,
                "the site default for an item that has it, else the item's own first language");
    }

    // ----------------------------------------------------------------------------------------- index freshness

    @Test
    void reindexRefreshesTheListingAndRebuildsFromCurrentContentImmediately() {
        corpus(item("a", "Runway", ""));
        assertEquals(1, engine.indexedDocuments(SITE));
        // The corpus grows; the cached index (fresh for 30 s) would still report 1 until reindex.
        corpus(item("a", "Runway", ""), item("b", "Runway Two", ""));
        assertEquals(1, engine.indexedDocuments(SITE), "the cached index still serves inside the TTL");
        assertEquals(2, engine.reindex(SITE),
                "the SPI reindex hook drops the cache and rebuilds NOW, returning the fresh count");
        assertEquals(List.of(SITE), catalog.refreshed(),
                "…after refreshing the shared listing through the corpus (out-of-band edits announce nothing)");
    }

    /** Proves that delivering {@code deliver} drops the cached index: before it the old index serves. */
    private void assertTheIndexIsDroppedBy(Runnable deliver) {
        corpus(item("a", "Runway", ""));
        assertEquals(1, search("runway", 20).size());
        corpus(item("a", "Runway", ""), item("b", "Runway Two", ""));
        assertEquals(1, search("runway", 20).size(), "before any change the cached index still serves");
        deliver.run();
        assertEquals(2, search("runway", 20).size(), "the change drops the index; the next query rebuilds");
    }

    private EventDispatch events() {
        return new EventDispatch("search-lucene", Set.of()).listener(new LuceneSiteEvents(engine));
    }

    @Test
    void aPageChangeDropsTheSiteIndex() {
        EventDispatch events = events();
        assertTheIndexIsDroppedBy(() -> events.page(SITE, "b", List.of(),
                List.of(new LocalizedUrl(Locale.ENGLISH, "/b", null))));
        assertTrue(events.failures().isEmpty());
    }

    @Test
    void aContentSourceChangeDropsTheSiteIndex() {
        EventDispatch events = events();
        assertTheIndexIsDroppedBy(() -> events.catalog(new CatalogChange(SITE, CatalogChange.Cause.CONTENT, "blog",
                "second", "blog", List.of(), List.of(), true)));
    }

    @Test
    void anotherPluginsSettingsSaveDropsTheSiteIndex() {
        // Any plugin's settings may reshape the corpus — the blog's prefix, the search plugin's exclude list — so
        // a SETTINGS change of a plugin other than this one drops the index too (no pluginId scoping).
        EventDispatch events = events();
        assertTheIndexIsDroppedBy(() -> events.settings(SITE, "search"));
    }

    @Test
    void aSiteLifecycleEventDropsTheSiteIndex() {
        EventDispatch events = events();
        assertTheIndexIsDroppedBy(() -> events.lifecycle(SITE, SiteLifecycleEvent.Kind.RESTORED, true));
    }

    @Test
    void aReplayedChangeFromAnotherNodeDropsTheLocalIndexToo() {
        EventDispatch events = events();
        assertTheIndexIsDroppedBy(() -> events.catalog(new CatalogChange(SITE, CatalogChange.Cause.PAGE, "pages",
                "b", null, List.of(), List.of(), false)));
    }

    @Test
    void aChangeOnAnotherSiteLeavesThisSitesIndexAlone() {
        corpus(item("a", "Runway", ""));
        assertEquals(1, search("runway", 20).size());
        corpus(item("a", "Runway", ""), item("b", "Runway Two", ""));
        events().page("other", "x", List.of(), List.of());
        assertEquals(1, search("runway", 20).size(), "only the changed site's index is dropped");
    }

    @Test
    void theListenerIsAConsumer() {
        assertEquals(LuceneSiteEvents.Phase.CONSUMER, new LuceneSiteEvents(engine).phase(),
                "it reads the listing after every source has dropped its state");
    }

    @Test
    void aChangeThatArrivesWhileTheIndexIsBeingBuiltIsNotLostToTheTtl() {
        // A change announced while a build is reading the corpus: the build may hold the old corpus, so it must not be
        // cached as fresh for 30 s — the next query rebuilds.
        AtomicBoolean changeMidBuild = new AtomicBoolean(true);
        List<List<CatalogEntry>> listings = new ArrayList<>(List.of(
                List.of(item("a", "Runway", "")),
                List.of(item("a", "Runway", ""), item("b", "Runway Two", ""))));
        LuceneSearchEngine[] self = new LuceneSearchEngine[1];
        SearchCorpus racing = new SearchCorpus() {
            @Override
            public List<CatalogEntry> corpus(String siteId) {
                List<CatalogEntry> current = listings.size() > 1 ? listings.remove(0) : listings.get(0);
                if (changeMidBuild.getAndSet(false)) {
                    self[0].invalidate(siteId); // the listener runs on another thread in real life
                }
                return current;
            }

            @Override
            public String indexableText(String siteId, CatalogEntry entry, Locale locale) {
                return "";
            }

            @Override
            public void refresh(String siteId) {
            }
        };
        try (LuceneSearchEngine e = new LuceneSearchEngine(racing, sites, luceneSettings, storage)) {
            self[0] = e;
            assertEquals(1, e.search(new SearchQuery(SITE, "runway", Locale.ENGLISH, 20)).size(),
                    "the query that built the index is answered from it");
            assertEquals(2, e.search(new SearchQuery(SITE, "runway", Locale.ENGLISH, 20)).size(),
                    "the next query rebuilds, because a change arrived during the first build");
            assertEquals(2, e.search(new SearchQuery(SITE, "runway", Locale.ENGLISH, 20)).size());
        }
    }

    // ------------------------------------------------------------------------------------------------- tiering

    @Test
    void exactPhraseHitOutranksScatteredTermsHit() {
        CatalogEntry scattered = item("scattered", "Notes", "");
        CatalogEntry exact = item("exact", "Manual", "");
        corpus(scattered, exact);
        body(exact, "<p>The cloud deployment guide explains everything.</p>");
        body(scattered, "<p>cloud infrastructure. a long unrelated paragraph about many topics. "
                + "deployment pipelines. yet another sentence entirely. a helpful guide to nothing.</p>");
        List<SearchHit> hits = search("cloud deployment guide", 20);
        assertEquals(2, hits.size(), "both pages contain all terms");
        assertEquals("Manual", hits.get(0).title(), "the exact-phrase page ranks first (phrase tier)");
        assertTrue(hits.get(0).score() > hits.get(1).score(), "exact-phrase score strictly higher");
    }

    @Test
    void titlePhraseHitOutranksBodyPhraseHit() {
        CatalogEntry bodyHit = item("body", "Some Article", "");
        corpus(bodyHit, item("title", "Runway Deployment Guide", ""));
        body(bodyHit, "<p>Here is the runway deployment guide in the body text.</p>");
        List<SearchHit> hits = search("runway deployment guide", 20);
        assertEquals("Runway Deployment Guide", hits.get(0).title(),
                "the title-phrase page wins (tierPhraseTitle > tierPhraseBody)");
    }

    @Test
    void theTierScoresAddUpAsDocumented() {
        // One title-phrase hit for a three-term query: any-terms (BM25, small) + exact title phrase (4000) +
        // all terms anywhere (300). The body has no phrase, so neither body tier fires.
        corpus(item("p", "Runway Deployment Guide", ""));
        double score = search("runway deployment guide", 20).get(0).score();
        assertTrue(score > 4300 && score < 4300 + 100, "4000 + 300 + a BM25 base, was " + score);
    }

    // --------------------------------------------------------------------------------------------------- fuzzy

    @Test
    void fuzzyQueryToleratesATypo() {
        CatalogEntry p = item("p", "Lucene Handbook", "");
        corpus(p);
        body(p, "<p>All about lucene.</p>");
        // "lucane" is 1 edit from "lucene" (and stays within the fuzzy budget after analysis).
        assertFalse(search("lucane", 20).isEmpty(), "a 1-edit typo still matches with fuzzy on");
    }

    @Test
    void fuzzyCanBeTurnedOffPerSiteAndTheSaveAppliesToTheNextQuery() {
        CatalogEntry p = item("p", "Lucene Handbook", "");
        corpus(p);
        body(p, "<p>All about lucene.</p>");
        assertFalse(search("lucane", 20).isEmpty());
        luceneSettings.site(SITE, Map.of("fuzzy", false));
        assertTrue(search("lucane", 20).isEmpty(), "with fuzzy off, the typo no longer matches — no rebuild needed");
        assertFalse(search("lucene", 20).isEmpty(), "the exact term still matches with fuzzy off");
    }

    // ------------------------------------------------------------------------------------ per-locale analyzers

    @Test
    void englishAnalyzerStemsSoAQueryMatchesAStemmedForm() {
        CatalogEntry p = item("p", "Athletics", "");
        corpus(p);
        body(p, "<p>They run every morning.</p>");
        assertFalse(search("running", 20).isEmpty(), "English stemming: 'running' matches 'run'");
    }

    @Test
    void spanishAnalyzerIsSelectedForASpanishSiteAndStems() {
        // A Spanish-default site: the SpanishAnalyzer stems "productos" and "producto" to the same root.
        site("tienda", Locale.forLanguageTag("es"));
        CatalogEntry cat = item("pages", "cat", "es", "Catálogo", "");
        corpus("tienda", cat);
        catalog.text("tienda", cat.compositeId(), "<p>Todos nuestros productos en oferta.</p>");
        List<SearchHit> hits = engine.search(new SearchQuery("tienda", "producto", Locale.forLanguageTag("es"), 20));
        assertFalse(hits.isEmpty(), "Spanish stemming: singular 'producto' matches plural 'productos'");
    }

    @Test
    void unknownLocaleFallsBackToStandardAnalyzerAndStillMatches() {
        site("kl", Locale.forLanguageTag("tlh"));
        corpus("kl", item("pages", "p", "tlh", "Qapla", "victory"));
        assertFalse(engine.search(new SearchQuery("kl", "victory", Locale.forLanguageTag("tlh"), 20)).isEmpty(),
                "unknown locale still searches via the StandardAnalyzer fallback");
        assertFalse(LocaleAnalyzers.hasLanguageAnalyzer("tlh"), "no language analyzer for Klingon");
        assertTrue(LocaleAnalyzers.hasLanguageAnalyzer("es"), "Spanish has a language analyzer");
    }

    // ------------------------------------------------------------------------------------------ heap vs disk

    @Test
    void diskStorageIndexesAndSearchesLikeHeap(@TempDir Path tmp) {
        corpus(item("guide", "Runway Guide", "How to deploy."), item("other", "Runway Other", "Runway runway."));
        List<SearchHit> heap = search("runway guide", 20);
        storage.setStorage("disk");
        storage.setIndexRoot(tmp.toString());
        newEngine();
        List<SearchHit> disk = search("runway guide", 20);
        assertEquals("disk", engine.storageMode());
        assertEquals(heap, disk, "disk (MMapDirectory) answers exactly what heap answers, scores included");
        assertTrue(Files.isDirectory(tmp.resolve(SITE)), "a per-site index folder was created on disk");
    }

    @Test
    void heapIsTheDefaultStorageMode() {
        assertEquals("heap", engine.storageMode());
    }

    // ------------------------------------------------------------------------------------------ highlighting

    @Test
    void highlightingWrapsTheHitInMarkTags() {
        CatalogEntry p = item("p", "Guide", "");
        corpus(p);
        body(p, "<p>Configuring the deployment pipeline is the first step of any release.</p>");
        List<SearchHit> hits = search("deployment", 20);
        assertEquals(1, hits.size());
        String snippet = hits.get(0).snippet();
        assertTrue(snippet.contains("<mark>deployment</mark>"),
                "the matched term is wrapped in <mark> in the query-focused snippet, was: " + snippet);
    }

    @Test
    void highlightSnippetEscapesPageMarkupSoOnlyMarkTagsSurvive() {
        CatalogEntry p = item("p", "Guide", "");
        corpus(p);
        body(p, "<p>Use the &lt;deployment&gt; token and the deployment button to ship.</p>");
        String snippet = search("deployment", 20).get(0).snippet();
        assertTrue(snippet.contains("<mark>"), "a mark tag is present");
        assertFalse(snippet.contains("<deployment>"), "raw page angle brackets are escaped, not rendered as a tag");
    }

    @Test
    void withoutABodyHitTheSnippetIsTheDescription() {
        corpus(item("p", "Runway", "A short summary."), item("q", "Lone Title", ""));
        assertEquals("A short summary.", search("runway", 20).get(0).snippet());
        assertEquals("", search("lone", 20).get(0).snippet(), "no body and no description: an empty snippet");
    }

    // ----------------------------------------------------------------------------------------- tuning knobs

    @Test
    void titleBoostKnobRaisesATitleMatchOverABodyMatch() {
        CatalogEntry bodyHit = item("bodyhit", "Other", "");
        corpus(item("titlehit", "alpha", ""), bodyHit);
        body(bodyHit, "<p>alpha appears only in the body here.</p>");
        // Single-term query so the phrase tiers don't fire — the any-terms tier (where titleBoost applies) decides.
        luceneSettings.site(SITE, Map.of("titleBoost", 50.0));
        List<SearchHit> hits = search("alpha", 20);
        assertEquals(2, hits.size());
        assertEquals("alpha", hits.get(0).title(), "with a high titleBoost the title-match page ranks first");
    }

    @Test
    void tierScoreKnobsChangeTheAbsoluteScore() {
        corpus(item("p", "Runway Deployment Guide", ""));
        double defaultScore = search("runway deployment guide", 20).get(0).score();
        luceneSettings.site(SITE, Map.of("tierPhraseTitle", 40000.0));
        double boostedScore = search("runway deployment guide", 20).get(0).score();
        assertTrue(boostedScore > defaultScore,
                "raising tierPhraseTitle raises the exact-title-phrase score: " + boostedScore + " > " + defaultScore);
    }

    @Test
    void theDeclaredDefaultsRankExactlyLikeNoSettingsAtAll() {
        // PluginSettings hands the engine the descriptor's defaults under the stored values; they equal the code
        // fallbacks, so a site that never saved the form ranks as the engine's own defaults do.
        CatalogEntry p = item("p", "Runway Deployment Guide", "deploy");
        corpus(p, item("q", "Notes", "runway"));
        body(p, "<p>runway deployment guide and more</p>");
        List<SearchHit> bare = search("runway deployment guide", 20);
        luceneSettings.defaults(Map.of("fuzzy", true, "fuzzyBoost", 0.4, "titleBoost", 8.0, "descriptionBoost", 4.0,
                "tierPhraseTitle", 4000, "tierPhraseBody", 2000, "tierNearPhrase", 800, "tierAllTerms", 300));
        assertEquals(bare, search("runway deployment guide", 20));
    }

    @Test
    void malformedKnobValuesFallBackToDefaultsAndStillSearch() {
        corpus(item("p", "Runway Guide", ""));
        luceneSettings.site(SITE, Map.of(
                "titleBoost", "not-a-number", "fuzzyBoost", "", "tierPhraseTitle", "abc", "fuzzy", "maybe"));
        assertFalse(search("runway", 20).isEmpty(), "malformed config degrades to defaults, never breaks search");
    }

    // ---------------------------------------------------------------------------------------------- console

    @Test
    void consoleModelReportsIndexSizeTitleBoostAndStorageMode() {
        corpus(item("a", "A", ""), item("b", "B", ""));
        LuceneAdminModel model = new LuceneAdminModel(engine);
        assertEquals(2, model.indexedPages(SITE), "the corpus is what the console reports");
        assertEquals("heap", model.storageMode());
        assertEquals("search/lucene", model.engineKey());
        assertEquals("8.0", model.titleBoost(SITE));
        luceneSettings.site(SITE, Map.of("titleBoost", 9.5));
        assertEquals("9.5", model.titleBoost(SITE), "the effective, per-site value");
    }
}
