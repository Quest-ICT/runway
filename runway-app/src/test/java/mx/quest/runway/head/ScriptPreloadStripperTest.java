package mx.quest.runway.head;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import mx.quest.runway.web.CspScriptAllowlist;
import org.junit.jupiter.api.Test;

/**
 * The preload half of "never loosen what the allowlist blocked": which {@code <link>} tags the edge removes
 * from a public page served under {@code 'strict-dynamic'} (a script-class preload — {@code modulepreload},
 * {@code prefetch}, {@code preload as=script}/worker/worklet/xslt — on a URL the host-allowlist policy refused),
 * which it keeps (allowed URLs, every other link type), the markup tricks it must see through (case, quotes,
 * whitespace, attribute order, character references, token lists, duplicate attributes), and the places it cannot
 * rewrite (raw-text contents, {@code srcdoc}), which it reports as hidden instead.
 */
class ScriptPreloadStripperTest {

    /** The shipped default plus the reCAPTCHA hosts the protection plugin contributes. */
    private static final CspScriptAllowlist ALLOWLIST =
            CspScriptAllowlist.parse("'self' https://www.google.com https://www.gstatic.com");

    private static final URI DOC = URI.create("http://site.test/blog/post");

    private static ScriptPreloadStripper.Result strip(String html) {
        return ScriptPreloadStripper.strip(html, ALLOWLIST, DOC);
    }

    /** Asserts that {@code link} is removed from a page around it, and that nothing else changes. */
    private static void assertStripped(String link) {
        String page = "<head><title>t</title>" + link + "<link rel=\"stylesheet\" href=\"/s.css\"></head><p>x</p>";
        ScriptPreloadStripper.Result result = strip(page);
        assertEquals("<head><title>t</title><link rel=\"stylesheet\" href=\"/s.css\"></head><p>x</p>", result.html(),
                link);
        assertFalse(result.hidden(), link);
    }

    /** Asserts that {@code link} stays exactly as written (the same instance comes back). */
    private static void assertKept(String link) {
        String page = "<head>" + link + "</head><p>x</p>";
        ScriptPreloadStripper.Result result = strip(page);
        assertSame(page, result.html(), link);
        assertFalse(result.hidden(), link);
    }

    // ---- the link types that are judged ------------------------------------------------------------------

    @Test
    void aForeignModulePreloadIsRemovedWhateverItsAs() {
        for (String as : new String[] {"", " as=\"script\"", " as=\"worker\"", " as=\"sharedworker\"",
                " as=\"serviceworker\"", " as=\"audioworklet\"", " as=\"paintworklet\"", " as=\"style\"",
                " as=\"json\"", " as=\"bogus\""}) {
            assertStripped("<link rel=\"modulepreload\"" + as + " href=\"https://evil.example/m.js\">");
        }
    }

    @Test
    void aForeignPrefetchIsRemovedWhateverItsAs() {
        // Measured in Chrome 155: fetched from any host under 'strict-dynamic', for every one of these.
        for (String as : new String[] {"", " as=\"script\"", " as=\"style\"", " as=\"image\"", " as=\"document\"",
                " crossorigin"}) {
            assertStripped("<link rel=\"prefetch\"" + as + " href=\"https://evil.example/p\">");
        }
    }

    @Test
    void aForeignPreloadWithAScriptLikeAsIsRemoved() {
        for (String as : new String[] {"script", "worker", "sharedworker", "serviceworker", "audioworklet",
                "paintworklet", "xslt", "SCRIPT", "Script", " script ", "&#115;cript", "scr&#x69;pt"}) {
            assertStripped("<link rel=\"preload\" as=\"" + as + "\" href=\"https://evil.example/c.js\">");
        }
    }

    @Test
    void aPreloadThatIsNotScriptClassIsKeptEvenWhenForeign() {
        // Destination "" (connect-src) or a resource with its own directive: not script-src, not this stripper's business.
        for (String as : new String[] {null, "", "fetch", "json", "style", "font", "image", "document", "track",
                "scripts", "script-x"}) {
            assertKept("<link rel=\"preload\"" + (as == null ? "" : " as=\"" + as + "\"")
                    + " href=\"https://evil.example/r\">");
        }
    }

    @Test
    void otherLinkTypesAreKeptEvenWhenForeign() {
        for (String rel : new String[] {"stylesheet", "icon", "manifest", "preconnect", "dns-prefetch", "prerender",
                "alternate", "canonical", "next", "compression-dictionary", "modulepreloads", "pre-fetch",
                "x-modulepreload"}) {
            assertKept("<link rel=\"" + rel + "\" href=\"https://evil.example/r\">");
        }
    }

    // ---- the URL rule: the same as a <script src> --------------------------------------------------------

    @Test
    void anAllowedPreloadIsKeptExactlyAsWritten() {
        for (String href : new String[] {"/static/m.js", "m.js", "../m.js", "http://site.test/m.js",
                "//site.test/m.js", "https://www.google.com/recaptcha/api.js", "https://www.gstatic.com/x/y.js",
                "/static/m.js?v=1#f", " /static/m.js "}) {
            for (String rel : new String[] {"modulepreload", "prefetch"}) {
                assertKept("<link rel=\"" + rel + "\" href=\"" + href + "\">");
            }
            assertKept("<link rel=\"preload\" as=\"script\" href=\"" + href + "\">");
        }
    }

