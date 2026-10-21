package mx.quest.search;

import static mx.quest.search.Fixtures.ACME;
import static mx.quest.search.Fixtures.entry;
import static mx.quest.search.Fixtures.page;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import mx.quest.search.spi.SearchCorpus;
import mx.quest.search.spi.SearchHit;
import mx.quest.search.spi.SearchQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The built-in full-text engine over the {@link SearchCorpus}: title/description/body matching, configurable
 * ranking weights, the per-site exclude list (applied by the corpus), limiting, highlighted snippets that honour the
 * frozen {@link SearchHit} snippet contract, the item's real URL in its primary locale, and freshness — every change
 * the kernel delivers to the plugin's listener drops the index, and a build that raced an invalidation is not kept.
 */
class SimpleSearchEngineTest {

    private FakeContentCatalog catalog;
    private FakePluginSettings settings;
    private final Map<String, Object> config = new HashMap<>();
    private SimpleSearchEngine engine;
    private EventDispatch events;

    @BeforeEach
    void setUp() {
        catalog = new FakeContentCatalog().site("acme");
        settings = new FakePluginSettings();
        applySettings();
        FakeSiteServices kernel = new FakeSiteServices().site(ACME);
        engine = new SimpleSearchEngine(new SiteSearchCorpus(catalog, settings), kernel, settings,
                new SearchProperties());
        events = new EventDispatch("search", Set.of())
                .listener(new SearchSiteEvents(engine, new NoResultsLog(new Fixtures.NoFiles(), settings,
                        Clock.systemUTC(), false)));
    }

    private void applySettings() {
        settings.site("acme", config);
    }

    private void corpus(CatalogEntry... entries) {
        catalog.clear("acme");
        for (CatalogEntry e : entries) {
            catalog.entry("acme", e);
        }
    }

    private List<SearchHit> search(String q, int limit) {
        return engine.search(new SearchQuery("acme", q, Locale.ENGLISH, limit));
    }

    @Test
    void keyAndPriorityAndAvailability() {
        assertEquals("search/simple", engine.key());
        assertEquals(0, engine.priority());
        assertTrue(engine.available("acme"), "the built-in engine is always available (no external index)");
    }

    @Test
    void matchesTitleAndDescriptionAndLinksToThePage() {
        corpus(page("guide", "Runway Guide", "How to deploy Runway."), page("about", "About Us", "Our team."));
        List<SearchHit> hits = search("runway", 20);
        assertEquals(1, hits.size());
        assertEquals("Runway Guide", hits.get(0).title());
        assertEquals("/guide", hits.get(0).url(), "the hit links to the entry's host-computed URL");
        assertFalse(hits.get(0).snippet().isBlank(), "the description becomes the snippet");
    }

    @Test
    void aBodyOnlyTermIsFound() {
        // Neither title nor description carry "kestrel"; only the indexable material does — fetched through
        // the corpus (the catalog's capability-gated seam) and stripped of markup before indexing.
        CatalogEntry post = page("post", "Blog", "Latest news.");
        corpus(post);
        catalog.text("acme", post.compositeId(), "<p>The <b>kestrel</b> is a small falcon seen over the runway.</p>");
        List<SearchHit> hits = search("kestrel", 20);
        assertEquals(1, hits.size(), "a term present only in the body is found");
        assertEquals("Blog", hits.get(0).title());
    }

    @Test
    void bodyScriptAndStyleAreNotIndexed() {
        CatalogEntry p = page("p", "Page", "");
        corpus(p);
        catalog.text("acme", p.compositeId(),
                "<style>.x{color:secretword}</style><script>var secretword=1;</script><p>hello</p>");
        assertTrue(search("secretword", 20).isEmpty(), "text inside <script>/<style> is code, not indexable prose");
        assertEquals(1, search("hello", 20).size(), "visible prose is indexed");
    }

