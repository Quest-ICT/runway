package mx.quest.runway.head;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.web.CspScriptAllowlist;

/**
 * Adds the public page's nonce to the {@code <script>} tags of a finished HTML document <b>that the host-allowlist
 * policy would have let run</b> — the body half of the public-page CSP nonce (the header half is
 * {@code CspFilter}). It runs at the response edge, after the page and its widgets rendered and after head
 * contributors were spliced in, so a script from any source — the tenant's templates, a plugin
 * {@code ContentWidget}, a {@code HeadContributor} — is judged the same way, without any of them knowing a nonce
 * exists (no SPI change).
 *
 * <h2>The invariant: never loosen what the allowlist blocked</h2>
 * Under {@code 'strict-dynamic'} the browser ignores {@code 'self'} and the hosts, so a stamp is the <b>only</b> way
 * a parser-inserted script runs. Stamping every script would turn an injected inline {@code <script>} — or a
 * {@code <script src>} on a host nobody allowlisted — from blocked into running. So a script is stamped only when
 * {@link CspScriptAllowlist} says the previous policy allowed it:
 * <ul>
 *   <li><b>external</b> — it has a {@code src} attribute, its text is blank, and every URL it names ({@code src},
 *       and {@code href}/{@code xlink:href} if present) is allowed: same-origin ({@code 'self'}, relative URLs
 *       included) or matching a configured host ({@code runway.csp.script-src}, {@code extra-hosts}, plugin
 *       {@code CspHostContributor} hosts such as reCAPTCHA's). Only {@code src} counts: an HTML {@code <script href>}
 *       runs its <em>text</em> inline, and inside SVG a {@code <script src>} ignores {@code src} and runs its text —
 *       hence "text blank" (see {@link #allowed}). So an SVG script that loads through
 *       {@code href} alone is not stamped, and under {@code 'strict-dynamic'} it is blocked (fail closed);</li>
 *   <li><b>inline</b> (any other executable script): only when the configured policy carries an effective
 *       {@code 'unsafe-inline'} — never with the shipped default, so inline scripts that were blocked stay blocked.
 *       The kernel emits no inline script on public pages ({@code NoInlineScriptGuardTest}); JSON-LD is a data
 *       block and needs no nonce.</li>
 * </ul>
 * Scripts the browser does not execute (data blocks: {@code application/ld+json}, {@code text/template}, any
 * non-JavaScript type) are never touched. Trust then flows only from stamped scripts: what they insert with
 * {@code document.createElement('script')} runs under {@code 'strict-dynamic'} (reCAPTCHA's loader fetching its
 * {@code gstatic} chunks), while parser-inserted markup ({@code document.write}) needs a stamp it cannot get.
 *
 * <h2>A nonce already in the markup is never trusted</h2>
 * The page nonce is visible to anyone who loads the page and stable per content version, so markup injected into a
 * page (a reflected query value) could carry it. The kernel puts no nonce on a public page itself, so every
 * {@code nonce} attribute in the body is foreign: an allowed script gets the page nonce inserted <b>first</b> (an
 * HTML parser keeps the first of duplicate attributes, so it wins); a script that is <em>not</em> allowed but carries
 * a {@code nonce} gets {@code nonce=""} inserted first, which matches no source — so a copied nonce never runs it.
 * That override reaches only the {@code <script>} tags this scanner sees. A copy elsewhere — inside an
 * {@code <iframe srcdoc>} document (which inherits the page's policy, nonce included), or in a corner where the
 * browser and this scanner tokenize differently — is caught by {@link #carriesNonce}: the edge checks the unstamped
 * body for the page's own nonce, in any encoding, and withdraws the nonce for that response (the page then gets the
 * host-allowlist policy, exactly what it had before {@code 'strict-dynamic'}).
 *
 * <h2>{@code <base href>}</h2>
 * Relative URLs resolve against the first {@code <base href>}. A same-origin base is followed; after a cross-origin
 * one, relative script URLs are not stamped at all (fail closed — {@code base-uri} blocks such a base in enforce mode
 * anyway).
 *
 * <h2>How it reads the HTML (and why not a regex)</h2>
 * A small forward scanner that follows the WHATWG tokenizer (HTML §13.2.5) wherever the token stream does not depend
 * on the tree, and refuses to guess where it does ({@link #walk}). It walks tag by tag, skipping quoted attribute
 * values (so {@code <div title="<script>">} is not a script) — in end tags too ({@code </div title="<!--">});
 * comments ({@code <!-- <script> -->} is not a script; {@code <!-->} and {@code <!--->} are complete empty comments and
 * {@code --!>} closes one); <b>bogus comments</b>, which end at the first {@code >}: {@code <?…>}, {@code <!…>} that
 * is not {@code <!--} (DOCTYPE included, quotes or not), {@code </} followed by a non-letter; and the raw-text/RCDATA
 * contents of {@code script}, {@code style}, {@code textarea}, {@code title}, {@code xmp}, {@code iframe},
 * {@code noembed} and {@code noframes} up to their closing tag (whose attributes are skipped like any end tag's) — a
 * script's following the script-data escape states, where {@code <!--<script>} keeps the element open past a
 * {@code </script>} — so a {@code "<script>"} inside a JavaScript string or a CSS comment is never mistaken for a
 * tag. Attribute names are ASCII-lower-cased and the first occurrence wins, as in the parser; values are HTML-decoded
 * ({@link #decodeAttribute}) before a URL is judged, so {@code src="https&#58;//evil.example/x.js"} is seen the way
 * the browser sees it. Everything it does not rewrite is copied byte-for-byte. The same scan drives
 * {@link ScriptPreloadStripper}, so the stamp and the preload removal can never disagree about what is a tag.
 *
 * <p><b>Where the tree decides.</b> Whether a raw-text start tag switches the tokenizer at all (not inside
 * {@code <svg>}/{@code <math>} outside their HTML integration points, not where the tree builder ignores it — in
 * {@code <select>}, {@code <frameset>}, {@code <template><col>}), whether {@code <![CDATA[} is a CDATA section (only
 * in foreign content) and whether {@code <noscript>} content is markup (scripting off) or raw text (scripting on) all
 * depend on the tree, which the scanner does not build. Once a start tag that can make any of this matter has
 * appeared, the scanner reads each such construct one way and checks that every other reading rejoins it at the
 * same token boundary; a {@code <noscript>} or CDATA is checked always. Where the readings agree (every real page),
 * nothing changes. Where they do not, the walk stops and says so: the stamp leaves every later script unstamped, and
 * {@link ScriptPreloadStripper} reports the body hidden, so the edge withdraws the nonce and the page gets the
 * host-allowlist policy.
 *
 * <h2>Known limits (documented, tested)</h2>
 * <ul>
 *   <li>A script inside an SVG/MathML HTML integration point ({@code <svg><title><script src>}) sits in what the
 *       scanner reads as raw text: it is not stamped (fail closed: blocked under {@code 'strict-dynamic'}); a copied
 *       nonce there is caught by {@link #carriesNonce}.</li>
 *   <li>A malformed, unterminated tag ({@code <script} with no {@code >} before end of input) stops the scan; the
 *       remainder is copied unchanged (so nothing after it is stamped) — the browser drops it the same way.</li>
 *   <li>{@code <noscript>} content is scanned as markup (the scripting-off reading); stamping a script there is
 *       harmless (it never runs with scripting enabled).</li>
 *   <li>{@code <plaintext>} makes the rest of the document text to the browser; the scanner reads on as markup,
 *       which can only stamp text that never runs.</li>
 * </ul>
 */