    @Test
    void aForeignUrlIsRemovedHoweverItIsSpelled() {
        String[] foreign = {
            "https://evil.example/x.js",
            "//evil.example/x.js",                       // scheme-relative
            "///evil.example/x.js",                      // WHATWG skips the extra slashes: host evil.example
            "////evil.example/x.js",
            "\\\\evil.example/x.js",                     // a browser reads '\' as '/'
            "/\\evil.example/x.js",
            " \n//evil.example/x.js ",
            "https&#58;//evil.example/x.js",             // character references are decoded first
            "&#x2F;&#x2F;evil.example/x.js",
            "&sol;&sol;evil.example/x.js",
            "&#47&#47evil.example/x.js",
            "https://www.google.com@evil.example/x.js",  // user info: the host is evil.example
            "http://www.google.com.evil.example/x.js",
            "https://google.com/x.js",                   // the apex is not www
            "http://site.test:9999/x.js",                // same host, other port
            "ht\ttps://evil.example/x.js",
            "data:text/javascript,1",
            "javascript:1",
        };
        for (String href : foreign) {
            assertStripped("<link rel=\"modulepreload\" href=\"" + href + "\">");
        }
    }

    @Test
    void aSecureUpgradeOfTheOriginCountsAsSelfLikeForAScript() {
        // CSP3 'self' matches the https upgrade of an http origin — the same answer the stamp gives a <script src>.
        boolean scriptAllowed = ScriptNonceStamper.stamp("<script src=\"https://site.test/x.js\"></script>", "N",
                ALLOWLIST, DOC).contains("nonce=\"N\"");
        String link = "<link rel=\"modulepreload\" href=\"https://site.test/x.js\">";
        assertEquals(!scriptAllowed, !strip(link).html().equals(link), "a preload follows the script rule exactly");
    }

    @Test
    void theRuleIsTheStampsRuleForEveryUrl() {
        // Whatever the stamp decides for <script src=U>, the stripper decides the same for <link rel=modulepreload
        // href=U>: stamped ⇔ kept.
        String[] urls = {"/a.js", "a.js", "//site.test/a.js", "https://evil.example/a.js", "///evil.example/a.js",
            "https://www.google.com/a.js", "https://www.google.com:8443/a.js", "https:evil.example/a.js",
            "https://%65vil.example/a.js", "/%2e%2e/a.js", "http://user@site.test/a.js", "", "#frag", "?q"};
        for (String url : urls) {
            boolean stamped = ScriptNonceStamper.stamp("<script src=\"" + url + "\"></script>", "N", ALLOWLIST, DOC)
                    .contains("nonce=\"N\"");
            String link = "<link rel=\"modulepreload\" href=\"" + url + "\">";
            boolean kept = strip(link).html().equals(link);
            assertEquals(stamped, kept, url);
        }
    }

    @Test
    void theFirstSameOriginBaseIsFollowedAndACrossOriginOneRemovesEveryRelativePreload() {
        assertEquals("<base href=\"/sub/\"><link rel=\"modulepreload\" href=\"m.js\">",
                strip("<base href=\"/sub/\"><link rel=\"modulepreload\" href=\"m.js\">").html());
        assertEquals("<base href=\"https://evil.example/\">",
                strip("<base href=\"https://evil.example/\"><link rel=\"modulepreload\" href=\"m.js\">").html());
        // Only the first <base href> counts.
        assertEquals("<base href=\"https://evil.example/\"><base href=\"/\">",
                strip("<base href=\"https://evil.example/\"><base href=\"/\">"
                        + "<link rel=\"modulepreload\" href=\"m.js\">").html());
    }

    @Test
    void aLinkWithoutHrefFetchesNothingAndIsKept() {
        assertKept("<link rel=\"modulepreload\">");
        assertKept("<link rel=\"prefetch\" as=\"script\">");
        assertKept("<link rel=\"modulepreload\" xlink:href=\"https://evil.example/x.js\">");
        assertKept("<link rel=\"modulepreload\" src=\"https://evil.example/x.js\">");
    }

    // ---- markup tricks -------------------------------------------------------------------------------------

    @Test
    void relIsATokenListReadCaseInsensitively() {
        for (String rel : new String[] {"ModulePreload", "MODULEPRELOAD", "nofollow modulepreload",
                "modulepreload  nofollow", "\tmodulepreload\n", "stylesheet\fprefetch", "PREFETCH",
                "preload\rnext", "module&#112;reload", "&#109;odulepreload", "pre&#102;etch"}) {
            String as = rel.toLowerCase().contains("preload\r") ? " as=\"script\"" : "";
            assertStripped("<link rel=\"" + rel + "\"" + as + " href=\"https://evil.example/x.js\">");
        }
    }

