package mx.quest.runway.head;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import mx.quest.runway.web.CspScriptAllowlist;
import org.junit.jupiter.api.Test;

/**
 * The body half of the public-page CSP nonce: which {@code <script>} tags get {@code nonce="…"}, which never do,
 * and the tokenizer corners (quoted attributes, comments, raw-text contents, character references) where a naive
 * scan would stamp text that is not a tag or misread a URL — plus the documented limits, pinned so a change to them
 * is a deliberate one.
 *
 * <p>The invariant under test: a script is stamped <b>only</b> when the host-allowlist policy the page
 * had before {@code 'strict-dynamic'} would have run it. Under {@code 'strict-dynamic'} an unstamped script never
 * runs, so every "not stamped" assertion below is a script that stays blocked exactly as it was.
 */
class ScriptNonceStamperTest {

    private static final String N = "AbC_-123xyzAbC_-123xy";

    /** The shipped default plus the reCAPTCHA hosts the protection plugin contributes. */
    private static final CspScriptAllowlist ALLOWLIST =
            CspScriptAllowlist.parse("'self' https://www.google.com https://www.gstatic.com");

    private static final URI DOC = URI.create("http://site.test/blog/post");

    private static String stamp(String html) {
        return ScriptNonceStamper.stamp(html, N, ALLOWLIST, DOC);
    }

    private static String stamped(String openTagRest) {
        return "<script nonce=\"" + N + "\"" + openTagRest;
    }

    // ---- the invariant: what the allowlist blocked stays blocked ----------------------------------------------

    /** A review probe: markup injected into a page, inline and from a foreign host. */
    @Test
    void theReviewersProbeIsNotStamped() {
        String probe = "<h1>Results for: <script>alert(document.cookie)</script></h1>"
                + "<script src=\"https://evil.example/x.js\"></script>";
        assertSame(probe, stamp(probe));
    }

    @Test
    void anInlineScriptIsNeverStampedUnderTheShippedPolicy() {
        for (String inline : new String[] {"<script>go()</script>", "<script type=\"module\">import('/m.js')</script>",
                "<script type=\"importmap\">{\"imports\":{}}</script>",
                "<script type=\"speculationrules\">{}</script>", "<SCRIPT>x()</SCRIPT>"}) {
            assertSame(inline, stamp(inline), inline);
        }
    }

    @Test
    void aForeignHostIsNeverStampedHoweverTheUrlIsSpelled() {
        String[] foreign = {
            "https://evil.example/x.js",
            "//evil.example/x.js",                       // scheme-relative
            " \n//evil.example/x.js ",                    // the URL parser trims C0/space
            "http://www.google.com.evil.example/x.js",   // allowlisted name as a label of another host
            "https://www.google.com@evil.example/x.js",  // user info: the host is evil.example
            "https://evil.example/https://www.google.com/x.js",
            "https://google.com/x.js",                    // the apex is not www
            "https://www.google.com:8443/x.js",           // a port the source does not name
            "http://site.test:9999/x.js",                 // same host, other port: another origin
            "https&#58;//evil.example/x.js",              // character references are decoded first
            "&#x2F;&#x2F;evil.example/x.js",
            "&sol;&sol;evil.example/x.js",
            "&#47&#47evil.example/x.js",                  // numeric references need no semicolon
            "\\\\evil.example/x.js",                      // a browser reads '\' as '/'
            "/\\evil.example/x.js",
            "https:evil.example/x.js",                    // special scheme without "//"
            "https:///evil.example/x.js",                 // the parser skips the extra slash; the JDK does not
            "ht\ttps://evil.example/x.js",                // tab/newline are dropped anywhere
            "https://%65vil.example/x.js",                // a percent-encoded host
            "https://www.google.com&#x2e;evil.example/x.js",
            "data:text/javascript,alert(1)",
            "javascript:alert(1)",
            "blob:https://site.test/1234",
            "",                                           // src="" never loads; nothing to authorize
            // Proven in Chrome 155: the WHATWG parser skips every slash where the authority
            // starts, so these name evil.example; java.net.URI saw an empty authority and a same-origin path.
            "///evil.example/x.js",
            "////evil.example/x.js?q2",
            " ///evil.example/x.js",
            "/\t//evil.example/x.js",                      // tab dropped first: "///evil…"
            "/\n/\n/evil.example/x.js",
            "&#47;//evil.example/x.js",
            "&sol;&sol;&sol;evil.example/x.js",
            "https:////evil.example/x.js",
            "///site.test/x.js",                          // even the page's own host: refused, not modelled
        };
        for (String url : foreign) {
            String in = "<script src=\"" + url + "\"></script>";
            assertSame(in, stamp(in), url);
        }
    }