public final class ScriptNonceStamper {

    /** Elements whose content is raw text / RCDATA: no tags inside until the matching close tag. */
    private static final Set<String> RAW_TEXT = Set.of("script", "style", "textarea", "title", "xmp", "iframe",
            "noembed", "noframes");

    /** The JavaScript MIME type essences (WHATWG MIME Sniffing §4.6) plus the other script-src-governed types. */
    private static final Set<String> EXECUTABLE_TYPES = Set.of(
            "", "module", "importmap", "speculationrules",
            "application/ecmascript", "application/javascript", "application/x-ecmascript",
            "application/x-javascript", "text/ecmascript", "text/javascript", "text/javascript1.0",
            "text/javascript1.1", "text/javascript1.2", "text/javascript1.3", "text/javascript1.4",
            "text/javascript1.5", "text/jscript", "text/livescript", "text/x-ecmascript", "text/x-javascript");

    /**
     * The attributes the scanner records (first occurrence wins); everything else is skipped. {@code rel}, {@code as}
     * and {@code srcdoc} are for {@link ScriptPreloadStripper}.
     */
    private static final Set<String> RECORDED = Set.of("nonce", "type", "src", "href", "xlink:href", "rel", "as",
            "srcdoc");

    /** The attributes through which a script names an external URL (SVG uses href / xlink:href). */
    private static final String[] URL_ATTRIBUTES = {"src", "href", "xlink:href"};

    /**
     * Every named character reference whose expansion is ASCII (WHATWG named character references table). All others
     * expand to non-ASCII characters, which can never turn a relative URL absolute or change a host this code
     * accepts; they are left as written, and a {@code &} left in a host makes the URL unclassifiable (not stamped).
     */
    private static final Map<String, String> ASCII_ENTITIES = Map.ofEntries(
            Map.entry("Tab", "\t"), Map.entry("NewLine", "\n"), Map.entry("excl", "!"), Map.entry("quot", "\""),
            Map.entry("QUOT", "\""), Map.entry("num", "#"), Map.entry("dollar", "$"), Map.entry("percnt", "%"),
            Map.entry("amp", "&"), Map.entry("AMP", "&"), Map.entry("apos", "'"), Map.entry("lpar", "("),
            Map.entry("rpar", ")"), Map.entry("ast", "*"), Map.entry("midast", "*"), Map.entry("plus", "+"),
            Map.entry("comma", ","), Map.entry("period", "."), Map.entry("sol", "/"), Map.entry("colon", ":"),
            Map.entry("semi", ";"), Map.entry("lt", "<"), Map.entry("LT", "<"), Map.entry("equals", "="),
            Map.entry("gt", ">"), Map.entry("GT", ">"), Map.entry("quest", "?"), Map.entry("commat", "@"),
            Map.entry("lsqb", "["), Map.entry("lbrack", "["), Map.entry("bsol", "\\"), Map.entry("rsqb", "]"),
            Map.entry("rbrack", "]"), Map.entry("Hat", "^"), Map.entry("lowbar", "_"), Map.entry("UnderBar", "_"),
            Map.entry("grave", "`"), Map.entry("DiacriticalGrave", "`"), Map.entry("lcub", "{"),
            Map.entry("lbrace", "{"), Map.entry("verbar", "|"), Map.entry("vert", "|"),
            Map.entry("VerticalLine", "|"), Map.entry("rcub", "}"), Map.entry("rbrace", "}"),
            Map.entry("fjlig", "fj"));

    /** ASCII legacy references a parser expands without {@code ;} (in an attribute: unless {@code [=A-Za-z0-9]} follows). */
    private static final Set<String> LEGACY_NO_SEMICOLON = Set.of("amp", "AMP", "lt", "LT", "gt", "GT", "quot", "QUOT");

    private ScriptNonceStamper() {
    }