    @Test
    void quotesWhitespaceAndAttributeOrderDoNotMatter() {
        String[] forms = {
            "<link rel=modulepreload href=https://evil.example/x.js>",
            "<link rel='modulepreload' href='https://evil.example/x.js'>",
            "<link href=\"https://evil.example/x.js\" rel=\"modulepreload\">",
            "<LINK REL=\"modulepreload\" HREF=\"https://evil.example/x.js\">",
            "<link\nrel\n=\n\"modulepreload\"\nhref\n=\n\"https://evil.example/x.js\"\n>",
            "<link\trel=modulepreload\thref=//evil.example/x.js/>",
            "<link rel=\"modulepreload\" href=\"https://evil.example/x.js\" />",
            "<link rel=\"modulepreload\"href=\"https://evil.example/x.js\">",
            "<link/rel=\"modulepreload\"/href=\"https://evil.example/x.js\">",
            "<link crossorigin integrity=\"sha384-x\" rel=\"modulepreload\" href=\"https://evil.example/x.js\">",
            "<link rel=\"modulepreload\" href=\"https://evil.example/x.js\" title='a > b'>",
            "<link title=\"<script>\" rel=\"modulepreload\" href=\"https://evil.example/x.js\">",
        };
        for (String link : forms) {
            assertStripped(link);
        }
    }

    @Test
    void theFirstOfDuplicateAttributesWinsAsInTheParser() {
        assertStripped("<link rel=\"modulepreload\" rel=\"stylesheet\" href=\"https://evil.example/x.js\">");
        assertKept("<link rel=\"stylesheet\" rel=\"modulepreload\" href=\"https://evil.example/x.js\">");
        assertStripped("<link rel=\"modulepreload\" href=\"https://evil.example/x.js\" href=\"/ok.js\">");
        assertKept("<link rel=\"modulepreload\" href=\"/ok.js\" href=\"https://evil.example/x.js\">");
        assertStripped("<link rel=\"preload\" as=\"script\" as=\"style\" href=\"https://evil.example/x.js\">");
        assertKept("<link rel=\"preload\" as=\"style\" as=\"script\" href=\"https://evil.example/x.js\">");
    }

    @Test
    void everyForeignLinkIsRemovedAndEverythingElseIsCopiedByteForByte() {
        String page = "<!DOCTYPE html>\n<html><head>\n"
                + "  <link rel=\"modulepreload\" href=\"https://a.example/1.js\">\n"
                + "  <link rel=\"modulepreload\" href=\"/static/m.js\">\n"
                + "  <link rel=prefetch href=//b.example/2>\n"
                + "  <script type=\"module\" src=\"/static/m.js\"></script>\n"
                + "  <link rel=\"preload\" as=\"script\" href=\"https://c.example/3.js\"><link rel=\"icon\" href=\"/f.ico\">\n"
                + "</head><body>é ü — 漢字</body></html>";
        ScriptPreloadStripper.Result result = strip(page);
        assertEquals("<!DOCTYPE html>\n<html><head>\n"
                + "  \n"
                + "  <link rel=\"modulepreload\" href=\"/static/m.js\">\n"
                + "  \n"
                + "  <script type=\"module\" src=\"/static/m.js\"></script>\n"
                + "  <link rel=\"icon\" href=\"/f.ico\">\n"
                + "</head><body>é ü — 漢字</body></html>", result.html());
        assertFalse(result.hidden());
    }

    @Test
    void linksTheBrowserMightNotFetchAreRemovedToo() {
        // Inside <svg> a <link> is a foreign element, inside <template> inert until cloned, inside <noscript> raw text
        // to a scripting browser — the scanner builds no tree, so it removes them all (harmless: fetches nothing).
        for (String wrap : new String[] {"<svg>%s</svg>", "<template>%s</template>", "<noscript>%s</noscript>",
                "<math>%s</math>", "<template shadowrootmode=\"open\">%s</template>", "<object>%s</object>"}) {
            String link = "<link rel=\"modulepreload\" href=\"https://evil.example/x.js\">";
            ScriptPreloadStripper.Result result = strip(String.format(wrap, link));
            assertEquals(String.format(wrap, ""), result.html(), wrap);
            assertFalse(result.hidden(), wrap);
        }
    }

    @Test
    void textThatIsNeverMarkupIsLeftAloneAndNotReported() {
        String[] inert = {
            "<!-- <link rel=\"modulepreload\" href=\"https://evil.example/x.js\"> -->",
            "<div title=\"<link rel=modulepreload href=https://evil.example/x.js>\"></div>",
            "<pre>&lt;link rel=\"modulepreload\" href=\"https://evil.example/x.js\"&gt;</pre>",
            "<p data-x='<link rel=\"modulepreload\" href=\"https://evil.example/x.js\">'>t</p>",
            "<!--><p>after an empty comment</p><!-- <link rel=modulepreload href=//evil.example/x.js> -->",
        };
        for (String html : inert) {
            ScriptPreloadStripper.Result result = strip(html);
            assertSame(html, result.html(), html);
            assertFalse(result.hidden(), html);
        }
    }