    @Test
    void sameOriginAndAllowlistedScriptsAreStamped() {
        String[] allowed = {
            "/a.js", "a.js", "../a.js", "./a.js?v=1", "?v=2", "#x",
            "http://site.test/a.js", "HTTP://SITE.TEST/a.js", "//site.test/a.js",
            "https://site.test/a.js",                     // 'self' allows the secure upgrade
            "https://www.google.com/recaptcha/api.js?render=k&amp;hl=es",
            "https://www.google.com/recaptcha/api.js?render=k&hl=es",
            "HTTPS://WWW.GSTATIC.COM/recaptcha/x.js",
            "&#x2F;assets&#x2F;a.js",                    // decodes to /assets/a.js
            "/café.js",                              // non-ASCII in the path
        };
        for (String url : allowed) {
            String in = "<script src=\"" + url + "\"></script>";
            assertEquals(stamped(" src=\"" + url + "\"></script>"), stamp(in), url);
        }
    }

    /** reCAPTCHA as the protection plugin's widget emits it: both scripts stamped (allowlisted host + same origin). */
    @Test
    void stampsThePluginCaptchaWidgetMarkup() {
        String widget = "<input type=\"hidden\" class=\"rw-captcha-v3\" name=\"g-recaptcha-response\" "
                + "data-sitekey=\"k\"><script src=\"https://www.google.com/recaptcha/api.js?render=k\" async defer>"
                + "</script><script src=\"/__assets/captcha-v3.js\" async defer></script>";
        String out = stamp(widget);
        assertEquals(2, out.split("<script nonce=\"" + N + "\" src=", -1).length - 1, out);
    }

    /** With no host contributed (protection plugin absent), the reCAPTCHA loader is foreign — as it was before. */
    @Test
    void anUnlistedHostIsForeignEvenWhenItIsAWellKnownOne() {
        String in = "<script src=\"https://www.google.com/recaptcha/api.js\"></script>";
        assertSame(in, ScriptNonceStamper.stamp(in, N, CspScriptAllowlist.selfOnly(), DOC));
    }

    @Test
    void theFirstOfDuplicateAttributesDecidesAsInTheParser() {
        assertEquals(stamped(" src=\"/ok.js\" src=\"https://evil.example/x.js\"></script>"),
                stamp("<script src=\"/ok.js\" src=\"https://evil.example/x.js\"></script>"));
        String evilFirst = "<script SRC=\"https://evil.example/x.js\" src=\"/ok.js\"></script>";
        assertSame(evilFirst, stamp(evilFirst));
    }

    /** Every URL a script names must be allowed — an SVG script loads its href, so an evil one next to a good src. */
    @Test
    void everyUrlTheElementNamesMustBeAllowed() {
        String mixed = "<script src=\"/ok.js\" href=\"https://evil.example/x.js\"></script>";
        assertSame(mixed, stamp(mixed));
        String mixedXlink = "<svg><script src=\"/ok.js\" xlink:href=\"https://evil.example/x.js\"></script></svg>";
        assertSame(mixedXlink, stamp(mixedXlink));
        assertEquals("<svg>" + stamped(" src=\"/ok.js\" href=\"/s.js\"></script></svg>"),
                stamp("<svg><script src=\"/ok.js\" href=\"/s.js\"></script></svg>"));
        String svgEvil = "<svg><script xlink:href=\"https://evil.example/s.js\"></script></svg>";
        assertSame(svgEvil, stamp(svgEvil));
        String svgInline = "<svg><script>s()</script></svg>";
        assertSame(svgInline, stamp(svgInline));
    }

    /**
     * Proven in Chrome 155: an HTML {@code <script>} has no {@code href} — the browser
     * ignores it and runs the element's TEXT inline, also inside SVG/MathML integration points. Only {@code src}
     * makes a script external, so a script without {@code src} is never stamped, whatever it carries.
     */
    @Test
    void aScriptWithoutSrcIsNeverStampedWhateverUrlAttributeItCarries() {
        String[] probes = {
            "<script href=\"/\">document.documentElement.setAttribute('data-p1','ran')</script>",
            "<script xlink:href=\"/x.js\">x()</script>",
            "<script href=\"#\">x()</script>",
            "<script type=\"module\" href=\"/\">x()</script>",
            "<script type=\"importmap\" href=\"/\">{\"imports\":{\"zzz\":\"/x.js\"}}</script>",
            "<script\nhref\n=\n/\n>x()</script>",                       // split over lines, unquoted
            "<SCRIPT HREF=\"/\">x()</SCRIPT>",
            "<script href=\"/s.js\"></script>",                            // even with no text
            "<svg><script href=\"/s.js\"></script></svg>",                  // SVG href-only: blocked (documented)
            "<svg><script xlink:href=\"/s.js\"></script></svg>",
            "<svg><foreignObject><script href=\"/\">x()</script></foreignObject></svg>",
            "<svg><desc><script href=\"/\">x()</script></desc></svg>",
            "<math><mtext><script href=\"/\">x()</script></mtext></math>",
            "<script src>x()</script>",                                     // bare src: empty URL never allowed
        };
        for (String probe : probes) {
            assertSame(probe, stamp(probe), probe);
        }
    }

