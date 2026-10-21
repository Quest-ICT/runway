package mx.quest.searchlucene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.SiteView;
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
 * One index per language (3.2.0): each item is indexed in every language it exists in, with that language's
 * analyzer, and a query searches its own language's index — plus the primary-locale document of an item missing
 * from that language. The corpus answers each language's own body, as the kernel does since 1.0.0-rc6 (a page's
 * copy comes from the site's bundles in the requested language).
 */
class LucenePerLanguageTest {

    private static final String SITE = "acme";
    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");
    private static final Locale FR = Locale.FRENCH;

    private FakeContentCatalog catalog;
    private FakePluginSettings searchSettings;
    private FakeSiteServices sites;
    private LuceneStorageProps storage;
    private LuceneSearchEngine engine;
    private Map<String, Map<String, String>> bodies = Map.of();

    @BeforeEach
    void setUp() {
        catalog = new FakeContentCatalog().site(SITE);
        searchSettings = new FakePluginSettings();
        sites = new FakeSiteServices().site(new SiteView(SITE, "Acme", EN, List.of(EN, ES, FR), null));
        storage = new LuceneStorageProps();
        newEngine();
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    private void newEngine() {
        if (engine != null) {
            engine.close();
        }
        SearchCorpus base = new SiteSearchCorpus(catalog, searchSettings);
        SearchCorpus perLanguage = new SearchCorpus() {
            @Override
            public List<CatalogEntry> corpus(String siteId) {
                return base.corpus(siteId);
            }

            @Override
            public String indexableText(String siteId, CatalogEntry entry, Locale locale) {
                return bodies.getOrDefault(entry.ref().id(), Map.of()).getOrDefault(locale.getLanguage(), "");
            }

            @Override
            public void refresh(String siteId) {
                base.refresh(siteId);
            }
        };
        engine = new LuceneSearchEngine(perLanguage, sites, new FakePluginSettings(), storage);
    }

    private static CatalogEntry about() {
        ContentRef ref = new ContentRef("about", "/about", Map.of("es", "/acerca", "fr", "/a-propos"),
                List.of(EN, ES, FR), Map.of("en", "About", "es", "Acerca", "fr", "À propos"), Map.of(), "", "tpl",
                true, Instant.EPOCH, false, null, null, Map.of());
        return new CatalogEntry("pages", ref, List.of(new LocalizedUrl(EN, "/about", null),
                new LocalizedUrl(ES, "/es/acerca", null), new LocalizedUrl(FR, "/fr/a-propos", null)), "/about");
    }

    private static CatalogEntry englishOnly() {
        ContentRef ref = new ContentRef("news", "/news", Map.of(), List.of(EN), Map.of("en", "News"), Map.of(), "",
                "tpl", true, Instant.EPOCH, false, null, null, Map.of());
        return new CatalogEntry("pages", ref, List.of(new LocalizedUrl(EN, "/news", null)), "/news");
    }

    private void corpus(CatalogEntry... entries) {
        catalog.clear(SITE);
        for (CatalogEntry e : entries) {
            catalog.entry(SITE, e);
        }
    }

    private List<SearchHit> search(String q, Locale locale) {
        return engine.search(new SearchQuery(SITE, q, locale, 20));
    }

    private void trilingualAbout() {
        bodies = Map.of("about", Map.of(
                "en", "<p>A roastery that stays up late, roasting every night.</p>",
                "es", "<p>Una tostaduría que trasnocha. Nuestros alumnos aprenden con una facultad de excelencia.</p>",
                "fr", "<p>Une torréfaction nocturne.</p>"));
        corpus(about());
    }

    @Test
    void eachLanguageSearchesItsOwnIndexAtItsOwnAddress() {
        trilingualAbout();
        List<SearchHit> es = search("trasnocha", ES);
        assertEquals(1, es.size(), "the Spanish text is searchable from a Spanish results page");
        assertEquals("/es/acerca", es.get(0).url());
        assertEquals("Acerca", es.get(0).title());
        assertTrue(es.get(0).snippet().contains("<mark>"), es.get(0).snippet());

        assertTrue(search("trasnocha", EN).isEmpty(), "English searches the English index only");
        assertTrue(search("roastery", ES).isEmpty(), "Spanish searches the Spanish index only");
        assertEquals("/about", search("roastery", EN).get(0).url());
    }

    @Test
    void eachIndexUsesItsLanguagesAnalyzer() {
        trilingualAbout();
        // French light stemming folds the accent: an unaccented query finds the accented word.
        List<SearchHit> fr = search("torrefaction", FR);
        assertEquals(1, fr.size(), "the French analyzer matches 'torrefaction' to 'torréfaction'");
        assertEquals("/fr/a-propos", fr.get(0).url());
        // Spanish stemming: an inflected form finds the page.
        assertEquals(1, search("facultades", ES).size(), "the Spanish analyzer stems 'facultades' to 'facultad'");
        assertEquals(1, search("roasted", EN).size(), "the English analyzer stems 'roasted' to 'roast'");
    }

    @Test
    void theMatchedFormsAreTheTargetLanguagesWords() {
        trilingualAbout();
        List<SearchHit> hits = search("facultades", ES);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).terms().contains("facultad"), () -> "terms: " + hits.get(0).terms());
    }

    @Test
    void anItemMissingFromTheQueryLanguageIsFoundInItsPrimaryLanguage() {
        bodies = Map.of("about", Map.of("es", "<p>tostaduría</p>"), "news", Map.of("en", "<p>harvest report</p>"));
        corpus(about(), englishOnly());
        List<SearchHit> hits = search("harvest", ES);
        assertEquals(1, hits.size(), "a page the site publishes in English only is still found from Spanish");
        assertEquals("/news", hits.get(0).url());
        assertEquals(1, search("tostaduría", ES).size());
    }

    @Test
    void aLanguageTheSiteDoesNotSpeakSearchesTheDefaultLanguage() {
        trilingualAbout();
        List<SearchHit> hits = search("roastery", Locale.GERMAN);
        assertEquals(1, hits.size());
        assertEquals("/about", hits.get(0).url());
    }

    @Test
    void theConsoleCountsItemsNotLanguages() {
        trilingualAbout();
        corpus(about(), englishOnly());
        assertEquals(2, engine.indexedDocuments(SITE));
        assertEquals(2, engine.reindex(SITE));
    }

    @Test
    void onDiskEachLanguageHasItsOwnFolderAndTheOldSingleIndexIsRemoved(@TempDir Path tmp) throws IOException {
        Path siteDir = tmp.resolve(SITE);
        Files.createDirectories(siteDir);
        Files.writeString(siteDir.resolve("segments_1"), "a 3.1.x index file"); // the old one-index-per-site layout
        Files.writeString(siteDir.resolve("_0.cfs"), "a 3.1.x codec file");
        Files.writeString(siteDir.resolve("notes.txt"), "not Lucene's"); // indexRoot pointed at the wrong folder
        storage.setStorage("disk");
        storage.setIndexRoot(tmp.toString());
        newEngine();
        trilingualAbout();

        assertEquals("/es/acerca", search("trasnocha", ES).get(0).url());
        for (String language : List.of("en", "es", "fr")) {
            assertTrue(Files.isDirectory(siteDir.resolve(language)), "a folder for " + language);
        }
        assertFalse(Files.exists(siteDir.resolve("segments_1")), "the 3.1.x index file is deleted");
        assertFalse(Files.exists(siteDir.resolve("_0.cfs")), "…and its codec files");
        assertTrue(Files.exists(siteDir.resolve("notes.txt")), "a file Lucene did not name is never deleted");
    }

    @Test
    void onlyLuceneFileNamesCountAsAnOldIndex() {
        for (String name : List.of("segments_1", "segments_a", "segments.gen", "write.lock", "_0.cfs", "_0.si",
                "_1_Lucene90_0.dvd")) {
            assertTrue(LuceneSearchEngine.isLuceneFile(name), name);
        }
        for (String name : List.of("site.yml", "notes.txt", "messages.properties", "index.html")) {
            assertFalse(LuceneSearchEngine.isLuceneFile(name), name);
        }
    }

    @Test
    void aSearchFromInsideTheSitesOwnIndexBuildAnswersNothingInsteadOfRecursing() {
        bodies = Map.of();
        corpus(about());
        engine.close();
        SearchCorpus base = new SiteSearchCorpus(catalog, searchSettings);
        LuceneSearchEngine[] self = new LuceneSearchEngine[1];
        java.util.concurrent.atomic.AtomicInteger inner = new java.util.concurrent.atomic.AtomicInteger(-1);
        SearchCorpus reentrant = new SearchCorpus() { // a page whose widget searches while it is being rendered
            @Override
            public List<CatalogEntry> corpus(String siteId) {
                return base.corpus(siteId);
            }

            @Override
            public String indexableText(String siteId, CatalogEntry entry, Locale locale) {
                inner.set(self[0].search(new SearchQuery(SITE, "roastery", locale, 5)).size());
                return "<p>roastery</p>";
            }

            @Override
            public void refresh(String siteId) {
                base.refresh(siteId);
            }
        };
        engine = new LuceneSearchEngine(reentrant, sites, new FakePluginSettings(), storage);
        self[0] = engine;
        assertEquals(1, search("roastery", EN).size(), "the outer search is answered");
        assertEquals(0, inner.get(), "the search from inside the build answered nothing (and did not recurse)");
    }
}