    /**
     * Returns {@code html} with {@code nonce="<nonce>"} inserted right after the tag name of every executable
     * {@code <script>} the allowlist policy allowed, and {@code nonce=""} inserted after the name of every executable
     * script that is <em>not</em> allowed but carries a {@code nonce} attribute of its own. The same instance is
     * returned when nothing needed rewriting, or when any argument is {@code null}/empty.
     *
     * @param nonce     a base64url token (CSP- and attribute-safe; it is inserted verbatim)
     * @param allowlist the host-allowlist {@code script-src} the page had before {@code 'strict-dynamic'}
     * @param document  the document's URL (scheme, host, port, path) — what {@code 'self'} and relative URLs mean
     */
    public static String stamp(String html, String nonce, CspScriptAllowlist allowlist, URI document) {
        if (html == null || nonce == null || nonce.isEmpty() || allowlist == null || document == null) {
            return html;
        }
        Edits edits = new Edits(html);
        // A false walk stops at the first point where the scanner cannot tell what the browser sees: scripts after it
        // stay unstamped (blocked under 'strict-dynamic' — fail closed). The edge never serves such a body under the
        // nonce anyway: ScriptPreloadStripper reports it hidden first, and the nonce is withdrawn.
        walk(html, document, document, 0, false, (name, start, nameEnd, tag, contentEnd, base, depth) -> {
            if (depth != 0 || !"script".equals(name) || !isExecutable(tag)) {
                return; // a tag that exists only if raw text is markup is never stamped
            }
            if (allowed(tag, html.substring(tag.end(), contentEnd), allowlist, base, document)) {
                edits.insert(nameEnd, " nonce=\"" + nonce + "\"");
            } else if (tag.attributes().containsKey("nonce")) {
                edits.insert(nameEnd, " nonce=\"\""); // first wins: a nonce copied into the markup can never authorize it
            }
        });
        return edits.result();
    }

    /**
     * What {@link #walk} reports for each start tag it reaches in markup (never inside a comment, a bogus comment, a
     * quoted attribute value or an end tag).
     */
    @FunctionalInterface
    interface TagVisitor {

        /**
         * @param name       the ASCII-lower-cased tag name
         * @param start      the index of the tag's {@code <}
         * @param nameEnd    the index just past the tag name (where an attribute can be inserted)
         * @param tag        the tag's end and its {@link #RECORDED} attributes (raw values, first occurrence wins)
         * @param contentEnd for a raw-text element ({@code script}, {@code style}, {@code title}, …) the index of its
         *                   closing tag, or the end of the region — its content is {@code [tag.end(), contentEnd)};
         *                   {@code -1} for any other element
         * @param base       what relative URLs resolve against at this point: the initial base, a same-origin
         *                   {@code <base href>} seen earlier (or on this very tag), or {@code null} after a
         *                   cross-origin one
         * @param depth      how many raw-text contents deep this tag sits, counted from the walk's starting depth:
         *                   the starting depth for a tag of the document's own token stream; deeper for a tag that
         *                   exists only when an enclosing raw-text element's content is read as markup (the reading
         *                   of the browser when that element sits in SVG/MathML, or a conservative one) — such a tag
         *                   must never be rewritten (it may be inside a JavaScript string)
         */
        void visit(String name, int start, int nameEnd, Tag tag, int contentEnd, URI base, int depth);
    }

    /**
     * The forward scan shared by the nonce stamp and {@link ScriptPreloadStripper} — one tokenizer, so the two can
     * never disagree about what is a tag. Equivalent to {@code walk(html, document, base, 0, true, visitor)}.
     *
     * @return whether the scan followed the browser's tokenizer to the end of {@code html} (see
     *         {@link #walk(String, URI, URI, int, boolean, TagVisitor)})
     */
    static boolean walk(String html, URI document, URI base, TagVisitor visitor) {
        return walk(html, document, base, 0, true, visitor);
    }

    /**
     * Walks {@code html} tag by tag as the class comment describes, follows the first {@code <base href>} (only when
     * same-origin with {@code document}), and calls {@code visitor} once per start tag, in document order.
     *
     * <p><b>The contract — follow the tokenizer or say so.</b> Where the token stream does not depend on the tree
     * (comments, bogus comments, DOCTYPE, tags and their quoted attributes, end tags, the raw-text/RCDATA/script-data
     * states once entered) the scan follows the WHATWG tokenizer exactly. Where it does depend on the tree — whether
     * a raw-text start tag switches the tokenizer at all (not in SVG/MathML, nor where the tree builder ignores the
     * tag), whether {@code <![CDATA[} is a CDATA section, whether {@code <noscript>} content is markup (scripting off)
     * or raw text (scripting on) — the scan follows one reading and <b>checks that every other reading rejoins it</b>
     * at the same token boundary. When it cannot show that, or a {@code <base href>} sits where the tree builder may
     * not honour it, or raw text nests deeper than {@value #MAX_NESTING} where it might be markup, it stops and
     * returns {@code false}: from there on it cannot know what the browser sees, and the caller must fail closed.
     * An unterminated tag, comment or raw-text element at the end of the input is not uncertainty — the browser drops
     * or swallows the rest the same way — and the walk returns {@code true}.
     *
     * @param document    the document's URL — what {@code 'self'} means
     * @param base        what relative URLs resolve against before any {@code <base>} in {@code html}: the document
     *                    URL for a page; {@code null} means a cross-origin base is already in force
     * @param depth       the depth reported for the document's own tags (an {@code <iframe srcdoc>} document read by
     *                    the stripper passes its nesting level, so one bound covers both kinds of nesting)
     * @param readRawText whether to read every raw-text content as markup too and report its tags at a greater depth
     *                    (the stripper's conservative reading); when {@code false}, contents are read only where the
     *                    browser itself might read them as markup, which the uncertainty check needs anyway
     * @return {@code true} when the scan followed the browser's tokenizer to the end; {@code false} when it stopped
     *         at a point where it cannot (tags before that point were visited, none after it)
     */
    static boolean walk(String html, URI document, URI base, int depth, boolean readRawText, TagVisitor visitor) {
        Scan scan = new Scan(html, document, visitor, readRawText);
        return scan.region(0, html.length(), base, false, depth, false) != End.UNCERTAIN;
    }