    /**
     * Inside SVG the browser ignores {@code src} and runs the text (proven in Chrome 155): a {@code <script src>} is
     * stamped only when its text is blank, so the stamp can never carry text the allowlist would have blocked.
     */
    @Test
    void aScriptWithTextIsNotStampedEvenWithAnAllowedSrc() {
        for (String probe : new String[] {
            "<svg><script src=\"/x.js\">evil()</script></svg>",
            "<script src=\"/x.js\">evil()</script>",
            "<script src=\"/x.js\"><![CDATA[evil()]]></script>",
            "<script src=\"/x.js\">&#101;vil()</script>",
            "<script src=\"/x.js\"> </script>",                       // NBSP is not ASCII whitespace
        }) {
            assertSame(probe, stamp(probe), probe);
        }
        assertEquals(stamped(" src=\"/x.js\">\n  \t\r\f</script>"), stamp("<script src=\"/x.js\">\n  \t\r\f</script>"));
        assertEquals("<svg>" + stamped(" src=\"/x.js\"/></script></svg>"),
                stamp("<svg><script src=\"/x.js\"/></script></svg>"));
        // A self-closed SVG script followed by text: the text belongs to the next element, but the scanner reads
        // it as this script's — not stamped (fail closed).
        String selfClosedThenText = "<svg><script src=\"/x.js\"/>evil()</script></svg>";
        assertSame(selfClosedThenText, stamp(selfClosedThenText));
    }

    // ---- a nonce already in the markup ------------------------------------------------------------------------

    /**
     * The page nonce is public and stable per version, so injected markup could copy it. An allowed script gets the
     * page nonce FIRST (the parser keeps the first duplicate); a blocked one gets an empty nonce first, so the copied
     * value is ignored and the script stays blocked.
     */
    @Test
    void aNonceAlreadyInTheMarkupNeverAuthorizesAScript() {
        assertEquals("<script nonce=\"\" nonce=\"" + N + "\">alert(1)</script>",
                stamp("<script nonce=\"" + N + "\">alert(1)</script>"));
        assertEquals("<script nonce=\"\" src=\"https://evil.example/x.js\" NONCE=\"" + N + "\"></script>",
                stamp("<script src=\"https://evil.example/x.js\" NONCE=\"" + N + "\"></script>"));
        assertEquals(stamped(" src=\"/a.js\" nonce=\"other\"></script>"),
                stamp("<script src=\"/a.js\" nonce=\"other\"></script>"));
    }

    /**
     * The edge's guard for copies the scanner cannot override: the page's own nonce
     * anywhere in the unstamped body — literally or behind any depth of character references, as inside a nested
     * {@code srcdoc} — is detected; anything else is not.
     */
    @Test
    void carriesNonceFindsTheNonceInAnyEncoding() {
        assertTrue(ScriptNonceStamper.carriesNonce("<script nonce=\"" + N + "\">x</script>", N));
        assertTrue(ScriptNonceStamper.carriesNonce("<iframe srcdoc=\"&lt;script nonce=&quot;" + N
                + "&quot;&gt;parent.x()&lt;/script&gt;\"></iframe>", N), "the verifier's srcdoc probe");
        String encoded = N.chars().mapToObj(c -> "&#" + c + ";").reduce("", String::concat);
        assertTrue(ScriptNonceStamper.carriesNonce("<iframe srcdoc=\"<script nonce=" + encoded + ">\">", N));
        String doubly = encoded.replace("&", "&amp;");
        assertTrue(ScriptNonceStamper.carriesNonce("<iframe srcdoc=\"<iframe srcdoc='<script nonce=" + doubly
                + ">'>\">", N), "a srcdoc inside a srcdoc");
        String hex = N.chars().mapToObj(c -> "&#x" + Integer.toHexString(c) + "").reduce("", String::concat);
        assertTrue(ScriptNonceStamper.carriesNonce("<svg><style><script nonce=" + hex + " >x</script>", N),
                "numeric references without ';' — and a copy the scanner skips (SVG <style> is not raw text)");
        assertTrue(ScriptNonceStamper.carriesNonce("<!--><script nonce=\"" + N + "\">x</script>", N));

        String deep = encoded;
        for (int i = 0; i < 20; i++) {
            deep = deep.replace("&", "&amp;");
        }
        assertTrue(ScriptNonceStamper.carriesNonce(deep, N), "nesting beyond the bound counts as carrying it");

        assertFalse(ScriptNonceStamper.carriesNonce("<script nonce=\"other\">x</script>", N));
        assertFalse(ScriptNonceStamper.carriesNonce("<p>&amp;lt;x&amp;gt; &copy; " + N.substring(1) + "</p>", N));
        assertFalse(ScriptNonceStamper.carriesNonce("<p>" + N.toLowerCase() + "</p>", N), "case-sensitive");
        assertFalse(ScriptNonceStamper.carriesNonce(null, N));
        assertFalse(ScriptNonceStamper.carriesNonce("x", null));
        assertFalse(ScriptNonceStamper.carriesNonce("x", ""));
    }

