package mx.quest.search;

import static mx.quest.search.Fixtures.entry;
import static mx.quest.search.Fixtures.page;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.testkit.FakeContentCatalog;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.search.spi.SearchCorpus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The published {@link SearchCorpus}: the kernel's listing minus the site's {@code exclude} list, matched on the
 * item's own id (case and a leading slash ignored), read fresh on every call; indexable text withheld for an excluded
 * entry; {@code refresh} reaching the kernel's listing; and the input rules of the service tier.
 */
class SiteSearchCorpusTest {

    private FakeContentCatalog catalog;
    private FakePluginSettings settings;
    private SearchCorpus corpus;
    private final CatalogEntry guide = page("guide", "Guide", "");
    private final CatalogEntry privacy = page("legal/privacy", "Privacy", "");
    private final CatalogEntry post = entry("blog", "Hello-World", "Hello", "");

    @BeforeEach
    void setUp() {
        catalog = new FakeContentCatalog().entry("acme", guide).entry("acme", privacy).entry("acme", post)
                .text("acme", guide.compositeId(), "guide body").text("acme", privacy.compositeId(), "secret body");
        settings = new FakePluginSettings();
        corpus = new SiteSearchCorpus(catalog, settings);
    }

    @Test
    void withNoExclusionsTheCorpusIsTheListingInItsOrder() {
        assertEquals(List.of(guide, privacy, post), corpus.corpus("acme"));
    }

    @Test
    void anExcludedIdIsLeftOutIgnoringCaseAndALeadingSlash() {
        settings.site("acme", Map.of("exclude", List.of(" /Legal/Privacy ", "hello-world")));
        assertEquals(List.of(guide), corpus.corpus("acme"),
                "a page by its slug and a source item by its own id; never the composite form");
    }

    // --- the results page (2.1.1) -----------------------------------------------------------------------------

    private static CatalogEntry localizedPage(String slug, String enPath, String esPath) {
        ContentRef ref = new ContentRef(slug, enPath, Map.of(), List.of(Locale.ENGLISH, Locale.forLanguageTag("es")),
                Map.of("en", "Search", "es", "Buscar"), Map.of("en", "", "es", ""), "", "tpl", true, Instant.EPOCH,
                false, null, null, Map.of());
        return new CatalogEntry("pages", ref, List.of(new LocalizedUrl(Locale.ENGLISH, enPath, null),
                new LocalizedUrl(Locale.forLanguageTag("es"), esPath, null)), enPath);
    }

    @Test
    void theSitesResultsPageIsNeverInItsOwnResults() {
        CatalogEntry results = page("search", "Search", "Search our programs: maestrías, MBA");
        catalog.entry("acme", results);
        assertEquals(List.of(guide, privacy, post), corpus.corpus("acme"), "the default resultsPath is /search");
        assertEquals("", corpus.indexableText("acme", results, Locale.ENGLISH), "and none of its text is offered");
    }

    @Test
    void theResultsPageFollowsTheSettingInAnyCaseAndWithATrailingSlash() {
        CatalogEntry results = page("search-results", "Results", "");
        CatalogEntry search = page("search", "Search", "");
        catalog.entry("acme", results).entry("acme", search);
        settings.site("acme", Map.of("resultsPath", " /Search-Results/ "));
        assertEquals(List.of(guide, privacy, post, search), corpus.corpus("acme"),
                "the configured page leaves; a page that merely sits at /search stays");
    }

    @Test
    void aResultsPageLeavesWithAllItsLanguages() {
        CatalogEntry results = localizedPage("search", "/search", "/es/buscar");
        catalog.entry("acme", results);
        settings.site("acme", Map.of("resultsPath", "/es/buscar"));
        assertEquals(List.of(guide, privacy, post), corpus.corpus("acme"),
                "one of its addresses is the results path, so the page leaves in every language");
    }

    @Test
    void aRootResultsPathNeverExcludesTheHomePageAndOnlyPagesCanBeTheResultsPage() {
        CatalogEntry home = localizedPage("home", "/", "/es");
        CatalogEntry item = entry("blog", "search", "A post called search", "");
        catalog.entry("acme", home).entry("acme", item);
        settings.site("acme", Map.of("resultsPath", "/"));
        assertEquals(5, corpus.corpus("acme").size(), "resultsPath / leaves the home page in");
        settings.site("acme", Map.of());
        assertTrue(corpus.corpus("acme").contains(item), "a content-source item at /search is not the results page");
    }

    @Test
    void theCompositeFormDoesNotMatch() {
        settings.site("acme", Map.of("exclude", List.of("pages:guide")));
        assertEquals(3, corpus.corpus("acme").size(), "only the item's own id is matched");
    }

    @Test
    void aCommaOrNewlineSeparatedStringWorksToo() {
        settings.site("acme", Map.of("exclude", "guide,\nlegal/privacy"));
        assertEquals(List.of(post), corpus.corpus("acme"));
    }

    @Test
    void theSettingIsReadOnEveryCall() {
        assertEquals(3, corpus.corpus("acme").size());
        settings.site("acme", Map.of("exclude", List.of("guide")));
        assertEquals(2, corpus.corpus("acme").size(), "nothing is cached on the plugin side");
    }

    @Test
    void indexableTextIsWithheldForAnExcludedEntry() {
        assertEquals("guide body", corpus.indexableText("acme", guide, Locale.ENGLISH));
        settings.site("acme", Map.of("exclude", List.of("legal/privacy")));
        assertEquals("", corpus.indexableText("acme", privacy, Locale.ENGLISH),
                "a consumer holding a stale entry cannot read an excluded item's text");
        assertEquals("guide body", corpus.indexableText("acme", guide, Locale.ENGLISH));
    }

    @Test
    void refreshDropsTheKernelsListing() {
        corpus.refresh("acme");
        assertEquals(List.of("acme"), catalog.refreshed());
    }

    @Test
    void anUnknownSiteIsEmptyAndAnUnsafeIdIsRefused() {
        assertTrue(corpus.corpus("nobody").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> corpus.corpus("../acme"));
        assertThrows(IllegalArgumentException.class, () -> corpus.refresh("a/b"));
        assertThrows(NullPointerException.class, () -> corpus.indexableText("acme", null, Locale.ENGLISH));
    }

    @Test
    void theCorpusIsUnmodifiable() {
        settings.site("acme", Map.of("exclude", List.of("guide")));
        List<CatalogEntry> list = corpus.corpus("acme");
        assertThrows(UnsupportedOperationException.class, () -> list.add(guide));
    }
}
