package mx.quest.search;

import static mx.quest.search.Fixtures.ACME;
import static mx.quest.search.Fixtures.engine;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.WidgetView;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import mx.quest.search.spi.SearchHit;
import mx.quest.search.spi.SiteSearchEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The query-aware {@code search} box widget: it renders the box, and when its page is opened with {@code ?q=…}
 * renders the results inline. Covers box rendering + form action, engine selection, the RAW snippet render, query
 * escaping, the site's own words, and the disabled/blank cases.
 */
class SearchBoxWidgetTest {

    private FakePluginSettings settings;
    private FakeSiteServices kernel;

    @BeforeEach
    void setUp() {
        settings = new FakePluginSettings().site("acme", Map.of("enabled", true));
        kernel = new FakeSiteServices().site(ACME);
    }

    private String render(Map<String, String> query, SiteSearchEngine... engines) {
        return render(false, Map.of(), query, engines);
    }

    private String render(boolean chrome, Map<String, Object> config, Map<String, String> query,
                          SiteSearchEngine... engines) {
        SearchBoxWidget widget = new SearchBoxWidget(List.of(engines), settings, kernel,
                new NoResultsLog(new Fixtures.NoFiles(), settings, Clock.systemUTC(), false));
        PageMeta page = new PageMeta("search", "Search", "", List.of(Locale.ENGLISH), Locale.ENGLISH, false, false);
        return widget.render(new WidgetView(ACME, page, Locale.ENGLISH, "search-box", chrome, config, query)).html();
    }

    private static SiteSearchEngine oneHit() {
        return engine("search/simple", 0, true,
                new SearchHit("/mba", "MBA", "the <mark>maestría</mark>", 3, List.of("maestría", "maestrías")));
    }

    @Test
    void aBoxSubmitsToTheResultsPageInItsPagesLanguage() {
        Locale es = Locale.of("es");
        mx.quest.runway.spi.testkit.FakeContentCatalog catalog = new mx.quest.runway.spi.testkit.FakeContentCatalog()
                .site("acme");
        mx.quest.runway.spi.model.ContentRef ref = new mx.quest.runway.spi.model.ContentRef("search", "/search",
                Map.of("es", "/buscar"), List.of(Locale.ENGLISH, es), Map.of("en", "Search"), Map.of(), "", "tpl",
                true, java.time.Instant.EPOCH, false, null, null, Map.of());
        catalog.entry("acme", new mx.quest.runway.spi.model.CatalogEntry("pages", ref, List.of(
                new mx.quest.runway.spi.model.LocalizedUrl(Locale.ENGLISH, "/search", null),
                new mx.quest.runway.spi.model.LocalizedUrl(es, "/es/buscar", null)), "/search"));
        SearchBoxWidget widget = new SearchBoxWidget(List.of(oneHit()), settings, kernel,
                new NoResultsLog(new Fixtures.NoFiles(), settings, Clock.systemUTC(), false),
                new ResultsPageLinks(catalog));
        PageMeta page = new PageMeta("about", "About", "", List.of(Locale.ENGLISH, es), Locale.ENGLISH, false, false);

        String spanish = widget.render(new WidgetView(ACME, page, es, "search-box", true, Map.of(), Map.of())).html();
        assertTrue(spanish.contains("action=\"/es/buscar\""), "a box on a Spanish page submits to /es/buscar: "
                + spanish);
        String english = widget.render(new WidgetView(ACME, page, Locale.ENGLISH, "search-box", true, Map.of(),
                Map.of())).html();
        assertTrue(english.contains("action=\"/search\""), english);
        String french = widget.render(new WidgetView(ACME, page, Locale.FRENCH, "search-box", true, Map.of(),
                Map.of())).html();
        assertTrue(french.contains("action=\"/search\""),
                "the results page has no French address: the setting as written: " + french);

        settings.site("acme", Map.of("enabled", true, "resultsPath", "/nowhere"));
        String unknown = widget.render(new WidgetView(ACME, page, es, "search-box", true, Map.of(), Map.of())).html();
        assertTrue(unknown.contains("action=\"/nowhere\""), "no page at the path: the setting as written: " + unknown);
    }

    @Test
    void aResultLinkCarriesTheMatchedWordsForTheTargetPage() {
        String html = render(Map.of("q", "maestría"), oneHit());
        assertTrue(html.contains("href=\"/mba#rw-hl=maestr%C3%ADa%20maestr%C3%ADas\""),
                "the link carries the matched words, percent-encoded, space as %20: " + html);
    }

    @Test
    void withHighlightOffTheLinkIsPlain() {
        settings.site("acme", Map.of("enabled", true, "highlight", false));
        String html = render(Map.of("q", "maestría"), oneHit());
        assertTrue(html.contains("href=\"/mba\""), html);
        assertFalse(html.contains("rw-hl"), "no marker anywhere when the site turned highlighting off");
    }