    // ---- <base href> ------------------------------------------------------------------------------------------

    @Test
    void aCrossOriginBaseMakesRelativeScriptsUnstampable() {
        String in = "<base href=\"https://evil.example/\"><script src=\"a.js\"></script>"
                + "<script src=\"https://www.google.com/x.js\"></script>";
        assertEquals("<base href=\"https://evil.example/\"><script src=\"a.js\"></script>"
                + stamped(" src=\"https://www.google.com/x.js\"></script>"), stamp(in));
    }

    @Test
    void aSameOriginBaseIsFollowedAndOnlyTheFirstBaseCounts() {
        assertEquals("<base href=\"/sub/\">" + stamped(" src=\"a.js\"></script>"),
                stamp("<base href=\"/sub/\"><script src=\"a.js\"></script>"));
        assertEquals("<base href=\"/x/\"><base href=\"https://evil.example/\">" + stamped(" src=\"a.js\"></script>"),
                stamp("<base href=\"/x/\"><base href=\"https://evil.example/\"><script src=\"a.js\"></script>"));
        // A <base> without href does not set the base URL; a later one with href still can.
        assertEquals("<base target=\"_top\"><base href=\"//evil.example/\"><script src=\"a.js\"></script>",
                stamp("<base target=\"_top\"><base href=\"//evil.example/\"><script src=\"a.js\"></script>"));
    }

    // ---- an operator policy that allowed inline script ------------------------------------------------------

    @Test
    void inlineScriptsAreStampedOnlyWhereTheConfiguredPolicyRanThem() {
        String inline = "<script>go()</script>";
        assertEquals(stamped(">go()</script>"), ScriptNonceStamper.stamp(inline, N,
                CspScriptAllowlist.parse("'self' 'unsafe-inline'"), DOC));
        // A hash source makes a browser ignore 'unsafe-inline' — the old policy blocked this inline script.
        assertSame(inline, ScriptNonceStamper.stamp(inline, N,
                CspScriptAllowlist.parse("'self' 'unsafe-inline' 'sha256-AAAA'"), DOC));
    }

    // ---- data blocks and types --------------------------------------------------------------------------------

    @Test
    void stampsEveryExecutableType() {
        for (String type : new String[] {"", "module", "text/javascript", "application/javascript", "TEXT/JAVASCRIPT",
                " text/javascript ", "text/javascript; charset=utf-8", "application/ecmascript", "text/x-javascript",
                "text&#x2F;javascript"}) {
            String in = "<script type=\"" + type + "\" src=\"/x.js\"></script>";
            assertEquals(stamped(" type=\"" + type + "\" src=\"/x.js\"></script>"), stamp(in), type);
        }
    }

    @Test
    void leavesDataBlocksAloneEvenWithASourceOrANonce() {
        for (String type : new String[] {"application/ld+json", "application/json", "text/template",
                "text/x-handlebars-template", "text/plain"}) {
            String in = "<script type=\"" + type + "\">{\"a\":1}</script>"
                    + "<script type=\"" + type + "\" src=\"/d.json\" nonce=\"x\"></script>";
            assertSame(in, stamp(in), type);
        }
    }

    // ---- tokenizer corners ------------------------------------------------------------------------------------

    @Test
    void isCaseInsensitiveAndIndependentOfAttributeOrder() {
        assertEquals("<SCRIPT nonce=\"" + N + "\" defer TYPE='text/javascript' src=x.js></SCRIPT>",
                stamp("<SCRIPT defer TYPE='text/javascript' src=x.js></SCRIPT>"));
        assertEquals("<Script nonce=\"" + N + "\"\n  src=\"/b.js\"\n  async></Script>",
                stamp("<Script\n  src=\"/b.js\"\n  async></Script>"));
        assertEquals(stamped(" x=\"a\"src=\"/c.js\"></script>"), stamp("<script x=\"a\"src=\"/c.js\"></script>"));
    }