    @Test
    void aContentSourceItemIsIndexedAlongsidePages() {
        CatalogEntry blogEntry = entry("blog", "hello-world", "Hello World", "The first entry.");
        corpus(page("guide", "Runway Guide", "Deploy runway."), blogEntry);
        catalog.text("acme", blogEntry.compositeId(), "Runway makes multi-tenant hosting simple.");
        List<SearchHit> hits = search("runway", 20);
        assertEquals(2, hits.size(), "the blog entry and the page are both indexed");
        assertTrue(hits.stream().anyMatch(h -> h.url().equals("/hello-world")),
                "the entry's hit links to the entry's own URL");
    }

    @Test
    void anItemMissingFromTheDefaultLanguageLinksToItsRealUrlAndIndexesThatLanguage() {
        // n6: an item that exists only in Spanish on an English-default site. 1.x linked "/solo" (the unprefixed
        // default-language path, a 404); 2.0 links the item's real Spanish URL and reads its Spanish text.
        SiteView bilingual = new SiteView("acme", "Acme", Locale.ENGLISH,
                List.of(Locale.ENGLISH, Locale.of("es")), null);
        FakeSiteServices kernel = new FakeSiteServices().site(bilingual);
        SearchCorpus base = new SiteSearchCorpus(catalog, settings);
        SearchCorpus spanishText = new SearchCorpus() { // the source only has a body in the item's own language
            @Override
            public List<CatalogEntry> corpus(String siteId) {
                return base.corpus(siteId);
            }

            @Override
            public String indexableText(String siteId, CatalogEntry entry, Locale locale) {
                return locale.getLanguage().equals("es") ? base.indexableText(siteId, entry, locale) : "";
            }

            @Override
            public void refresh(String siteId) {
                base.refresh(siteId);
            }
        };
        engine = new SimpleSearchEngine(spanishText, kernel, settings, new SearchProperties());
        // A stray English title in the map must not win: the item is not available in English.
        ContentRef ref = new ContentRef("solo", "/solo", Map.of("es", "/solo-es"), List.of(Locale.of("es")),
                Map.of("en", "Stray English", "es", "Guía de pista"), Map.of("es", "Solo en español."), "", "tpl",
                true, Instant.EPOCH, false, null, null, Map.of());
        CatalogEntry solo = new CatalogEntry("pages", ref,
                List.of(new LocalizedUrl(Locale.of("es"), "/es/solo-es", null)), "/es/solo-es");
        corpus(solo);
        catalog.text("acme", solo.compositeId(), "<p>contenido mariposa</p>");

        List<SearchHit> hits = search("mariposa", 20);
        assertEquals(1, hits.size(), "the Spanish body is indexed");
        assertEquals("/es/solo-es", hits.get(0).url(), "the hit links the item's real (Spanish) URL, not /solo");
        assertEquals("Guía de pista", hits.get(0).title());
    }

    // --- one document per item and language (2.2.0) ----------------------------------------------------------

    private static final Locale ES = Locale.of("es");
    private static final Locale FR = Locale.FRENCH;