    @Test
    void aBoxInTheMasterChromeShowsOnlyTheBoxByDefault() {
        String html = render(true, Map.of(), Map.of("q", "maestría"), oneHit());
        assertTrue(html.contains("name=\"q\""), "the box itself is shown");
        assertFalse(html.contains("rw-search-hits"), "a header/footer box does not repeat the results page's list");
        assertFalse(html.contains("rw-search-empty"), "…nor a 'no results' line");
    }

    @Test
    void thePlacementSettingOverridesWhereResultsAreListed() {
        assertTrue(render(true, Map.of("results", "always"), Map.of("q", "x"), oneHit()).contains("rw-search-hits"),
                "always: a chrome box lists the results");
        assertFalse(render(false, Map.of("results", "never"), Map.of("q", "x"), oneHit()).contains("rw-search-hits"),
                "never: a content box shows only the box");
        assertTrue(render(false, Map.of("results", "auto"), Map.of("q", "x"), oneHit()).contains("rw-search-hits"),
                "auto: a content box lists them");
        assertTrue(render(false, Map.of("results", "bogus"), Map.of("q", "x"), oneHit()).contains("rw-search-hits"),
                "an unknown value behaves as auto");
    }

    @Test
    void aBareRenderShowsTheBoxButNoResults() {
        String html = render(Map.of(), engine("search/simple", 0, true));
        assertTrue(html.contains("name=\"q\""), "the search box is shown");
        assertTrue(html.contains("action=\"/search\""), "the box submits to the results page (default /search)");
        assertTrue(html.contains("placeholder=\"Search…\""), "the default placeholder");
        assertFalse(html.contains("rw-search-hits"), "no results list without a query");
    }

    @Test
    void resultsRenderInlineWhenTheQueryIsPresent() {
        String html = render(Map.of("q", "runway"),
                engine("search/simple", 0, true, new SearchHit("/guide", "Runway Guide", "How to deploy.", 3)));
        assertTrue(html.contains("Runway Guide"), "the hit title is rendered");
        assertTrue(html.contains("href=\"/guide\""), "the hit links to the page");
        assertTrue(html.contains("/__plugin/search/static/search.css"), "the widget references the plugin CSS");
        assertTrue(html.contains("value=\"runway\""), "the box keeps the current query");
        assertTrue(html.contains("1 result(s) for"), "the count line");
    }

    @Test
    void theSnippetIsRenderedAsRawHtmlSoTheHighlightShows() {
        String snippet = "deploy <mark>runway</mark> now &amp; later";
        String html = render(Map.of("q", "runway"),
                engine("search/simple", 0, true, new SearchHit("/g", "Guide", snippet, 3)));
        assertTrue(html.contains("<mark>runway</mark>"), "the engine's <mark> is emitted raw (highlight shows)");
        assertFalse(html.contains("&lt;mark&gt;"), "the snippet is NOT double-escaped");
        assertTrue(html.contains("&amp; later"), "…while the engine's own escaping is preserved verbatim");
    }

    @Test
    void aCraftedQueryIsEscaped() {
        String html = render(Map.of("q", "<script>x</script>"), engine("search/simple", 0, true));
        assertFalse(html.contains("<script>x</script>"), "the query is HTML-escaped, never reflected as markup");
        assertTrue(html.contains("&lt;script&gt;"), "…it appears escaped");
    }

    @Test
    void theHighestPriorityAvailableEngineServesTheResults() {
        String html = render(Map.of("q", "x"),
                engine("search/simple", 0, true, new SearchHit("/a", "FromSimple", "", 1)),
                engine("search/lucene", 100, true, new SearchHit("/b", "FromLucene", "", 1)),
                engine("search/broken", 200, false, new SearchHit("/c", "FromBroken", "", 1)));
        assertTrue(html.contains("FromLucene"), "the highest-priority AVAILABLE engine serves");
        assertFalse(html.contains("FromBroken"), "an unavailable higher-priority engine is skipped");
    }

    @Test
    void theResultsPathAndPlaceholderSettingsShapeTheBox() {
        settings.site("acme", Map.of("enabled", true, "resultsPath", " /find ", "placeholder", "Look up"));
        String html = render(Map.of(), engine("search/simple", 0, true));
        assertTrue(html.contains("action=\"/find\""), "the box submits to the configured results page");
        assertTrue(html.contains("placeholder=\"Look up\""), "the configured placeholder");
    }

    @Test
    void theSitesOwnWordsAreUsed() {
        kernel.message("acme", "search.box.submit", "Buscar").message("acme", "search.results.none", "Nada para");
        String html = render(Map.of("q", "zzz"), engine("search/simple", 0, true));
        assertTrue(html.contains(">Buscar</button>"), "the submit label comes from Messages.site");
        assertTrue(html.contains("Nada para “zzz”."), "the empty-result line too");
    }

    @Test
    void aDisabledSearchRendersNothing() {
        settings.site("acme", Map.of("enabled", false));
        assertTrue(render(Map.of("q", "runway"), engine("search/simple", 0, true)).isEmpty(),
                "a disabled search widget renders an empty fragment");
    }
}