    @Test
    void doesNotStampScriptTextInsideAScriptBody() {
        // A JS string that spells a tag is script DATA, not a tag (the scanner skips to </script>). The outer script
        // has text, so it is not stamped either.
        String in = "<script src=\"/a.js\">var s = \"<script src=/b.js></script>\";</script>";
        assertEquals(in, stamp(in));
        String inline = "<script>var s = \"<script src=/b.js></script>\";</script>";
        assertEquals(inline, stamp(inline));
    }

    @Test
    void doesNotStampInsideAQuotedAttributeValue() {
        String in = "<div title=\"<script src=/a.js>x</script>\" data-x='<script src=/a.js>'>ok</div>";
        assertSame(in, stamp(in));
    }

    /**
     * {@code <!-->} and {@code <!--->} are complete, empty comments and {@code --!>} closes one (the tokenizer). The
     * scanner used to read them as open comments and skip the script after them — a same-origin script the browser
     * runs, left unstamped and so blocked (proven in Chrome 155).
     */
    @Test
    void readsCommentsAsTheTokenizerDoes() {
        assertEquals("<!-->" + stamped(" src=\"/x.js\"></script>"), stamp("<!--><script src=\"/x.js\"></script>"));
        assertEquals("<!--->" + stamped(" src=\"/x.js\"></script>"), stamp("<!---><script src=\"/x.js\"></script>"));
        assertEquals("<!-- a --!>" + stamped(" src=\"/x.js\"></script>"),
                stamp("<!-- a --!><script src=\"/x.js\"></script>"));
        assertEquals("<!---->" + stamped(" src=\"/x.js\"></script>"), stamp("<!----><script src=\"/x.js\"></script>"));
        assertEquals("<!-- a --->" + stamped(" src=\"/x.js\"></script>"),
                stamp("<!-- a ---><script src=\"/x.js\"></script>"));
        for (String inside : new String[] {
            "<!-- -> <script src=\"/x.js\"></script> -->",         // "->" does not close a comment
            "<!-- --! <script src=\"/x.js\"></script> -->",        // "--!" without ">" does not either
            "<!-- - -> <script src=\"/x.js\"></script> -->",
            "<!-- --!-> <script src=\"/x.js\"></script> -->",
        }) {
            assertSame(inside, stamp(inside), inside);
        }
    }

    @Test
    void doesNotStampInsideACommentOrRawTextElements() {
        String in = "<!-- <script src=/a.js></script> --><style>/* <script src=/a.js> */</style>"
                + "<textarea><script src=/a.js></script></textarea><title><script src=/a.js></title>";
        assertSame(in, stamp(in));
    }

    /** End tags match ASCII case-insensitively only: "</ſtyle>" (long s) does not close a style element. */
    @Test
    void aNonAsciiLookalikeDoesNotCloseARawTextElement() {
        String in = "<style></ſtyle><script src=/a.js></script></style>";
        assertSame(in, stamp(in));
    }

    @Test
    void stampsAScriptAfterRawTextElementsAndInsideInlineSvg() {
        assertEquals("<style>a{}</style><svg>" + stamped(" src=\"/s.js\"></script></svg>"),
                stamp("<style>a{}</style><svg><script src=\"/s.js\"></script></svg>"));
    }

    @Test
    void aSelfClosingSlashStillOpensAScript() {
        assertEquals(stamped("/src=\"/a.js\"></script>"), stamp("<script/src=\"/a.js\"></script>"));
    }

    @Test
    void doesNotConfuseALongerTagName() {
        String in = "<scripted src=/a.js>x</scripted><scripts src=/a.js></scripts>";
        assertSame(in, stamp(in));
    }

    @Test
    void returnsTheSameInstanceWhenThereIsNothingToStampAndHandlesNulls() {
        String in = "<html><body><p>a < b</p></body></html>";
        assertSame(in, stamp(in));
        assertSame(null, ScriptNonceStamper.stamp(null, N, ALLOWLIST, DOC));
        String script = "<script src=/a.js></script>";
        assertSame(script, ScriptNonceStamper.stamp(script, null, ALLOWLIST, DOC));
        assertSame(script, ScriptNonceStamper.stamp(script, "", ALLOWLIST, DOC));
        assertSame(script, ScriptNonceStamper.stamp(script, N, null, DOC));
        assertSame(script, ScriptNonceStamper.stamp(script, N, ALLOWLIST, null));
    }

    @Test
    void preservesNonAsciiAndEverythingAroundTheInsertion() {
        assertEquals("<p>café — ñ</p>" + stamped(" src=\"/ü.js\"></script><p>fin</p>"),
                stamp("<p>café — ñ</p><script src=\"/ü.js\"></script><p>fin</p>"));
    }

