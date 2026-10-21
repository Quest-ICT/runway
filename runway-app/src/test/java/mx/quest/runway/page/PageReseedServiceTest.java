package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The re-seed structural merge: it replaces the chrome seams a page and its master share (head +
 * {@code #site-header}/{@code #site-footer}/{@code #site-scripts}) with the master's, while preserving the
 * author's {@code #content} <b>byte-for-byte</b> (position-tracked splice, no HTML5 re-normalization), and
 * leaves alone any seam the master does not expose.
 */
class PageReseedServiceTest {

    private static final String MASTER = "<!DOCTYPE html><html><head><title>MASTER</title></head>"
            + "<body><header id=\"site-header\">MASTER HEADER</header>"
            + "<main id=\"content\">MASTER CONTENT</main>"
            + "<footer id=\"site-footer\">MASTER FOOTER</footer></body></html>";

    @Test
    void replacesSharedSeamsAndPreservesContentByteForByte() {
        String content = "<main id=\"content\">\n  <h1>MY CONTENT</h1>\n  <p>keep me exactly &amp; verbatim</p>\n</main>";
        String page = "<!DOCTYPE html><html><head><title>OLD</title></head>"
                + "<body><header id=\"site-header\">OLD HEADER</header>"
                + content
                + "<footer id=\"site-footer\">OLD FOOTER</footer></body></html>";

        String merged = PageReseedService.merge(page, MASTER);

        assertTrue(merged.contains("MASTER HEADER"), "header re-seeded");
        assertTrue(merged.contains("MASTER FOOTER"), "footer re-seeded");
        assertTrue(merged.contains("<title>MASTER</title>"), "head re-seeded");
        assertFalse(merged.contains("OLD HEADER"), "old header gone");
        assertFalse(merged.contains("OLD FOOTER"), "old footer gone");
        // The author's content is untouched — exact bytes, whitespace and entities included.
        assertTrue(merged.contains(content), "content preserved byte-for-byte");
        assertFalse(merged.contains("MASTER CONTENT"), "the master's own content is never pulled in");
    }

    @Test
    void leavesAloneASeamTheMasterDoesNotExpose() {
        String master = "<!DOCTYPE html><html><head></head><body><main id=\"content\">m</main></body></html>";
        String page = "<!DOCTYPE html><html><head></head>"
                + "<body><header id=\"site-header\">KEEP THIS HEADER</header>"
                + "<main id=\"content\">c</main></body></html>";

        String merged = PageReseedService.merge(page, master);
        assertTrue(merged.contains("KEEP THIS HEADER"), "a seam the master lacks is untouched");
    }

    @Test
    void isANoOpWhenThereAreNoSharedSeams() {
        // A page with no seam anchors (and jsoup synthesizes head/body for both) — nothing tracked to splice.
        String page = "<main id=\"content\">only content</main>";
        String merged = PageReseedService.merge(page, "<div>irrelevant master</div>");
        assertTrue(merged.contains("only content"));
    }

    // --- hardening regressions ------------------------------------------------

    @Test
    void aSeamWithNoClosingTagDoesNotSwallowTheFollowingContent() {
        // finding #1 (CRITICAL): a missing </header> made jsoup report an implicit end at EOF, splicing away
        // everything after — including #content. The implicit-tag guard must leave such a page untouched.
        String page = "<!DOCTYPE html><html><head></head><body>"
                + "<header id=\"site-header\"><p>hi"          // <-- no </header>
                + "<main id=\"content\">PRECIOUS</main></body></html>";
        String merged = PageReseedService.merge(page, MASTER);
        assertTrue(merged.contains("PRECIOUS"), "the author's content survives an unclosed seam");
        assertTrue(merged.contains("id=\"content\""));
    }

    @Test
    void contentNestedInsideASeamIsNeverDestroyed() {
        // finding #4: a splice covers the seam's whole span, so #content nested in a seam must abort that seam.
        String page = "<!DOCTYPE html><html><head></head><body>"
                + "<header id=\"site-header\">chrome <main id=\"content\">MY PRECIOUS CONTENT</main></header>"
                + "</body></html>";
        String merged = PageReseedService.merge(page, MASTER);
        assertTrue(merged.contains("MY PRECIOUS CONTENT"), "content nested in a seam is preserved");
    }

    @Test
    void anEmptyMasterHeadDoesNotWipeThePageHead() {
        // finding #6: master.head() is never null; an empty/fragment master head must not blank the page head.
        String master = "<!DOCTYPE html><html><head></head><body><main id=\"content\">m</main></body></html>";
        String page = "<!DOCTYPE html><html><head><title>KEEP MY TITLE</title></head>"
                + "<body><main id=\"content\">c</main></body></html>";
        String merged = PageReseedService.merge(page, master);
        assertTrue(merged.contains("KEEP MY TITLE"), "an empty master head leaves the page head alone");
    }

    @Test
    void duplicateSeamIdsAreRefusedRatherThanHalfReseeded() {
        // finding #14: getElementById returns only the first — reseeding one and leaving the other is worse
        // than doing nothing; a duplicate seam id aborts that seam.
        String page = "<!DOCTYPE html><html><head></head><body>"
                + "<header id=\"site-header\">FIRST</header><header id=\"site-header\">SECOND</header>"
                + "<main id=\"content\">c</main></body></html>";
        String merged = PageReseedService.merge(page, MASTER);
        assertTrue(merged.contains("FIRST") && merged.contains("SECOND"), "an ambiguous duplicate id is left alone");
    }

    @Test
    void aPageWithNoLiteralHeadIsNotGivenOne() {
        // finding #15: an implicit (jsoup-synthesized) head must not be inserted — insertion is deferred.
        String page = "<header id=\"site-header\">H</header><main id=\"content\">c</main>";
        String merged = PageReseedService.merge(page, MASTER);
        assertFalse(merged.contains("<title>MASTER</title>"), "no head is inserted where the page has none");
        assertTrue(merged.contains("MASTER HEADER"), "the present header seam is still re-seeded");
    }
}