    /** Bound on how many raw-text contents deep the walk reads as markup (each level re-reads its content once). */
    static final int MAX_NESTING = 16;

    /**
     * Start tags after which a raw-text start tag may no longer switch the tokenizer, or {@code <![CDATA[} may open a
     * CDATA section: {@code svg}/{@code math} open foreign content (where {@code title}, {@code style}, {@code script}
     * … are ordinary elements, except at HTML integration points); the tree builder <em>ignores</em> raw-text start
     * tags inside {@code select} and {@code frameset}, and inside {@code template} after a {@code col}/{@code colgroup}
     * (the "in column group" insertion mode with a template as current node). The walk does not build a tree, so once
     * any of these has opened, every later raw-text element and CDATA must pass the agreement check.
     */
    private static final Set<String> TREE_DEPENDENT = Set.of("svg", "math", "select", "frameset", "template", "col",
            "colgroup");

    /** How the reading of a region ended. */
    private enum End {
        /** In the data state at the region's end: every reading agrees there. */
        CLEAN,
        /** Inside a construct (tag, comment, raw text …) that would continue past the region's end. */
        OPEN,
        /** At a point where the readings disagree or cannot be checked — nothing after it was visited. */
        UNCERTAIN
    }

    /** One walk: the input, the visitor, and the tree-dependence flag (sticky across nested readings). */
    private static final class Scan {

        private final String html;
        private final URI document;
        private final TagVisitor visitor;
        private final boolean readRawText;
        private boolean treeDependent;

        Scan(String html, URI document, TagVisitor visitor, boolean readRawText) {
            this.html = html;
            this.document = document;
            this.visitor = visitor;
            this.readRawText = readRawText;
        }

        /**
         * Reads {@code html[from, limit)} in the data state.
         *
         * @param strict whether this region is a reading the browser may really take (a raw-text content that may
         *               be markup) — then a {@code <base href>} in it is uncertainty; {@code false} for the document
         *               itself and for the stripper's conservative readings
         */
        End region(int from, int limit, URI base, boolean baseSeen, int depth, boolean strict) {
            // Positions where another reading of an earlier construct re-enters the data state; this reading must be
            // in the data state there too (a <noscript> close with scripting on, the end of a CDATA section).
            java.util.TreeSet<Integer> rejoin = null;
            boolean otherReadingOpen = false; // another reading runs past `limit` (only fine at the very end)
            int i = from;
            while (true) {
                int lt = indexOf(html, '<', i, limit);
                if (rejoin != null) {
                    rejoin.headSet(lt < 0 ? limit : lt, true).clear(); // [i, lt] is text: the data state
                }
                if (lt < 0) {
                    return otherReadingOpen ? End.OPEN : End.CLEAN;
                }
                if (lt + 1 >= limit) {
                    return End.OPEN; // a '<' at the end: the next character decides
                }
                char c = html.charAt(lt + 1);
                int next;            // where the data state resumes after this construct; -1: it runs to `limit`
                Tag tag = null;      // a start tag to visit
                String name = null;
                int nameEnd = -1;
                int contentEnd = -1; // a raw-text element's close tag (or `limit`)
                if (c == '!') {
                    if (startsWith(html, "--", lt + 2, limit)) {
                        next = commentEnd(html, lt + 4, limit);
                    } else {
                        next = bogusCommentEnd(html, lt + 2, limit); // DOCTYPE ends at the first '>' too
                        if (treeDependent && startsWith(html, "[CDATA[", lt + 2, limit)) {
                            // In foreign content this is a CDATA section, which ends at "]]>": that reading must
                            // rejoin this one (a bogus comment to the first '>') at a token boundary.
                            int cdata = indexOf(html, "]]>", lt + 9, limit);
                            if (cdata < 0) {
                                otherReadingOpen = true;
                            } else {
                                rejoin = add(rejoin, cdata + 3);
                            }
                        }
                    }
                } else if (c == '?') {
                    next = bogusCommentEnd(html, lt + 1, limit);
                } else if (c == '/') {
                    if (lt + 2 >= limit) {
                        return End.OPEN;
                    }
                    char d = html.charAt(lt + 2);
                    if (d == '>') {
                        next = lt + 3; // "</>": dropped
                    } else if (isAsciiLetter(d)) {
                        Tag end = scanAttributes(html, tagNameEnd(html, lt + 2, limit), limit); // end tags have them
                        next = end == null ? -1 : end.end();
                    } else {
                        next = bogusCommentEnd(html, lt + 2, limit);
                    }
                } else if (isAsciiLetter(c)) {
                    nameEnd = tagNameEnd(html, lt + 1, limit);
                    name = asciiLower(html.substring(lt + 1, nameEnd));
                    tag = scanAttributes(html, nameEnd, limit);
                    if (tag == null) {
                        next = -1; // an unterminated tag: the browser drops it and the rest
                    } else if (RAW_TEXT.contains(name)) {
                        // Raw-text elements ignore a self-closing slash in HTML (`<script/>` still opens one).
                        contentEnd = "script".equals(name) ? scriptDataEnd(html, tag.end(), limit)
                                : closingTagStart(html, tag.end(), name, limit);
                        Tag close = contentEnd >= limit ? null
                                : scanAttributes(html, contentEnd + 2 + name.length(), limit);
                        next = close == null ? -1 : close.end();
                    } else {
                        next = tag.end();
                    }
                } else {
                    i = lt + 1; // a '<' in text
                    continue;
                }
                int end = next < 0 ? limit : next;
                if (rejoin != null && !rejoin.subSet(lt, false, end, false).isEmpty()) {
                    return End.UNCERTAIN; // another reading needs the data state inside this construct
                }
                if (tag != null) {
                    if ("base".equals(name) && !baseSeen && tag.attributes().containsKey("href")) {
                        // The first <base href> IN THE TREE sets the base: one in foreign content, in a template, in
                        // content another reading calls text, may not be it — and a later one would be.
                        if (strict || treeDependent || rejoin != null || otherReadingOpen) {
                            return End.UNCERTAIN;
                        }
                        baseSeen = true;
                        base = CspScriptAllowlist.sameOriginBase(decodeAttribute(tag.attributes().get("href")),
                                document);
                    }
                    if (TREE_DEPENDENT.contains(name)) {
                        treeDependent = true;
                    }
                    visitor.visit(name, lt, nameEnd, tag, contentEnd < 0 ? -1 : Math.min(contentEnd, limit), base,
                            depth);
                    if (contentEnd >= 0 && !readContent(tag.end(), end, next >= 0, base, baseSeen, depth)) {
                        return End.UNCERTAIN;
                    }
                    if ("noscript".equals(name)) {
                        // Scripting on, the content is raw text to the first </noscript ...>: that reading must
                        // rejoin this one (markup, the scripting-off reading) right after the close tag.
                        int close = closingTagStart(html, tag.end(), "noscript", limit);
                        Tag closeTag = close >= limit ? null
                                : scanAttributes(html, close + 2 + "noscript".length(), limit);
                        if (closeTag == null) {
                            otherReadingOpen = true;
                        } else {
                            rejoin = add(rejoin, closeTag.end());
                        }
                    }
                }
                if (next < 0) {
                    return End.OPEN;
                }
                i = next;
            }
        }