    // ---- attribute decoding -----------------------------------------------------------------------------------

    @Test
    void decodesTheCharacterReferencesThatCanProduceAscii() {
        assertEquals("https://a/b", ScriptNonceStamper.decodeAttribute("https&#58;&#x2F;&#X2f;a&sol;b"));
        assertEquals("//e", ScriptNonceStamper.decodeAttribute("&#47&#47e"));
        assertEquals("a&b<c", ScriptNonceStamper.decodeAttribute("a&amp;b&lt;c"));
        assertEquals("?a=1&ampb", ScriptNonceStamper.decodeAttribute("?a=1&ampb"),
                "a legacy reference before an alphanumeric stays literal in an attribute");
        assertEquals("?a=1&amp=2", ScriptNonceStamper.decodeAttribute("?a=1&amp=2"), "… and before '='");
        assertEquals("x& y", ScriptNonceStamper.decodeAttribute("x&amp y"), "legacy &amp before a space expands");
        assertEquals("&copy;��", ScriptNonceStamper.decodeAttribute("&copy;&#0;&#x110000;"));
        assertEquals("&#;&#x;", ScriptNonceStamper.decodeAttribute("&#;&#x;"));
        assertEquals("�", ScriptNonceStamper.decodeAttribute("&#x85;"), "the C1 range is never ASCII");
        assertEquals("�", ScriptNonceStamper.decodeAttribute("&#99999999999999999999;"));
    }

    // ---- the parser sweep ----------------------------------------------------------------------------

