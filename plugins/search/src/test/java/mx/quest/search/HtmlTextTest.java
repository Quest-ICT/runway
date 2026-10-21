package mx.quest.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The best-effort HTML-to-text extractor that feeds the built-in engine's body index. */
class HtmlTextTest {

    @Test
    void nullAndBlankYieldEmpty() {
        assertEquals("", HtmlText.toText(null));
        assertEquals("", HtmlText.toText("   "));
    }

    @Test
    void tagsAreStrippedAndWhitespaceCollapsed() {
        assertEquals("Hello world", HtmlText.toText("<p>Hello   <b>world</b></p>\n"));
    }

    @Test
    void scriptAndStyleContentIsRemoved() {
        String html = "<style>.x{color:red}</style><p>visible</p><script>var a=1;</script>";
        String text = HtmlText.toText(html);
        assertEquals("visible", text);
        assertFalse(text.contains("color"), "CSS is not indexable prose");
        assertFalse(text.contains("var"), "JS is not indexable prose");
    }

    @Test
    void commentsAreRemoved() {
        assertEquals("after", HtmlText.toText("<!-- hidden note --> after"));
    }

    @Test
    void commonEntitiesAreDecoded() {
        assertEquals("a < b & c", HtmlText.toText("a &lt; b &amp; c"));
    }

    @Test
    void anEscapedEntityRoundTripsWithoutOverDecoding() {
        // "&amp;lt;" is the literal text "&lt;", not the character "<": &amp; decodes LAST so this is preserved.
        assertEquals("&lt;", HtmlText.toText("&amp;lt;"));
    }

    @Test
    void malformedHtmlStillYieldsSomeText() {
        // An unclosed tag must not throw — indexing may never fail on one bad page.
        String text = HtmlText.toText("<div><p>hello <b>world");
        assertTrue(text.contains("hello"), "best-effort extraction yields the visible words");
        assertTrue(text.contains("world"));
    }
}