        /**
         * Reads a raw-text element's content (with its close tag, {@code [from, to)}) as markup: where the tree may
         * make it markup ({@link #TREE_DEPENDENT}), that reading must end in the data state exactly where the raw
         * text ends; otherwise it is only the stripper's conservative reading, whose outcome does not matter.
         *
         * @param closed whether the element has a close tag (otherwise it runs to the region's end)
         * @return {@code false} when the readings disagree or nest too deep to check
         */
        private boolean readContent(int from, int to, boolean closed, URI base, boolean baseSeen, int depth) {
            boolean mayBeMarkup = treeDependent;
            if (!mayBeMarkup && !readRawText) {
                return true;
            }
            if (depth + 1 > MAX_NESTING) {
                return !mayBeMarkup; // too deep to check: only a problem where the browser may read it as markup
            }
            if (!mayBeMarkup) {
                new Scan(html, document, visitor, true).region(from, to, base, baseSeen, depth + 1, false);
                return true;
            }
            End reading = region(from, to, base, baseSeen, depth + 1, true);
            return reading == End.CLEAN || (reading == End.OPEN && !closed);
        }
    }

    private static java.util.TreeSet<Integer> add(java.util.TreeSet<Integer> set, int position) {
        java.util.TreeSet<Integer> result = set == null ? new java.util.TreeSet<>() : set;
        result.add(position);
        return result;
    }

    /** The index just past a bogus comment (or DOCTYPE) whose text starts at {@code from}: after the next {@code >}. */
    private static int bogusCommentEnd(String html, int from, int limit) {
        int gt = indexOf(html, '>', from, limit);
        return gt < 0 ? -1 : gt + 1;
    }

