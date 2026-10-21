package mx.quest.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The snippet builder — the security-critical class. It must honour the frozen {@link mx.quest.search.spi.SearchHit}
 * snippet contract: escape EVERY source character and leave {@code <mark>…</mark>} — and nothing else — as raw
 * markup. These tests prove the highlight works AND that adversarial page text cannot forge a tag (XSS-safe).
 */
class SearchSnippetTest {

    @Test
    void blankInputYieldsEmpty() {
        assertEquals("", SearchSnippet.build("", List.of("x"), 160));
        assertEquals("", SearchSnippet.build(null, List.of("x"), 160));
        assertEquals("", SearchSnippet.build("   ", List.of("x"), 160));
    }

    @Test
    void aHitIsWrappedInMarkAndTheGapIsPlainEscapedText() {
        String out = SearchSnippet.build("the quick brown fox", List.of("brown"), 160);
        assertTrue(out.contains("<mark>brown</mark>"), "the matched term is wrapped in <mark>");
        assertTrue(out.contains("quick"), "surrounding text is preserved");
        assertFalse(out.contains("<mark>quick</mark>"), "only the matched term is marked");
    }

    @Test
    void matchingIsCaseInsensitiveButPreservesSourceCase() {
        String out = SearchSnippet.build("The Runway Platform", List.of("runway"), 160);
        assertTrue(out.contains("<mark>Runway</mark>"),
                "the hit matches case-insensitively but the highlight preserves the source's casing");
    }

    @Test
    void noHitFallsBackToAnEscapedLeadingSliceWithNoMark() {
        String out = SearchSnippet.build("nothing relevant here", List.of("absent"), 160);
        assertFalse(out.contains("<mark>"), "no term present ⇒ no highlight");
        assertTrue(out.contains("nothing"), "…but the leading text is still shown");
    }

    @Test
    void theWindowIsBoundedByLength() {
        String text = "x ".repeat(200) + "needle " + "y ".repeat(200);
        String out = SearchSnippet.build(text, List.of("needle"), 50);
        assertTrue(out.contains("<mark>needle</mark>"), "the window is centred on the hit");
        // The raw source is ~1200 chars; the snippet is a ~50-char window plus ellipses/markup.
        assertTrue(out.length() < 120, "the snippet is a small window, not the whole document: " + out.length());
    }

    // --- XSS-safety proof: adversarial page text --------------------------------------------------

    @Test
    void adversarialSourceIsFullyEscapedAndCannotForgeATag() {
        // The page body is hostile: a script tag, quotes, and a FORGED <mark> the attacker planted, all around
        // the real query term. The engine's escape-then-mark must neutralize every one of these. The term leads
        // so the whole payload sits inside the snippet window (windowing is centred on the first hit).
        String hostile = "runway <script>alert('xss')</script> \"quoted\" <mark>forged</mark> tail";
        String out = SearchSnippet.build(hostile, List.of("runway"), 400);

        // 1. The engine's OWN highlight is present and raw (this is the only allowed raw markup).
        assertTrue(out.contains("<mark>runway</mark>"), "the real hit is highlighted");

        // 2. Every hostile construct from the SOURCE is escaped — no live tag survives.
        assertFalse(out.contains("<script>"), "the source <script> is escaped, never emitted raw");
        assertTrue(out.contains("&lt;script&gt;"), "…it appears as escaped text");
        assertFalse(out.contains("</script>"), "the closing script tag is escaped too");
        assertTrue(out.contains("&quot;") || out.contains("&#39;"), "quotes from the source are escaped");

        // 3. The attacker's FORGED <mark> is escaped — it must NOT become a real tag. Only the ENGINE's own
        //    <mark> around the query term is raw. So the only raw "<mark>" in the output is the highlighted hit.
        assertFalse(out.contains("<mark>forged</mark>"), "a <mark> planted in the source is escaped, not honoured");
        assertTrue(out.contains("&lt;mark&gt;forged&lt;/mark&gt;"), "the forged mark appears as escaped text");
        assertEquals(1, countOccurrences(out, "<mark>"), "exactly one RAW <mark> — the engine's own highlight");
        assertEquals(1, countOccurrences(out, "</mark>"), "and its matching raw close tag");
    }

    @Test
    void anAmpersandInTheSourceIsEscaped() {
        String out = SearchSnippet.build("Tom & Jerry meet the runway", List.of("runway"), 160);
        assertTrue(out.contains("Tom &amp; Jerry"), "a literal & becomes &amp; so it can't start an entity");
        assertFalse(out.contains("Tom & Jerry"), "no raw ampersand survives");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