    /**
     * An 84-case sweep of the URL parser, re-run as a
     * regression: allowlist {@code 'self' https://www.google.com}, document {@code https://site.example/page}; each
     * case gets {@code </script>} appended unless it has one. {@code 1} = stamped once, {@code 0} = not stamped. A
     * {@code 1} on a script the browser never executes (inside {@code <template>}, {@code <noscript>}, after
     * {@code <plaintext>}, a non-JavaScript type) is a harmless over-stamp; every foreign, inline or ambiguous case
     * is {@code 0}.
     */
    @Test
    void theVerifiersParserSweep() {
        CspScriptAllowlist allowlist = CspScriptAllowlist.parse("'self' https://www.google.com");
        URI doc = URI.create("https://site.example/page");
        String[][] cases = {
            {"1", "<script src=/x.js>"},
            {"1", "<SCRIPT SRC=/x.js>"},
            {"1", "<script/src=/x.js>"},
            {"1", "<script\tsrc\n=\r\n'/x.js'>"},
            {"1", "<script src=/x.js/>"},
            {"0", "<script src=\"https://evil.example/x.js\" src=\"/x.js\">"},
            {"1", "<script src=\"/x.js\" src=\"https://evil.example/x.js\">"},
            {"0", "<script src=\"https://site.example.evil.example/x.js\">"},
            {"1", "<script src=\"https://site.example:443/x.js\">"},
            {"0", "<script src=\"https://site.example:8443/x.js\">"},
            {"1", "<script src=\"https://SITE.EXAMPLE/x.js\">"},
            {"0", "<script src=\"https://site.example./x.js\">"},
            {"0", "<script src=\"https://site.example%2F@evil.example/\">"},
            {"0", "<script src=\"https://site.example\\\\@evil.example/\">"},
            {"1", "<script src=\"//site.example/x.js\">"},
            {"0", "<script src=\"///evil.example/x.js\">"},                       // (was stamped)
            {"1", "<script src=\"/ /evil.example/x.js\">"},                       // a path with a space: same origin
            {"0", "<script src=\"http://www.google.com/recaptcha/api.js\">"},
            {"0", "<script src=\"https://www.google.com.evil.example/\">"},
            {"0", "<script src=\"https://www.google.com:8443/x.js\">"},
            {"0", "<script src=\"https://xn--80ak6aa92e.com/x.js\">"},
            {"1", "<script src=\"https://www.google.com/x.js\" type=\"text/javascript;charset=utf-8\">"},
            {"0", "<script src=\"/x.js\" type=\"text/plain\">"},
            {"1", "<script src=\"/x.js\" type=\" TEXT/JAVASCRIPT \">"},
            {"1", "<script src=\"/x.js\" type=\"text/javascript\n\">"},
            {"1", "<script src=\"&#x2F;x.js\">"},
            {"1", "<script src=\"&sol;x.js\">"},
            {"0", "<script src=\"https&colon;//evil.example/x.js\">"},
            {"1", "<script src=\"https&colon//evil.example/x.js\">"},            // literal "&colon": a relative path
            {"0", "<script src=\"&#104;&#116;&#116;&#112;&#115;://evil.example/x.js\">"},
            {"1", "<script src=\"/x.js?u=https://evil.example\">"},
            {"1", "<script src=\"/x.js#https://evil.example\">"},
            {"1", "<script src=\"/x.js\" nonce=\"copied\">"},
            {"0", "<script nonce=\"copied\" src=\"https://evil.example/x.js\">"},
            {"0", "<script NONCE=\"copied\">a</script>"},
            {"0", "<script nonce\u0000=\"copied\">a</script>"},
            {"0", "<script src=\"\">x</script>"},
            {"0", "<script src>x</script>"},
            {"1", "<script src=\"\u0000/x.js\">"},                                // same origin either way: the URL
            {"1", "<script src=\"/x.js\u0000\">"},                                // parser trims C0, HTML makes U+FFFD
            {"1", "<!--><script src=\"/x.js\">"},                                 // (was skipped)
            {"1", "<!---><script src=\"/x.js\">"},
            {"1", "<!-- --!><script src=\"/x.js\">"},
            {"0", "<![CDATA[<script src=\"/x.js\">]]>"},                         // the "]]>" is its text now
            {"1", "<template><script src=\"/x.js\"></script></template>"},
            {"1", "<noscript><script src=\"/x.js\"></script></noscript>"},
            {"1", "<plaintext><script src=\"/x.js\">"},
            {"1", "<div title=x><script src=\"/x.js\">"},
            {"0", "<div title='x\"><script src=\"/x.js\">'>"},
            {"0", "<svg><title><script src=\"/x.js\"></script></title></svg>"},
            {"0", "<svg><style><script src=\"/x.js\"></script></style></svg>"},
            {"0", "<svg><desc></desc><script href=\"https://evil.example/x.js\"/></svg>"},
            {"1", "<math><mtext><script src=\"/x.js\">"},
            {"1", "<base href=\"/sub/\"><script src=\"x.js\">"},
            {"0", "<base href=\"https://evil.example/\"><script src=\"x.js\">"},
            {"0", "<base href=\"https://evil.example/\"><script src=\"/x.js\">"},  // "/x.js" is on evil.example
            {"0", "<base target=_blank><base href=\"https://evil.example/\"><script src=\"x.js\">"},
            {"0", "<template><base href=\"https://evil.example/\"></template><script src=\"x.js\">"},
            {"0", "<script src=\"https://site.example/x.js\"><script src=\"https://evil.example/y.js\"></script>"},
            {"0", "<script src=\"/a.js\"><!--</script><script src=\"https://evil.example/y.js\">--></script>"},
            {"0", "<script src=\"/a.js\"><!--<script></script><script src=\"https://evil.example/y.js\">--></script>"},
            {"1", "<script src=\"/a.js\"></script ><script src=\"https://evil.example/y.js\"></script>"},
            {"1", "<script src=\"/a.js\"></script/><script src=\"https://evil.example/y.js\"></script>"},
            {"0", "<script src=\"/a.js\"></SCRIPT ><script src=\"https://evil.example/y.js\"></script>"},
            {"0", "<script src=\"/a.js\"></scripts><script src=\"https://evil.example/y.js\"></script>"},
            {"0", "<script src=\"/x.js\" href=\"https://evil.example/\">"},
            {"0", "<script src=\"https://evil.example/\" href=\"/\">"},
            {"0", "<script src=\"http://site.example/x.js\">"},
            {"1", "<script src=\"HTTPS://site.example/x.js\">"},
            {"1", "<script src=\"https://site.example/x.js\" src=\"\">"},
            {"0", "<script src=\"ftp://site.example/x.js\">"},
            {"1", "<script src=\"wss://site.example/x.js\">"},                    // 'self' secure upgrade; never fetches
            {"1", "<script src=\"https://site.example:0443/x.js\">"},
            {"0", "<script src=\"https://site.example:443:/x.js\">"},
            {"1", "<script src=\"https://site.example\t/x.js\">"},
            {"1", "<script src=\"https://site\t.example/x.js\">"},
            {"0", "<script src=\"https://site.example /x.js\">"},
            {"1", "<script src=\"https://site.example?.evil.example/x.js\">"},
            {"1", "<script src=\"https://site.example#@evil.example/x.js\">"},
            {"0", "<script src=\"https://site.example%00@evil.example/x.js\">"},
            {"0", "<script src=\"https://%73ite.example/x.js\">"},
            {"0", "<script src=\"https://[::1]/x.js\">"},
            {"1", "<script src=\"https://site.example/x.js\" type=\"speculationrules\">"},
            {"1", "<script src=\"/x.js\" type=\"text/javascript;\">"},
        };
        StringBuilder failures = new StringBuilder();
        for (String[] c : cases) {
            String html = c[1].contains("</script") ? c[1] : c[1] + "</script>";
            String out = ScriptNonceStamper.stamp(html, N, allowlist, doc);
            int count = out.split("nonce=\"" + N + "\"", -1).length - 1;
            if (count != Integer.parseInt(c[0])) {
                failures.append("\n  expected ").append(c[0]).append(", got ").append(count).append(": ").append(c[1]);
            }
        }
        assertEquals(84, cases.length);
        assertEquals("", failures.toString());
    }