    /** The end of a tag name that starts at {@code from} (whitespace, {@code /} or {@code >} ends it). */
    private static int tagNameEnd(String html, int from, int limit) {
        int i = from;
        while (i < limit && !isTagNameEnd(html.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int indexOf(String html, char c, int from, int limit) {
        int at = html.indexOf(c, from);
        return at < 0 || at >= limit ? -1 : at;
    }

    private static int indexOf(String html, String s, int from, int limit) {
        int at = html.indexOf(s, from);
        return at < 0 || at + s.length() > limit ? -1 : at;
    }

    private static boolean startsWith(String html, String prefix, int at, int limit) {
        return at + prefix.length() <= limit && html.startsWith(prefix, at);
    }

    /**
     * Collects insertions and removals at increasing positions of one text and builds the result lazily: when nothing
     * was edited, {@link #result()} returns the original instance, so an untouched body passes through unchanged.
     */
    static final class Edits {

        private final String text;
        private StringBuilder out; // allocated on the first edit
        private int copied;        // text[0, copied) is already in `out`

        Edits(String text) {
            this.text = text;
        }

        /** Inserts {@code insertion} at {@code at} (never before the previous edit's end). */
        void insert(int at, String insertion) {
            builder().append(text, copied, at).append(insertion);
            copied = at;
        }

        /**
         * Removes the tag at {@code [from, to)} (never before the previous edit's end) without letting its neighbours
         * join into a new token. A start tag the scanner saw sits in the data state, so the text kept before it can
         * end in an unfinished construct only one way: a lone {@code <} (text only because the tag's {@code <}
         * followed it) — which, spliced to what follows, could open a tag, an end tag or a comment ({@code <} +
         * {@code !--…}) that ends somewhere else than anything did before, turning attribute text into markup. And a
         * {@code ]} could complete a {@code ]]>} that ends a CDATA section early in the foreign reading. In those two
         * cases an empty comment {@code <!---->} — a complete token that combines with nothing — takes the tag's place.
         */
        void removeTag(int from, int to) {
            StringBuilder kept = builder().append(text, copied, from);
            char last = kept.isEmpty() ? '\0' : kept.charAt(kept.length() - 1);
            if (last == '<' || last == ']') {
                kept.append("<!---->");
            }
            copied = to;
        }

        String result() {
            return out == null ? text : out.append(text, copied, text.length()).toString();
        }

        private StringBuilder builder() {
            if (out == null) {
                out = new StringBuilder(text.length() + 64);
            }
            return out;
        }
    }

    /** Bound on decoding rounds in {@link #carriesNonce}; deeper nesting is treated as carrying it (fail closed). */
    private static final int MAX_DECODE_ROUNDS = 16;

    /**
     * Whether {@code html} already contains {@code nonce} — literally or behind any number of rounds of character
     * reference decoding. The edge calls it on the unstamped body: the kernel writes no nonce on a public page, and a
     * 132-bit value does not appear by chance, so a hit means markup copied the published nonce into the page (only
     * possible through an input the ETag does not fold — otherwise the copy would change the nonce it needs). Such a
     * copy would run wherever the scanner cannot override it: inside {@code <iframe srcdoc>}, whose document inherits
     * the page's policy, nonce included (proven in Chrome 155), or in a corner where the
     * browser and this scanner tokenize differently (SVG {@code <title>}/{@code <style>}, which are not raw text in
     * foreign content). The caller then withdraws the nonce for the response.
     *
     * <p>Each round decodes the whole text the way an attribute value is decoded ({@link #decodeAttribute}); a
     * {@code srcdoc} nested <i>k</i> deep needs <i>k</i> + 1 rounds to reach the inner {@code nonce} value. A value the
     * browser compares with the nonce is a decoded attribute value, and decoding never reaches across the quote or
     * {@code =} that bounds one, so if such a value equals {@code nonce}, some round contains it. Text still changing
     * after {@value #MAX_DECODE_ROUNDS} rounds counts as a hit (no real page nests that deep).
     *
     * @return {@code false} for a {@code null}/empty input or nonce
     */
    public static boolean carriesNonce(String html, String nonce) {
        if (html == null || nonce == null || nonce.isEmpty()) {
            return false;
        }
        String text = html;
        for (int round = 0; round <= MAX_DECODE_ROUNDS; round++) {
            if (text.contains(nonce)) {
                return true;
            }
            String decoded = decodeAttribute(text);
            if (decoded.equals(text)) {
                return false; // nothing left to decode
            }
            text = decoded;
        }
        return true; // still decoding after the bound: refuse to vouch for it
    }

    /**
     * Whether the allowlist policy would have let this (executable) script run, in whichever namespace the browser's
     * tree builder puts it. The scanner does not build a tree, so the answer must hold in HTML and in SVG alike:
     * <ul>
     *   <li>every URL the element names ({@code src}, {@code href}, {@code xlink:href}) must be allowed — an SVG
     *       script loads its {@code href} and ignores {@code src};</li>
     *   <li>with an effective {@code 'unsafe-inline'} that is enough: the old policy ran inline text as well;</li>
     *   <li>otherwise only {@code src} makes a script external. An HTML {@code <script>} has no {@code href}: the
     *       browser ignores it and runs the element's <b>text</b> — also inside SVG's HTML integration points
     *       ({@code foreignObject}, {@code desc}, {@code title}) and MathML's text ones, where the parser is back in
     *       HTML (proven in Chrome 155);</li>
     *   <li>and the element's text must be blank: inside SVG the browser ignores {@code src} and runs the text,
     *       so {@code <svg><script src="/ok.js">evil()</script>} would run {@code evil()} under the stamp
     *       (proven in Chrome 155).</li>
     * </ul>
     * The cost, fail-closed: an SVG script that names its file only through {@code href}/{@code xlink:href} is no
     * longer stamped (so {@code 'strict-dynamic'} blocks it), nor is a {@code <script src>} that also carries text.
     *
     * @param text the element's content up to its close tag (raw, as written)
     */
    private static boolean allowed(Tag tag, String text, CspScriptAllowlist allowlist, URI base, URI document) {
        for (String attribute : URL_ATTRIBUTES) {
            if (tag.attributes().containsKey(attribute)
                    && !allowlist.allowsUrl(decodeAttribute(tag.attributes().get(attribute)), base, document)) {
                return false; // every URL the element names must be allowed
            }
        }
        if (allowlist.allowsInline()) {
            return true;
        }
        return tag.attributes().containsKey("src") && isBlank(text);
    }

    /** ASCII whitespace only: running such a script does nothing, in any namespace. */
    private static boolean isBlank(String text) {
        for (int k = 0; k < text.length(); k++) {
            if (!isSpace(text.charAt(k))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isExecutable(Tag tag) {
        String type = tag.attributes().get("type");
        if (type == null) {
            return true;
        }
        String essence = decodeAttribute(type).strip().toLowerCase(Locale.ROOT);
        int semi = essence.indexOf(';');
        return EXECUTABLE_TYPES.contains(semi >= 0 ? essence.substring(0, semi).strip() : essence);
    }

    /**
     * Decodes HTML character references in an attribute value the way a browser does, for every case that can
     * produce ASCII: numeric references (with or without {@code ;}; NUL, surrogates, the C1 range and out-of-range
     * values become U+FFFD — never ASCII), the ASCII named references with {@code ;}, and the legacy
     * {@code &amp &lt &gt &quot} without one unless {@code =} or an alphanumeric follows. Any other reference is left
     * as written (a browser expands it to non-ASCII, see {@link #ASCII_ENTITIES}).
     */
    static String decodeAttribute(String raw) {
        if (raw == null || raw.indexOf('&') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int n = raw.length();
        int i = 0;
        while (i < n) {
            char c = raw.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 < n && raw.charAt(i + 1) == '#') {
                int j = i + 2;
                boolean hex = j < n && (raw.charAt(j) == 'x' || raw.charAt(j) == 'X');
                if (hex) {
                    j++;
                }
                int radix = hex ? 16 : 10;
                int digitsStart = j;
                long value = 0;
                while (j < n && Character.digit(raw.charAt(j), radix) >= 0 && raw.charAt(j) < 0x80) {
                    value = Math.min(value * radix + Character.digit(raw.charAt(j), radix), 0x110000L);
                    j++;
                }
                if (j == digitsStart) {
                    out.append(c); // "&#" with no digits stays literal
                    i++;
                    continue;
                }
                if (j < n && raw.charAt(j) == ';') {
                    j++;
                }
                int cp = (int) value;
                if (cp == 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF) || (cp >= 0x80 && cp <= 0x9F)) {
                    cp = 0xFFFD;
                }
                out.appendCodePoint(cp);
                i = j;
                continue;
            }
            int j = i + 1;
            while (j < n && isAsciiAlphanumeric(raw.charAt(j))) {
                j++;
            }
            String name = raw.substring(i + 1, j);
            if (j < n && raw.charAt(j) == ';' && ASCII_ENTITIES.containsKey(name)) {
                out.append(ASCII_ENTITIES.get(name));
                i = j + 1;
                continue;
            }
            String legacy = longestLegacyPrefix(name);
            if (legacy != null) {
                int after = i + 1 + legacy.length();
                boolean semicolon = after < n && raw.charAt(after) == ';';
                char follower = after < n ? raw.charAt(after) : ' ';
                if (semicolon || (follower != '=' && !isAsciiAlphanumeric(follower))) {
                    out.append(ASCII_ENTITIES.get(legacy));
                    i = semicolon ? after + 1 : after;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String longestLegacyPrefix(String name) {
        String best = null;
        for (String legacy : LEGACY_NO_SEMICOLON) {
            if (name.startsWith(legacy) && (best == null || legacy.length() > best.length())) {
                best = legacy;
            }
        }
        return best;
    }

    /** The parsed facts of one open tag: where it ends, and the first occurrence of each {@link #RECORDED} attribute. */
    record Tag(int end, Map<String, String> attributes) {
    }

    /**
     * Scans the attributes of a tag (start or end) whose name ended at {@code from}, honouring single/double quotes,
     * until the closing {@code >} — the tokenizer's attribute states. Returns {@code null} when {@code limit} comes
     * first. Values are raw (not yet decoded).
     */
    private static Tag scanAttributes(String html, int from, int limit) {
        int n = limit;
        int i = from;
        Map<String, String> attributes = new HashMap<>(4);
        while (i < n) {
            char c = html.charAt(i);
            if (c == '>') {
                return new Tag(i + 1, attributes);
            }
            if (isSpace(c) || c == '/') {
                i++;
                continue;
            }
            int nameStart = i;
            // An attribute name runs to whitespace, '/', '>' or '=' (a leading '=' is part of the name per spec).
            i++;
            while (i < n && !isSpace(html.charAt(i)) && html.charAt(i) != '/' && html.charAt(i) != '>'
                    && html.charAt(i) != '=') {
                i++;
            }
            String attr = asciiLower(html.substring(nameStart, i));
            int j = i;
            while (j < n && isSpace(html.charAt(j))) {
                j++;
            }
            String value;
            if (j < n && html.charAt(j) == '=') {
                j++;
                while (j < n && isSpace(html.charAt(j))) {
                    j++;
                }
                if (j >= n) {
                    return null;
                }
                char q = html.charAt(j);
                if (q == '"' || q == '\'') {
                    int close = indexOf(html, q, j + 1, n);
                    if (close < 0) {
                        return null;
                    }
                    value = html.substring(j + 1, close);
                    i = close + 1;
                } else {
                    int k = j;
                    while (k < n && !isSpace(html.charAt(k)) && html.charAt(k) != '>') {
                        k++;
                    }
                    value = html.substring(j, k);
                    i = k;
                }
            } else {
                value = "";
            }
            if (RECORDED.contains(attr)) {
                attributes.putIfAbsent(attr, value); // the first occurrence wins, as in the HTML parser
            }
        }
        return null;
    }

    /**
     * Where the {@code </name} that closes a RAWTEXT/RCDATA element whose content starts at {@code from} begins — the
     * name matched ASCII case-insensitively and followed by whitespace, {@code /} or {@code >} (the "appropriate end
     * tag") — or {@code limit} when there is none (the content then runs to the end).
     */
    private static int closingTagStart(String html, int from, String name, int limit) {
        String close = "</" + name;
        int i = from;
        while (i < limit) {
            int at = indexOfIgnoreCase(html, close, i, limit);
            if (at < 0) {
                return limit;
            }
            int after = at + close.length();
            if (after >= limit || isTagNameEnd(html.charAt(after))) {
                return at;
            }
            i = after; // "</scripts" is not "</script"
        }
        return limit;
    }

    /** The script-data states that decide where a {@code <script>} element's content ends. */
    private enum ScriptState { DATA, ESCAPED, ESCAPED_DASH, ESCAPED_DASH_DASH, DOUBLE, DOUBLE_DASH, DOUBLE_DASH_DASH }

    /**
     * Where the {@code </script} that closes a script element whose content starts at {@code from} begins, or
     * {@code limit} — following the tokenizer's script-data states (WHATWG HTML §13.2.5.15–§13.2.5.31). A
     * {@code <!--} in the script text enters the "escaped" state, where {@code </script} still closes; a
     * {@code <script} there (followed by whitespace, {@code /} or {@code >}) enters "double escaped", where it does
     * <b>not</b>, until a matching {@code </script} returns to "escaped"; {@code -->} returns to plain script data. So
     * {@code <script><!--<script></script>…</script>} is ONE element that ends at the second close tag — reading it as
     * two made the first {@code </script>} look like the end, after which a {@code <!--} hid the rest of the page from
     * the scan.
     */
    private static int scriptDataEnd(String html, int from, int limit) {
        ScriptState state = ScriptState.DATA;
        int i = from;
        while (i < limit) {
            char c = html.charAt(i);
            switch (state) {
                case DATA -> {
                    if (c == '<') {
                        if (i + 1 < limit && html.charAt(i + 1) == '/' && isScriptTag(html, i + 2, limit)) {
                            return i;
                        }
                        if (startsWith(html, "<!--", i, limit)) {
                            state = ScriptState.ESCAPED_DASH_DASH; // "<!--" (its "--" is the escape-start dash dash)
                            i += 4;
                            continue;
                        }
                    }
                }
                case ESCAPED, ESCAPED_DASH, ESCAPED_DASH_DASH -> {
                    if (c == '-') {
                        state = state == ScriptState.ESCAPED ? ScriptState.ESCAPED_DASH : ScriptState.ESCAPED_DASH_DASH;
                    } else if (c == '<') {
                        if (i + 1 < limit && html.charAt(i + 1) == '/' && isScriptTag(html, i + 2, limit)) {
                            return i; // an appropriate end tag closes the element in the escaped state
                        }
                        if (isScriptTag(html, i + 1, limit)) {
                            state = ScriptState.DOUBLE;
                            i += 1 + "script".length();
                            continue;
                        }
                        state = ScriptState.ESCAPED;
                    } else if (c == '>' && state == ScriptState.ESCAPED_DASH_DASH) {
                        state = ScriptState.DATA;
                    } else {
                        state = ScriptState.ESCAPED;
                    }
                }
                default -> { // DOUBLE, DOUBLE_DASH, DOUBLE_DASH_DASH
                    if (c == '-') {
                        state = state == ScriptState.DOUBLE ? ScriptState.DOUBLE_DASH : ScriptState.DOUBLE_DASH_DASH;
                    } else if (c == '<') {
                        if (i + 1 < limit && html.charAt(i + 1) == '/' && isScriptTag(html, i + 2, limit)) {
                            state = ScriptState.ESCAPED; // "</script" ends the double escape, not the element
                            i += 2 + "script".length();
                            continue;
                        }
                        state = ScriptState.DOUBLE;
                    } else if (c == '>' && state == ScriptState.DOUBLE_DASH_DASH) {
                        state = ScriptState.DATA;
                    } else {
                        state = ScriptState.DOUBLE;
                    }
                }
            }
            i++;
        }
        return limit;
    }

    /** Whether {@code script} (ASCII case-insensitive) starts at {@code at}, followed by whitespace, '/' or '>'. */
    private static boolean isScriptTag(String html, int at, int limit) {
        int after = at + "script".length();
        return after < limit && html.regionMatches(true, at, "script", 0, "script".length())
                && asciiOnly(html, at, after) && isTagNameEnd(html.charAt(after));
    }

    /** {@code regionMatches(ignoreCase)} also folds non-ASCII lookalikes ("ſ"); the tokenizer folds ASCII only. */
    private static boolean asciiOnly(String html, int from, int to) {
        for (int k = from; k < to; k++) {
            if (html.charAt(k) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    /**
     * The index just past the comment whose {@code <!--} ended at {@code from}, or {@code -1} when it never closes
     * before {@code limit}. As the tokenizer reads it: {@code <!-->} and {@code <!--->} are complete, empty comments,
     * and a comment closes at the first {@code -->} or {@code --!>}. (Reading {@code <!-->} as an open comment made the
     * scanner skip — and so leave unstamped — a same-origin script the browser runs.)
     */
    private static int commentEnd(String html, int from, int limit) {
        if (startsWith(html, ">", from, limit)) {
            return from + 1;
        }
        if (startsWith(html, "->", from, limit)) {
            return from + 2;
        }
        for (int k = from; k + 2 < limit; k++) {
            if (html.charAt(k) != '-' || html.charAt(k + 1) != '-') {
                continue;
            }
            if (html.charAt(k + 2) == '>') {
                return k + 3;
            }
            if (html.charAt(k + 2) == '!' && k + 3 < limit && html.charAt(k + 3) == '>') {
                return k + 4;
            }
        }
        return -1;
    }

    /** ASCII case-insensitive search ({@code needle} is lower-case) in {@code [from, limit)}, as end tags match. */
    private static int indexOfIgnoreCase(String haystack, String needle, int from, int limit) {
        int last = limit - needle.length();
        outer:
        for (int i = from; i <= last; i++) {
            for (int k = 0; k < needle.length(); k++) {
                char c = haystack.charAt(i + k);
                if (c >= 'A' && c <= 'Z') {
                    c = (char) (c + 32);
                }
                if (c != needle.charAt(k)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAsciiAlphanumeric(char c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9');
    }

    /** ASCII-only lower-casing, as the HTML tokenizer does for tag and attribute names. */
    static String asciiLower(String s) {
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                if (out == null) {
                    out = new StringBuilder(s);
                }
                out.setCharAt(i, (char) (c + 32));
            }
        }
        return out == null ? s : out.toString();
    }

    private static boolean isTagNameEnd(char c) {
        return isSpace(c) || c == '/' || c == '>';
    }

    static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }
}
