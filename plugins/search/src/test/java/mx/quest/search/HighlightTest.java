package mx.quest.search;

import static mx.quest.search.Fixtures.ACME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.search.spi.SearchHit;
import org.junit.jupiter.api.Test;

/**
 * The result highlighter (2.1.0): the matched words a hit carries, the link marker that hands them to the target
 * page, the head loader that brings the page script, and the agreement between the Java side and that script.
 */
class HighlightTest {

    // --- SearchHit.terms -----------------------------------------------------------------------------

    @Test
    void theFourArgumentHitHasNoTerms() {
        assertEquals(List.of(), new SearchHit("/a", "A", "", 1).terms(), "an engine built before 2.1.0 still links");
        assertEquals(List.of(), new SearchHit("/a", "A", "", 1, null).terms());
    }

    @Test
    void termsAreLowercasedDistinctTrimmedAndBounded() {
        String tooLong = "x".repeat(SearchHit.MAX_TERM_LENGTH + 1);
        SearchHit hit = new SearchHit("/a", "A", "", 1,
                java.util.Arrays.asList(" Maestría ", "maestría", "", null, tooLong, "MBA"));
        assertEquals(List.of("maestría", "mba"), hit.terms());

        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add("w" + i);
        }
        assertEquals(SearchHit.MAX_TERMS, new SearchHit("/a", "A", "", 1, many).terms().size());
    }

    // --- MatchedWords (the built-in engine) ------------------------------------------------------------

    @Test
    void theBuiltInEngineReportsTheWholeWordsAroundEachMatchTitleFirst() {
        SearchDocument doc = SearchDocument.of("mba", "/mba", "Maestrías ejecutivas", "",
                "La maestría (MBA) dura dos años; maestrías.");
        assertEquals(List.of("maestrías", "maestría"), MatchedWords.in(doc, List.of("maestr")));
        assertEquals(List.of("mba"), MatchedWords.in(doc, List.of("mba")), "punctuation ends a word");
        assertEquals(List.of(), MatchedWords.in(doc, List.of("zzz")));
    }

    @Test
    void theBuiltInEngineStopsAtTheCap() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            body.append("run").append(i).append(' ');
        }
        SearchDocument doc = SearchDocument.of("x", "/x", "", "", body.toString());
        assertEquals(SearchHit.MAX_TERMS, MatchedWords.in(doc, List.of("run")).size());
    }

    // --- HighlightMarker ------------------------------------------------------------------------------

    @Test
    void theMarkerPercentEncodesTheWordsAsOneComponent() {
        SearchHit hit = new SearchHit("/es/mba", "MBA", "", 1, List.of("maestría", "a&b", "c#d"));
        assertEquals("/es/mba#rw-hl=maestr%C3%ADa%20a%26b%20c%23d", HighlightMarker.link(hit));
    }

    @Test
    void aHitWithoutWordsOrWithItsOwnFragmentLinksPlainly() {
        assertEquals("/a", HighlightMarker.link(new SearchHit("/a", "A", "", 1)));
        assertEquals("/a#top", HighlightMarker.link(new SearchHit("/a#top", "A", "", 1, List.of("x"))));
    }

    // --- SearchHighlightHead --------------------------------------------------------------------------

    private static String head(FakePluginSettings settings, boolean preview) {
        PageMeta page = new PageMeta("home", "Home", "", List.of(Locale.ENGLISH), Locale.ENGLISH, false, false);
        HeadContext ctx = new HeadContext(ACME, page, Locale.ENGLISH, "", "", List.of(), preview, false, null);
        return new SearchHighlightHead(settings).render(ctx);
    }

    @Test
    void theLoaderIsAddedOnlyWhereSearchAndHighlightingAreOn() {
        assertEquals(SearchHighlightHead.LOADER, head(new FakePluginSettings().site("acme", Map.of()), false),
                "both default on");
        assertEquals("", head(new FakePluginSettings().site("acme", Map.of("highlight", false)), false));
        assertEquals("", head(new FakePluginSettings().site("acme", Map.of("enabled", false)), false));
        assertEquals("", head(new FakePluginSettings().site("acme", Map.of()), true), "nothing in an editor preview");
    }

    @Test
    void theLoaderIsOneSameOriginScriptThatLoadsTheHighlighterOnlyOnTheMarker() throws IOException {
        assertEquals("<script src=\"/__plugin/search/static/highlight-loader.js?v=" + SearchConfig.VERSION
                + "\" defer></script>",
                SearchHighlightHead.LOADER, "external and same-origin: the kernel never nonces an inline script");
        String loader = Files.readString(Path.of("static/highlight-loader.js"), StandardCharsets.UTF_8);
        assertTrue(loader.contains("indexOf('" + HighlightMarker.KEY + "=')<0)return"),
                "it looks for the same key the links carry, and stops when it is absent");
        assertTrue(loader.contains("'/__plugin/search/static/highlight.js?v=" + SearchConfig.VERSION + "'"),
                "the loader asks for this release's script, so an upgrade is fetched at once");
        assertTrue(loader.length() < 600, "fetched with every page, so it stays tiny: " + loader.length());
    }

    // --- the JSON API ---------------------------------------------------------------------------------------

    @Test
    void theApiGivesEachResultItsWordsAndItsMarkedLink() {
        List<SearchHit> hits = List.of(new SearchHit("/mba", "MBA", "", 2, List.of("maestría")));
        String on = SearchEndpoint.toJson("maestria", hits, true);
        assertTrue(on.contains("\"link\":\"/mba#rw-hl=maestr%C3%ADa\""), on);
        assertTrue(on.contains("\"terms\":[\"maestría\"]"), on);
        assertTrue(on.contains("\"url\":\"/mba\""), "url stays the plain address");
        String off = SearchEndpoint.toJson("maestria", hits, false);
        assertTrue(off.contains("\"link\":\"/mba\""), off);
        assertTrue(SearchEndpoint.toJson("x", List.of(new SearchHit("/a", "A", "", 1)), true)
                .contains("\"terms\":[]"), "a hit without words has an empty list");
    }

    // --- the page script agrees with the Java side --------------------------------------------------------

    @Test
    void thePageScriptReadsTheSameKeyAndItsStylesheetShips() throws IOException {
        String js = Files.readString(Path.of("static/highlight.js"), StandardCharsets.UTF_8);
        assertTrue(js.contains("var KEY = '" + HighlightMarker.KEY + "=';"), "highlight.js reads the marker the links write");
        assertTrue(js.contains("var MAX_WORDS = " + SearchHit.MAX_TERMS + ";"), "and accepts as many words as a hit carries");
        assertTrue(js.contains("'/__plugin/search/static/highlight.css?v=" + SearchConfig.VERSION + "'"),
                "the script asks for this release's stylesheet");
        assertTrue(js.contains("history.replaceState"), "the marker is removed from the address");
        String css = Files.readString(Path.of("static/highlight.css"), StandardCharsets.UTF_8);
        assertTrue(css.contains("::highlight(rw-search)") && js.contains("var NAME = 'rw-search';"),
                "the highlight name in the script and in the stylesheet agree");
    }
}