    @Test
    void aLinkAfterAnEmptyCommentIsSeen() {
        // <!--> is a complete comment to the tokenizer: the link after it is markup.
        assertEquals("<!-->", strip("<!--><link rel=\"modulepreload\" href=\"https://evil.example/x.js\">").html());
        assertEquals("<!--->", strip("<!---><link rel=\"modulepreload\" href=\"https://evil.example/x.js\">").html());
        assertEquals("<!-- a --!>",
                strip("<!-- a --!><link rel=\"modulepreload\" href=\"https://evil.example/x.js\">").html());
    }

    // ---- what the scanner cannot rewrite: reported as hidden ----------------------------------------------

    @Test
    void aForeignPreloadInsideRawTextIsReportedNotRewritten() {
        // In SVG/MathML these elements are not raw text: <svg><title><link …> is a real HTML <link> to the browser.
        String link = "<link rel=\"modulepreload\" href=\"https://evil.example/x.js\">";
        for (String wrap : new String[] {"<svg><title>%s</title></svg>", "<svg><style><p>%s</style></svg>",
                "<svg><script><div>%s</script></svg>", "<math><style><p>%s</style></math>",
                "<svg><textarea><p>%s</textarea></svg>", "<svg><xmp><p>%s</xmp></svg>",
                "<svg><iframe><p>%s</iframe></svg>", "<svg><noembed><p>%s</noembed></svg>",
                "<svg><noframes><p>%s</noframes></svg>", "<title>%s</title>",
                "<script>var s = '%s';</script>", "<svg><title><b><i>%s</i></b></title></svg>"}) {
            String html = String.format(wrap, link);
            ScriptPreloadStripper.Result result = strip(html);
            assertTrue(result.hidden(), wrap);
            assertSame(html, result.html(), "never rewritten inside raw text: " + wrap);
        }
    }

    @Test
    void anAllowedOrNonScriptLinkInsideRawTextIsNotReported() {
        for (String html : new String[] {"<svg><title><link rel=\"modulepreload\" href=\"/m.js\"></title></svg>",
                "<script>x = '<link rel=\"stylesheet\" href=\"https://evil.example/s.css\">';</script>",
                "<textarea>&lt;link rel=modulepreload href=https://evil.example/x.js&gt;</textarea>",
                "<style>/* <!-- <link rel=modulepreload href=https://evil.example/x.js> --> */</style>",
                "<script>a = '<link rel=\"modulepreload\" href=\"' + url + '\">';</script>"}) {
            assertFalse(strip(html).hidden(), html);
        }
    }

    @Test
    void aForeignPreloadInsideSrcdocIsReported() {
        String[] frames = {
            "<iframe srcdoc=\"&lt;link rel=modulepreload href=https://evil.example/x.js&gt;\"></iframe>",
            "<iframe srcdoc='<link rel=\"modulepreload\" href=\"//evil.example/x.js\">'></iframe>",
            "<iframe srcdoc=\"&lt;link rel=&quot;prefetch&quot; href=&quot;https://evil.example/p&quot;&gt;\"></iframe>",
            "<IFRAME SRCDOC=\"&lt;svg&gt;&lt;title&gt;&lt;link rel=modulepreload href=//evil.example/x.js&gt;\">",
            // nested: a srcdoc inside a srcdoc needs two rounds of decoding
            "<iframe srcdoc=\"&lt;iframe srcdoc=&quot;&amp;lt;link rel=modulepreload "
                    + "href=https://evil.example/x.js&amp;gt;&quot;&gt;\"></iframe>",
            // a srcdoc the scanner cannot place (inside raw text) is read too
            "<svg><title><iframe srcdoc=\"&lt;link rel=modulepreload href=//evil.example/x.js&gt;\">"
                    + "</iframe></title></svg>",
        };
        for (String html : frames) {
            ScriptPreloadStripper.Result result = strip(html);
            assertTrue(result.hidden(), html);
            assertSame(html, result.html(), html);
        }
    }

    @Test
    void anAllowedPreloadInsideSrcdocIsNotReported() {
        for (String html : new String[] {
            "<iframe srcdoc=\"&lt;link rel=modulepreload href=/m.js&gt;\"></iframe>",
            "<iframe srcdoc=\"&lt;link rel=stylesheet href=https://evil.example/s.css&gt;\"></iframe>",
            "<iframe srcdoc=\"&lt;!-- &lt;link rel=modulepreload href=https://evil.example/x.js&gt; --&gt;\">",
            "<iframe srcdoc=\"&amp;lt;link rel=modulepreload href=https://evil.example/x.js&amp;gt;\"></iframe>",
        }) {
            assertFalse(strip(html).hidden(), html);
        }
    }