    /**
     * A trilingual site (English default) whose corpus returns each language's own body — what the kernel's rendered
     * pages give since 1.0.0-rc6, where a page's copy comes from the site's bundles in the requested language.
     */
    private void trilingual(Map<String, Map<String, String>> bodiesById) {
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH, ES, FR), null);
        SearchCorpus base = new SiteSearchCorpus(catalog, settings);
        SearchCorpus perLanguage = new SearchCorpus() {
            @Override
            public List<CatalogEntry> corpus(String siteId) {
                return base.corpus(siteId);
            }

            @Override
            public String indexableText(String siteId, CatalogEntry entry, Locale locale) {
                return bodiesById.getOrDefault(entry.ref().id(), Map.of()).getOrDefault(locale.getLanguage(), "");
            }

            @Override
            public void refresh(String siteId) {
                base.refresh(siteId);
            }
        };
        engine = new SimpleSearchEngine(perLanguage, new FakeSiteServices().site(site), settings,
                new SearchProperties());
    }

    private static CatalogEntry about() {
        ContentRef ref = new ContentRef("about", "/about", Map.of("es", "/acerca", "fr", "/a-propos"),
                List.of(Locale.ENGLISH, ES, FR), Map.of("en", "About", "es", "Acerca", "fr", "À propos"),
                Map.of(), "", "tpl", true, Instant.EPOCH, false, null, null, Map.of());
        return new CatalogEntry("pages", ref, List.of(new LocalizedUrl(Locale.ENGLISH, "/about", null),
                new LocalizedUrl(ES, "/es/acerca", null), new LocalizedUrl(FR, "/fr/a-propos", null)), "/about");
    }

    private static CatalogEntry englishOnly() {
        ContentRef ref = new ContentRef("news", "/news", Map.of(), List.of(Locale.ENGLISH), Map.of("en", "News"),
                Map.of(), "", "tpl", true, Instant.EPOCH, false, null, null, Map.of());
        return new CatalogEntry("pages", ref, List.of(new LocalizedUrl(Locale.ENGLISH, "/news", null)), "/news");
    }

    private List<SearchHit> search(String q, Locale locale) {
        return engine.search(new SearchQuery("acme", q, locale, 20));
    }

    @Test
    void eachLanguageSearchesItsOwnDocuments() {
        trilingual(Map.of("about", Map.of("en", "<p>A roastery that stays up late.</p>",
                "es", "<p>Una tostaduría que trasnocha.</p>", "fr", "<p>Une torréfaction nocturne.</p>")));
        corpus(about());

        List<SearchHit> es = search("trasnocha", ES);
        assertEquals(1, es.size(), "the Spanish text is searchable from a Spanish results page");
        assertEquals("/es/acerca", es.get(0).url(), "the hit links the Spanish address");
        assertEquals("Acerca", es.get(0).title(), "the Spanish title");
        assertTrue(es.get(0).snippet().contains("<mark>trasnocha</mark>"), es.get(0).snippet());

        List<SearchHit> fr = search("torréfaction", FR);
        assertEquals(1, fr.size());
        assertEquals("/fr/a-propos", fr.get(0).url());

        assertTrue(search("trasnocha", Locale.ENGLISH).isEmpty(), "English searches the English document only");
        assertTrue(search("roastery", ES).isEmpty(), "Spanish searches the Spanish document only");
        assertEquals("/about", search("roastery", Locale.ENGLISH).get(0).url());
    }

    @Test
    void anItemMissingFromTheQueryLanguageIsFoundInItsPrimaryLanguage() {
        trilingual(Map.of("about", Map.of("es", "<p>tostaduría</p>"), "news", Map.of("en", "<p>harvest report</p>")));
        corpus(about(), englishOnly());
        List<SearchHit> hits = search("harvest", ES);
        assertEquals(1, hits.size(), "a page the site publishes in English only is still found from Spanish");
        assertEquals("/news", hits.get(0).url(), "…at its English address, the only one it has");
        assertEquals(1, search("tostaduría", ES).size(), "the view also holds the Spanish documents");
    }

    @Test
    void aLanguageTheSiteDoesNotSpeakSearchesTheDefaultLanguage() {
        trilingual(Map.of("about", Map.of("en", "<p>roastery</p>", "es", "<p>tostaduría</p>")));
        corpus(about());
        List<SearchHit> hits = search("roastery", Locale.GERMAN);
        assertEquals(1, hits.size());
        assertEquals("/about", hits.get(0).url());
    }

    @Test
    void aSearchFromInsideTheSitesOwnIndexBuildAnswersNothingInsteadOfRecursing() {
        SearchCorpus base = new SiteSearchCorpus(catalog, settings);
        SimpleSearchEngine[] self = new SimpleSearchEngine[1];
        java.util.concurrent.atomic.AtomicInteger inner = new java.util.concurrent.atomic.AtomicInteger(-1);
        SearchCorpus reentrant = new SearchCorpus() { // a page whose widget searches while it is being rendered
            @Override
            public List<CatalogEntry> corpus(String siteId) {
                return base.corpus(siteId);
            }

            @Override
            public String indexableText(String siteId, CatalogEntry entry, Locale locale) {
                inner.set(self[0].search(new SearchQuery("acme", "kestrel", locale, 5)).size());
                return "<p>kestrel</p>";
            }

            @Override
            public void refresh(String siteId) {
                base.refresh(siteId);
            }
        };
        engine = new SimpleSearchEngine(reentrant, new FakeSiteServices().site(ACME), settings, new SearchProperties());
        self[0] = engine;
        corpus(page("p", "Page", ""));
        assertEquals(1, search("kestrel", 20).size(), "the outer search is answered");
        assertEquals(0, inner.get(), "the search from inside the build answered nothing (and did not recurse)");
    }

    @Test
    void theDocumentCountCountsItemsNotLanguages() {
        trilingual(Map.of());
        corpus(about(), englishOnly());
        assertEquals(2, engine.documentCount("acme"));
        assertEquals(2, engine.reindex("acme"));
    }

    @Test
    void aTitleMatchOutranksADescriptionOnlyMatch() {
        corpus(page("mention", "Pricing", "We also mention runway here."), page("titled", "Runway", "Nothing."));
        List<SearchHit> hits = search("runway", 20);
        assertEquals(2, hits.size());
        assertEquals("Runway", hits.get(0).title(), "the title match ranks first (higher weight)");
    }

    @Test
    void rankingWeightsChangeTheOrder() {
        corpus(page("t", "Runway", "plain."),        // term in TITLE only
                page("d", "Plain", "runway runway runway.")); // term x3 in DESCRIPTION only
        assertEquals("Runway", search("runway", 20).get(0).title(), "with defaults the title match leads");

        // Flip the weights so a description match is worth far more than a title match: the order reverses.
        // Zero the phrase bonuses so this isolates the title-vs-description WEIGHT.
        config.put("titleWeight", 1);
        config.put("descriptionWeight", 100);
        config.put("phraseTitleBonus", 0);
        config.put("phraseBodyBonus", 0);
        applySettings();
        assertEquals("Plain", search("runway", 20).get(0).title(),
                "raising descriptionWeight above titleWeight makes the description-only match rank first");
    }

    @Test
    void theAllTermsBonusRewardsAPageWithEveryTerm() {
        corpus(page("both", "Alpha", "beta"), page("one", "Alpha alpha", ""));
        config.put("titleWeight", 10);
        config.put("descriptionWeight", 1);
        config.put("phraseTitleBonus", 0);
        config.put("allTermsBonus", 0);
        applySettings();
        // Without the bonus both score 10 (alpha in the title) + 1 for "both" (beta in the description): "both" leads
        // by a hair; with a big bonus it leads by far — and a page missing a term never gets it.
        List<SearchHit> plain = search("alpha beta", 20);
        config.put("allTermsBonus", 1000);
        applySettings();
        List<SearchHit> bonus = search("alpha beta", 20);
        assertEquals("Alpha", bonus.get(0).title());
        assertEquals(plain.get(0).score() + 1000, bonus.get(0).score(), 0.0001, "the bonus is added once");
        assertEquals(plain.get(1).score(), bonus.get(1).score(), 0.0001, "a page missing a term gets no bonus");
    }

    @Test
    void anExcludedIdIsAbsentFromResults() {
        corpus(page("privacy", "Privacy", "Runway privacy policy."), page("guide", "Runway Guide", "Deploy runway."));
        config.put("exclude", List.of("/Privacy")); // leading slash and case tolerated
        applySettings();
        List<SearchHit> hits = search("runway", 20);
        assertEquals(1, hits.size(), "the excluded item is dropped from the index");
        assertEquals("Runway Guide", hits.get(0).title());
        assertEquals(1, engine.documentCount("acme"));
    }

    @Test
    void theExcludeListMatchesASourceItemsOwnId() {
        CatalogEntry blogEntry = entry("blog", "hello-world", "Runway Hello", "About runway.");
        corpus(page("guide", "Runway Guide", "Deploy runway."), blogEntry);
        config.put("exclude", "hello-world"); // a comma/newline string works as well as a list
        applySettings();
        List<SearchHit> hits = search("runway", 20);
        assertEquals(1, hits.size(), "the excluded blog entry is dropped");
        assertEquals("Runway Guide", hits.get(0).title());
    }

    @Test
    void nonMatchesReturnNothing() {
        corpus(page("live", "Contact", "Reach us."));
        assertTrue(search("runway", 20).isEmpty(), "'contact' has no 'runway'");
    }

    @Test
    void theResultLimitIsHonored() {
        corpus(page("a", "Runway A", ""), page("b", "Runway B", ""), page("c", "Runway C", ""));
        assertEquals(2, search("runway", 2).size());
    }

    @Test
    void aBlankQueryFindsNothing() {
        corpus(page("a", "Runway", ""));
        assertTrue(search("   ", 20).isEmpty());
    }

    @Test
    void anUnknownSiteHasAnEmptyIndex() {
        catalog.entry("other", page("a", "Runway", ""));
        assertTrue(engine.search(new SearchQuery("other", "runway", Locale.ENGLISH, 20)).isEmpty(),
                "a site Sites does not know has nothing to search");
    }

    @Test
    void eachHitCarriesTheWholeWordsItsTermsMatched() {
        corpus(page("mba", "Maestrías", "La maestría ejecutiva"), page("other", "Contacto", "Escríbenos"));
        List<SearchHit> hits = search("maestr", 20);
        assertEquals(1, hits.size());
        assertEquals(List.of("maestrías", "maestría"), hits.get(0).terms(),
                "the words around the match, title first, so the target page can highlight them");
    }

    @Test
    void theSnippetHonoursLengthAndWrapsHitsInMark() {
        String longDescription = "Alpha ".repeat(30) + "runway tail " + "omega ".repeat(30);
        corpus(page("p", "Page", longDescription));
        config.put("snippetLength", 60);
        applySettings();
        List<SearchHit> hits = search("runway", 20);
        assertEquals(1, hits.size());
        String snippet = hits.get(0).snippet();
        assertTrue(snippet.contains("<mark>runway</mark>"), "the matched term is wrapped in <mark>");
        assertTrue(snippet.length() < longDescription.length(), "the snippet is a bounded window, not the whole text");
        assertTrue(snippet.contains("…"), "a windowed snippet shows an ellipsis");
    }

    @Test
    void reindexRebuildsFromCurrentContentAndRefreshesTheListing() {
        corpus(page("a", "Runway", ""));
        assertEquals(1, engine.documentCount("acme"));
        corpus(page("a", "Runway", ""), page("b", "Runway Two", ""));
        assertEquals(1, engine.documentCount("acme"), "the cached index still serves until something drops it");
        assertEquals(2, engine.reindex("acme"), "reindex rebuilds from the now-larger corpus");
        assertEquals(List.of("acme"), catalog.refreshed(),
                "reindex refreshes the kernel's listing first: it exists for changes no event announced");
    }

    @Test
    void aPageChangeDropsTheSiteIndexImmediately() {
        corpus(page("a", "Runway", ""));
        assertEquals(1, search("runway", 20).size());
        corpus(page("a", "Runway", ""), page("b", "Runway Two", ""));
        assertEquals(1, search("runway", 20).size(), "before any event the cached index still serves");
        events.page("acme", "b", List.of(), List.of());
        assertEquals(2, search("runway", 20).size(), "the change drops the index; the next query rebuilds");
    }

    @Test
    void aContentChangeDropsTheSiteIndexImmediately() {
        corpus(entry("blog", "hello-world", "Runway Hello", ""));
        assertEquals(1, search("runway", 20).size());
        corpus(entry("blog", "hello-world", "Runway Hello", ""), entry("blog", "second", "Runway Second", ""));
        events.catalog(new CatalogChange("acme", CatalogChange.Cause.CONTENT, "blog", "second", "blog",
                List.of(), List.of(), true));
        assertEquals(2, search("runway", 20).size(), "a content source's change drops the index too");
    }

    @Test
    void anyPluginsSettingsSaveDropsTheSiteIndex() {
        // A content source reshapes its corpus from its own settings (disable the blog, change its URL prefix);
        // the engine must drop its index on any plugin's settings save, not only its own.
        corpus(entry("blog", "hello-world", "Runway Hello", ""));
        assertEquals(1, search("runway", 20).size());
        corpus();
        events.settings("acme", "blog");
        assertTrue(search("runway", 20).isEmpty(), "the blog's settings change dropped the index");
    }

    @Test
    void theExcludeListTakesEffectOnTheSettingsSave() {
        corpus(page("privacy", "Runway Privacy", ""), page("guide", "Runway Guide", ""));
        assertEquals(2, search("runway", 20).size());
        config.put("exclude", List.of("privacy"));
        applySettings();
        events.settings("acme", "search");
        assertEquals(1, search("runway", 20).size(), "this plugin's own save drops the index; exclusion applies");
    }

    @Test
    void aSiteLifecycleChangeDropsTheSiteIndex() {
        corpus(page("a", "Runway", ""));
        assertEquals(1, search("runway", 20).size());
        corpus(page("a", "Runway", ""), page("b", "Runway Two", ""));
        events.lifecycle("acme", SiteLifecycleEvent.Kind.RESTORED, true);
        assertEquals(2, search("runway", 20).size(), "a backup restore replaced content — the index rebuilt");
        assertTrue(events.failures().isEmpty(), "no callback failed: " + events.failures());
    }

    @Test
    void anIndexBuiltWhileAChangeArrivedIsServedButNotKept() {
        // A query builds the index; while it reads the corpus, a change is delivered (another thread in production).
        // The build may have read the old state, so it must not be cached: the next query rebuilds.
        SearchCorpus base = new SiteSearchCorpus(catalog, settings);
        int[] builds = {0};
        SimpleSearchEngine[] self = new SimpleSearchEngine[1];
        SearchCorpus racing = new SearchCorpus() {
            @Override
            public List<CatalogEntry> corpus(String siteId) {
                builds[0]++;
                List<CatalogEntry> read = base.corpus(siteId);
                if (builds[0] == 1) {
                    self[0].invalidate(siteId); // the change lands mid-build
                }
                return read;
            }

            @Override
            public String indexableText(String siteId, CatalogEntry entry, Locale locale) {
                return base.indexableText(siteId, entry, locale);
            }

            @Override
            public void refresh(String siteId) {
                base.refresh(siteId);
            }
        };
        self[0] = new SimpleSearchEngine(racing, new FakeSiteServices().site(ACME), settings, new SearchProperties());
        corpus(page("a", "Runway", ""));
        assertEquals(1, self[0].documentCount("acme"), "the raced build still answers its own caller");
        assertEquals(1, self[0].documentCount("acme"));
        assertEquals(1, self[0].documentCount("acme"));
        assertEquals(2, builds[0], "the raced build was not cached; the next one was, and is then served from cache");
    }

    @Test
    void theIndexCacheIsBounded() {
        FakeSiteServices kernel = new FakeSiteServices();
        int[] reads = {0};
        SearchCorpus counting = new SearchCorpus() {
            @Override
            public List<CatalogEntry> corpus(String siteId) {
                reads[0]++;
                return List.of();
            }

            @Override
            public String indexableText(String siteId, CatalogEntry entry, Locale locale) {
                return "";
            }

            @Override
            public void refresh(String siteId) {
            }
        };
        for (int i = 0; i <= SimpleSearchEngine.MAX_SITE_INDEXES; i++) {
            kernel.site(new SiteView("s" + i, "S", Locale.ENGLISH, List.of(Locale.ENGLISH), null));
        }
        SimpleSearchEngine bounded = new SimpleSearchEngine(counting, kernel, settings, new SearchProperties());
        for (int i = 0; i <= SimpleSearchEngine.MAX_SITE_INDEXES; i++) {
            bounded.documentCount("s" + i);
        }
        int afterFill = reads[0];
        bounded.documentCount("s" + SimpleSearchEngine.MAX_SITE_INDEXES);
        assertEquals(afterFill, reads[0], "the most recent site is cached");
        bounded.documentCount("s0");
        assertEquals(afterFill + 1, reads[0], "the least recently used site was dropped past the bound");
    }
}