    // ---- documented limits ------------------------------------------------------------------------------------

    /** An unterminated tag stops the scan: the remainder is copied unchanged (never corrupted, never stamped). */
    @Test
    void limitAnUnterminatedTagStopsTheScan() {
        String in = "<script src=/a.js></script><div class=\"x><script src=/b.js></script>";
        assertEquals(stamped(" src=/a.js></script><div class=\"x><script src=/b.js></script>"), stamp(in));
    }

    /**
     * The script-data escape states are followed (this used to be a documented limit). In the
     * "double escaped" state ({@code <!--} then {@code <script}) a {@code </script>} does not close the element, so
     * below is ONE script — with text, so not stamped — and no second script exists; {@code -->} ends the escape.
     */
    @Test
    void theScriptDataEscapeStatesAreFollowed() {
        String one = "<script src=/a.js><!--<script></script><script src=/b.js></script>";
        assertSame(one, stamp(one));
        // "<!--" alone escapes, but </script> still closes: the next script is real.
        assertEquals("<script src=/a.js><!--</script>" + stamped(" src=/b.js></script>"),
                stamp("<script src=/a.js><!--</script><script src=/b.js></script>"));
        // "-->" returns to plain script data, from the double-escaped state too: the second </script> closes.
        assertEquals("<script>x<!--<script>--></script>" + stamped(" src=/b.js></script>"),
                stamp("<script>x<!--<script>--></script><script src=/b.js></script>"));
        // The double escape needs "<script" followed by whitespace, '/' or '>': "<scripts>" does not enter it.
        assertEquals("<script>x<!--<scripts></script>" + stamped(" src=/b.js></script>"),
                stamp("<script>x<!--<scripts></script><script src=/b.js></script>"));
        // After a double escape ends at </script>, a further </script> closes the element.
        assertEquals("<script>x<!--<script></script></script>" + stamped(" src=/b.js></script>"),
                stamp("<script>x<!--<script></script></script><script src=/b.js></script>"));
    }

    /**
     * A bogus comment ({@code <?…>}, {@code <!…>}, {@code </} + non-letter) ends at the first
     * {@code >}, and an end tag's quoted attribute values are skipped — so a {@code <!--} inside either is not a
     * comment, and the same-origin script after it is stamped (the scanner used to read a comment there and leave every
     * later script unstamped).
     */
    @Test
    void bogusCommentsAndEndTagAttributesDoNotHideLaterScripts() {
        for (String prefix : new String[] {"<?x <!-- y>", "<!x <!-- y>", "<!- <!-- y>", "</3 <!-- y>", "</ <!--y>",
                "</div title=\"<!--\">", "<!DOCTYPE x \"<!--\">", "<![CDATA[<!--]]>", "<svg><![CDATA[<!--]]></svg>",
                "<script>a</script x=\">\"<!--\">"}) {
            assertEquals(prefix + stamped(" src=\"/ok.js\"></script>"), stamp(prefix + "<script src=\"/ok.js\"></script>"),
                    prefix);
        }
        // ...and the DOCTYPE ends at the first '>' even inside quotes: here the comment is real.
        String swallowed = "<!DOCTYPE html PUBLIC \"a>b<!--\"><script src=\"/ok.js\"></script>";
        assertSame(swallowed, stamp(swallowed));
    }

    /** Where the scanner cannot tell what the browser sees, it stops stamping: nothing after that point (fail closed). */
    @Test
    void afterAPointTheScannerCannotFollowNothingIsStamped() {
        String in = "<script src=/a.js></script><svg><style><a title=\"</style><!--\"></svg><p>"
                + "<script src=/b.js></script>";
        assertEquals(stamped(" src=/a.js></script><svg><style><a title=\"</style><!--\"></svg><p>"
                + "<script src=/b.js></script>"), stamp(in));
    }

    /** An unterminated comment swallows the rest, exactly as a browser's parser does. */
    @Test
    void limitAnUnterminatedCommentSwallowsTheRest() {
        String in = "<!-- open <script src=/a.js></script>";
        assertSame(in, stamp(in));
    }
}