    @Test
    void aSrcdocResolvesRelativeUrlsAgainstTheEmbeddingBase() {
        // The srcdoc document's base is the embedding document's: after a cross-origin <base>, a relative preload in
        // a srcdoc is foreign too.
        assertTrue(strip("<base href=\"https://evil.example/\"><iframe srcdoc=\"&lt;link rel=modulepreload "
                + "href=m.js&gt;\"></iframe>").hidden());
        assertFalse(strip("<iframe srcdoc=\"&lt;link rel=modulepreload href=m.js&gt;\"></iframe>").hidden());
        // ...and a cross-origin <base> inside the srcdoc applies within it.
        assertTrue(strip("<iframe srcdoc=\"&lt;base href=https://evil.example/&gt;&lt;link rel=modulepreload "
                + "href=m.js&gt;\"></iframe>").hidden());
    }

    @Test
    void nestingDeeperThanTheBoundCountsAsHidden() {
        String titles = "<p>clean</p>";
        for (int level = 0; level < ScriptPreloadStripper.MAX_DEPTH; level++) {
            titles = "<svg><title>" + titles; // each raw-text content holds the next one (no close tags needed)
        }
        assertFalse(strip(titles).hidden(), "raw text nested up to the bound is read");
        assertTrue(strip("<svg><title>" + titles).hidden(), "one level past the bound: refuse to vouch for it");

        String srcdoc = "<p>clean</p>";
        for (int level = 0; level < ScriptPreloadStripper.MAX_DEPTH; level++) {
            srcdoc = "<iframe srcdoc=\"" + srcdoc.replace("&", "&amp;").replace("\"", "&quot;") + "\"></iframe>";
        }
        assertFalse(strip(srcdoc).hidden(), "srcdoc nested up to the bound is read");
        String deeper = "<iframe srcdoc=\"" + srcdoc.replace("&", "&amp;").replace("\"", "&quot;") + "\"></iframe>";
        assertTrue(strip(deeper).hidden(), "srcdoc nested past the bound: refuse to vouch for it");
    }

    // ---- the tokenizer's corners ----------------------------------------------------------

    private static final String EVIL_LINK = "<link rel=\"modulepreload\" href=\"https://evil.example/x.mjs\">";
    private static final String OK_SCRIPT = "<script src=\"/ok.js\"></script>";

    /** The reviewer's pages: {@code freeze/security/repro/p1.html}, {@code p2.html} and the control, byte for byte. */
    private static final String CONTROL = "<!DOCTYPE html><html><head><title>t</title></head><body>\n"
            + EVIL_LINK + "\n" + OK_SCRIPT + "\n</body></html>\n";
    private static final String P1 = "<!DOCTYPE html><html><head><title>t</title></head><body><p>hi</p>\n"
            + "<?x <!-- y>\n" + EVIL_LINK + "\n" + OK_SCRIPT + "\n</body></html>\n";
    private static final String P2 = "<!DOCTYPE html><html><head><title>t</title></head><body>"
            + "<svg><![CDATA[<!--]]></svg>\n" + EVIL_LINK + "\n" + OK_SCRIPT + "\n<!-- footer comment -->\n"
            + "</body></html>\n";

    /**
     * {@code <?x <!-- y>} is ONE bogus comment to the browser (it ends at the first {@code >}), and
     * {@code <svg><![CDATA[<!--]]>} one CDATA section; the scanner used to step over {@code <?}/{@code <!} one
     * character at a time and read the {@code <!--} inside as a real comment, so the foreign modulepreload after it
     * was neither removed nor reported and the same-origin script after it went unstamped. Chrome 155 puts the link
     * in the DOM on all three pages.
     */
    @Test
    void theFreezeReviewersPagesLoseTheLinkAndKeepTheirScript() {
        for (String page : new String[] {CONTROL, P1, P2}) {
            ScriptPreloadStripper.Result result = strip(page);
            assertFalse(result.hidden(), page);
            assertEquals(page.replace(EVIL_LINK, ""), result.html(), page);
            assertTrue(ScriptNonceStamper.stamp(result.html(), "N", ALLOWLIST, DOC)
                    .contains("<script nonce=\"N\" src=\"/ok.js\">"), "the same-origin script is stamped: " + page);
        }
    }

    /**
     * A table of the tokenizer states the scanner must follow or refuse to guess (WHATWG HTML §13.2.5): bogus
     * comments ({@code <?}, {@code <!x}, {@code <!-}, {@code </} + non-letter), DOCTYPE (ends at the first {@code >},
     * quotes or not), comments ({@code <!-->}, {@code <!--->}, {@code --!>}), end tags with attributes, CDATA (a
     * section only in foreign content), the script-data escape states, raw-text end tags with attributes, and the
     * tree-builder contexts where a raw-text start tag does or does not switch the tokenizer ({@code <svg>},
     * {@code <math>}, {@code <select>}, {@code <template><col>}, {@code <noscript>} with scripting on or off).
     * Each prefix is put before a foreign modulepreload and a same-origin script. Expected:
     * <ul>
     *   <li>{@code R} — the browser sees the link as markup, and the scanner removes it (and stamps the script);</li>
     *   <li>{@code H} — the scanner cannot tell how the browser reads that stretch: it reports the page hidden, so
     *       the nonce is withdrawn and the host-allowlist policy blocks the fetch (fail closed);</li>
     *   <li>{@code I} — the link is not markup to the browser (inside a comment or a script); the body is left
     *       untouched and nothing is reported.</li>
     * </ul>
     * No case may come out "link still there, not hidden" when the browser sees the link: that is the bypass. The
     * classifications were checked in headless Chrome 155 (the page parsed with scripting on), and {@link
     * #aGeneratedCorpusNeverLeavesALinkTheTableWouldCatch} combines the prefixes.
     */
    @Test
    void theTokenizerCornersAreFollowedOrReportedNeverGuessed() {
        StringBuilder failures = new StringBuilder();
        for (String[] c : CORNERS) {
            String got = outcome(c[1]);
            if (!got.equals(c[0])) {
                failures.append("\n  expected ").append(c[0]).append(", got ").append(got).append(": ").append(c[1]);
            }
        }
        assertEquals("", failures.toString());
    }

    private static final String PAGE_HEAD = "<!DOCTYPE html><html><head><title>t</title></head><body><p>hi</p>";
    private static final String PAGE_FOOT = "<!-- footer --></body></html>";

    /** {@code R}, {@code H} or {@code I} (see the table) for {@code prefix} before the link and the script. */
    private static String outcome(String prefix) {
        String page = PAGE_HEAD + prefix + EVIL_LINK + OK_SCRIPT + PAGE_FOOT;
        ScriptPreloadStripper.Result result = strip(page);
        if (result.hidden()) {
            return "H";
        }
        if (result.html().contains("evil.example")) {
            return result.html().equals(page) ? "I" : "I*"; // I*: left the link but changed something else
        }
        return ScriptNonceStamper.stamp(result.html(), "N", ALLOWLIST, DOC)
                .contains("<script nonce=\"N\" src=\"/ok.js\">") ? "R" : "R-unstamped";
    }

    static final String[][] CORNERS = {
        // bogus comments end at the first '>'
        {"R", "<?x <!-- y>"},
        {"R", "<?php echo \"<!--\"; ?>"},
        {"R", "<!x <!-- y>"},
        {"R", "<!- <!-- y>"},
        {"R", "</3 <!-- y>"},
        {"R", "</ <!-- y>"},
        {"R", "</>"},
        {"R", "<!DOCTYPE x \"<!--\">"},
        {"I", "<!DOCTYPE html PUBLIC \"a>b<!--\">"},          // the DOCTYPE ends inside the quotes
        // CDATA: a bogus comment in HTML content...
        {"R", "<![CDATA[ <!-- ]]>"},
        {"I", "<![CDATA[ x>y <!-- ]]>"},
        // ...a CDATA section in foreign content
        {"R", "<svg><![CDATA[<!--]]></svg>"},
        {"R", "<svg><![CDATA[ a>b ]]></svg>"},
        {"H", "<svg><![CDATA[ a><!-- ]]></svg>"},              // the two readings disagree
        {"H", "<svg><p><![CDATA[ a><!-- ]]>"},
        {"R", "<math><mtext><![CDATA[ x ]]></mtext></math>"},
        {"I", "<svg><![CDATA["},                                // SVG: text; HTML: a bogus comment to the link's '>'
        // comments
        {"R", "<!---->"},
        {"R", "<!-->"},
        {"R", "<!--->"},
        {"R", "<!-- --!>"},
        {"R", "<!--!-->"},
        {"R", "<!--<!-->"},
        {"I", "<!-- -> "},
        // end tags carry attributes, quoted values included
        {"R", "</div title=\"<!--\">"},
        {"R", "</div title='>'<!--'>"},
        {"R", "<p title=\"x\" <!-- y>"},
        {"R", "<div title=\"</p><!--\">"},
        {"I", "<a href=\"x\"/<!--"},                            // the link becomes attributes of <a>
        // raw-text end tags carry attributes too
        {"R", "<script>a</script x=\">\"<!--\">"},
        {"R", "<style>x</style x=\">\"<!--\">"},
        {"R", "<style><!--</style>"},
        {"R", "<iframe><!--</iframe>"},
        {"R", "<textarea><!--</textarea>"},
        {"R", "<title><!--</title>"},
        {"R", "<xmp><!--</xmp>"},
        {"R", "<noembed><!--</noembed>"},
        {"R", "<noframes><!--</noframes>"},
        // the script-data escape states
        {"R", "<script><!--<script></script><!--</script>"},
        {"R", "<SCRIPT><!--<SCRIPT></SCRIPT><!--</SCRIPT>"},
        {"R", "<script><!--<script>--></script>"},
        {"R", "<script><!--</script>"},
        {"I", "<script><!--<script></script>"},                // double escaped: one script to the end of input
        // contexts where a raw-text start tag may not switch the tokenizer
        {"H", "<svg><style><!--</style></svg>"},
        {"H", "<svg><title><!--</title>"},
        {"H", "<svg><script><!--</script>"},
        {"H", "<svg><desc><style><!--</style>"},
        {"H", "<math><mi><style><!--</style>"},
        {"H", "<select><style><!--</style>"},
        {"H", "<template><col><style><a title=\"</style><!--\"></template>"},
        {"H", "<svg><base href=\"/x/\"></svg>"},                 // a base the tree builder may not honour
        // <noscript>: raw text with scripting on, markup with it off
        {"R", "<noscript></noscript>"},
        {"R", "<noscript><img src=\"/pixel.gif\"></noscript>"},
        {"R", "<noscript><style>.x{}</style></noscript>"},
        {"H", "<noscript><!--</noscript>"},
        {"H", "<noscript><p title=\"</noscript>\">"},
        // benign shapes real pages use, after which nothing is ambiguous
        {"R", "<svg viewBox=\"0 0 1 1\"><path d=\"M0 0\"/></svg>"},
        {"R", "<svg><path/></svg><style>a>b{}</style>"},
        {"R", "<svg></svg><script src=\"/a.js\"></script>"},
        {"R", "<svg><style><![CDATA[ .a{fill:red} ]]></style></svg>"},
        {"R", "<iframe srcdoc=\"<!--\"></iframe>"},
        {"R", "<plaintext>"},                                   // all text to the browser: removing is harmless
    };

    private static final String EVIL_LINK_2 = "<link rel=\"modulepreload\" href=\"https://evil.example/y.mjs\">";

    /**
     * Removing a tag must not splice its neighbours into a new token. Here a lone {@code <} (text: a {@code <}
     * follows it) sits before the foreign link and {@code !--<a title="-->} after it: cutting the link out would form
     * {@code <!--}, a comment that ends inside the title attribute and turns the second link — attribute text to the
     * browser — into markup. So when what is kept ends in {@code <} (or {@code ]}, which could close a CDATA section
     * in the foreign reading), an empty comment takes the removed tag's place.
     */
    @Test
    void aRemovalNeverSplicesItsNeighboursIntoANewToken() {
        String page = PAGE_HEAD + "<" + EVIL_LINK + "!--<a title=\"-->" + EVIL_LINK_2 + "\">" + OK_SCRIPT + PAGE_FOOT;
        ScriptPreloadStripper.Result result = strip(page);
        assertFalse(result.hidden());
        assertEquals(PAGE_HEAD + "<<!---->!--<a title=\"-->" + EVIL_LINK_2 + "\">" + OK_SCRIPT + PAGE_FOOT,
                result.html(), "the second link stays attribute text");
        assertEquals(PAGE_HEAD + "]]<!---->>x", strip(PAGE_HEAD + "]]" + EVIL_LINK + ">x").html());
        assertEquals(PAGE_HEAD + "<<!---->", strip(PAGE_HEAD + "<" + EVIL_LINK + EVIL_LINK).html(),
                "two in a row: the kept text still ends in '<'");
        assertEquals(PAGE_HEAD + "x ", strip(PAGE_HEAD + "x " + EVIL_LINK).html(), "otherwise nothing is left");
    }

    /** Single tokenizer-state openers and closers that {@link #corpus} composes with the table's prefixes. */
    static final String[] FRAGMENTS = {"<svg>", "</svg>", "<math>", "</math>", "<p>", "<select>", "</select>",
        "<template>", "</template>", "<col>", "<table>", "<noscript>", "</noscript>", "<style>", "</style>", "<title>",
        "</title>", "<script>", "</script>", "<textarea>", "</textarea>", "<!--", "-->", "<![CDATA[", "]]>", "<?", ">",
        "<!", "</", "\"", "'", "<a title=\"", "<foreignObject>", "</foreignObject>", "<desc>", "<mi>", "<mtext>",
        "<iframe>", "</iframe>", "<xmp>", "<plaintext>", "<!-->", "--!>", "<script><!--", "<script><!--<script>",
        "<base href=\"/x/\">", "<frameset>", "<math><annotation-xml encoding=\"text/html\">", "<!DOCTYPE x>",
        "<noembed>", "</noembed>", "<noframes>", "</noframes>", "a>b", "</p title='>'>", "<b x=\"", "<svg><title>",
        "<svg><style>", "<svg><script>", "</script x=\">\">", "<![CDATA[x]]>", "<", "]", "]]",
        "!--<a title=\"-->" + EVIL_LINK_2 + "\">", EVIL_LINK_2,
        "<" + EVIL_LINK_2 + "!--<a title=\"-->" + EVIL_LINK_2 + "\">"};

    /** The seed of the corpus whose Chrome verdicts are pinned in {@code preload-corpus-chrome155.txt}. */
    static final long CORPUS_SEED = 20261018L;

    /**
     * {@code n} generated pages, deterministic for {@code seed}: one to four fragments (table prefixes and
     * {@link #FRAGMENTS}) with the foreign link after them — or, three times in ten, among them — then the
     * same-origin script.
     */
    static java.util.List<String> corpus(long seed, int n) {
        java.util.Random random = new java.util.Random(seed);
        java.util.List<String> pool = new java.util.ArrayList<>();
        for (String[] c : CORNERS) {
            pool.add(c[1]);
        }
        pool.addAll(java.util.List.of(FRAGMENTS));
        java.util.List<String> pages = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int count = 1 + random.nextInt(4);
            int linkAt = random.nextInt(10) < 3 ? random.nextInt(count + 1) : count;
            StringBuilder page = new StringBuilder(PAGE_HEAD);
            for (int k = 0; k < count; k++) {
                if (k == linkAt) {
                    page.append(EVIL_LINK);
                }
                page.append(pool.get(random.nextInt(pool.size())));
            }
            if (linkAt == count) {
                page.append(EVIL_LINK);
            }
            pages.add(page.append(OK_SCRIPT).append(PAGE_FOOT).toString());
        }
        return pages;
    }

    /**
     * The generated corpus against Chrome's own parser: {@code preload-corpus-chrome155.txt} records, per page of
     * {@code corpus(CORPUS_SEED, n)}, whether headless Chrome 155 (scripting on) put the page's foreign
     * {@code x.mjs} modulepreload {@code <link>} in the DOM of the page as written ({@code 1}) or not ({@code 0}).
     * Wherever it did, the edge must remove it or report the page hidden. (The harness that produced the file also
     * parsed every served body in Chrome and found no foreign link at all — {@code x.mjs}, the {@code y.mjs} ones in
     * the fragments — in any body served under the nonce: the stronger form of this check, which needs a browser;
     * this test keeps the verdicts as a regression.)
     */
    @Test
    void aGeneratedCorpusNeverLeavesALinkChromeSees() throws java.io.IOException {
        String verdicts;
        try (java.io.InputStream in = getClass().getResourceAsStream("preload-corpus-chrome155.txt")) {
            assertTrue(in != null, "the pinned Chrome verdicts");
            verdicts = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).lines()
                    .filter(line -> !line.startsWith("#")).collect(java.util.stream.Collectors.joining());
        }
        java.util.List<String> pages = corpus(CORPUS_SEED, verdicts.length());
        int seen = 0;
        StringBuilder failures = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            if (verdicts.charAt(i) != '1') {
                continue;
            }
            seen++;
            ScriptPreloadStripper.Result result = strip(pages.get(i));
            if (!result.hidden() && result.html().contains("evil.example/x.mjs")) {
                failures.append("\n  #").append(i).append(": ").append(pages.get(i));
            }
        }
        assertTrue(seen > 1000, "the corpus exercises many pages where the link is real: " + seen);
        assertEquals("", failures.toString());
    }

    // ---- limits and degenerate input ----------------------------------------------------------------------

    @Test
    void anUnterminatedTagStopsTheScanAndLeavesTheRestAlone() {
        String html = "<link rel=\"modulepreload\" href=\"https://evil.example/a.js\"><div title='unclosed"
                + "<link rel=\"modulepreload\" href=\"https://evil.example/b.js\">";
        assertEquals("<div title='unclosed<link rel=\"modulepreload\" href=\"https://evil.example/b.js\">",
                strip(html).html());
        String open = "<link rel=\"modulepreload\" href=\"https://evil.example/a.js\"";
        assertSame(open, strip(open).html(), "a tag the browser drops at end of input is left alone");
    }

    @Test
    void degenerateInputComesBackUnchanged() {
        assertEquals(null, ScriptPreloadStripper.strip(null, ALLOWLIST, DOC).html());
        assertEquals("", ScriptPreloadStripper.strip("", ALLOWLIST, DOC).html());
        String html = "<link rel=\"modulepreload\" href=\"https://evil.example/a.js\">";
        assertSame(html, ScriptPreloadStripper.strip(html, null, DOC).html());
        assertSame(html, ScriptPreloadStripper.strip(html, ALLOWLIST, null).html());
        assertFalse(ScriptPreloadStripper.strip(html, ALLOWLIST, null).hidden());
        String plain = "no tags at all < > &";
        assertSame(plain, strip(plain).html());
    }

    @Test
    void aWildcardPolicyKeepsEveryHttpPreload() {
        CspScriptAllowlist star = CspScriptAllowlist.parse("'self' *");
        String html = "<link rel=\"modulepreload\" href=\"https://evil.example/a.js\">";
        assertSame(html, ScriptPreloadStripper.strip(html, star, DOC).html());
    }

    @Test
    void theStampAndTheStripperShareOneScanner() {
        // A script and a link inside the same raw-text corner: the stamp leaves the script unstamped (fail closed) and
        // the stripper reports the link — neither treats the corner as markup.
        String html = "<svg><title><script src=\"/ok.js\"></script>"
                + "<link rel=\"modulepreload\" href=\"https://evil.example/x.js\"></title></svg>";
        assertSame(html, ScriptNonceStamper.stamp(html, "N", ALLOWLIST, DOC));
        assertTrue(strip(html).hidden());
        // And the stamp still works after a removal on the same body.
        String page = "<link rel=\"modulepreload\" href=\"https://evil.example/x.js\"><script src=\"/ok.js\"></script>";
        assertEquals("<script nonce=\"N\" src=\"/ok.js\"></script>",
                ScriptNonceStamper.stamp(strip(page).html(), "N", ALLOWLIST, DOC));
    }
}
