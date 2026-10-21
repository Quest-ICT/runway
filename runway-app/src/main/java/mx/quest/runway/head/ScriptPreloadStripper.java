package mx.quest.runway.head;

import java.net.URI;
import java.util.Set;
import mx.quest.runway.web.CspScriptAllowlist;

/**
 * Removes, from a public page served under the ETag-derived nonce and {@code 'strict-dynamic'}, every {@code <link>}
 * that would make the visitor's browser fetch a script-class resource from a host the host-allowlist policy did not
 * allow — the preload half of "never loosen what the allowlist blocked" ({@link ScriptNonceStamper} is the script
 * half). It runs at the response edge, only where the stamp runs (a public nonce is in force); when the nonce is
 * withdrawn the page gets the host-allowlist policy, whose {@code 'self'} + hosts already block these fetches, and
 * nothing is removed.
 *
 * <h2>Why</h2>
 * Under {@code 'strict-dynamic'} a browser ignores {@code 'self'} and the host list for any request that is not
 * "parser-inserted". A module preload is fetched as not-parser-inserted, so injected markup could make every
 * visitor's browser contact any host — a beacon channel that beta13's {@code script-src 'self'} closed. Nothing runs
 * (a preload never executes), but the visit, the visitor's address and the page it came from leak. The edge removes
 * such a link, judged by <b>the same rule as a {@code <script src>}</b> ({@link CspScriptAllowlist#allowsUrl}:
 * same-origin, or a host on the policy — {@code script-src}, {@code extra-hosts}, plugin {@code CspHostContributor}
 * hosts), with the URL read as the browser reads it (character references, {@code //host}, {@code ///host},
 * backslashes, the first same-origin {@code <base href>}). An allowed link is left exactly as written.
 *
 * <h2>Which links are judged, and why each (CSP3 "effective directive" + measured in Chrome 155)</h2>
 * Measured with a stand-in host counting requests, the same page under the public policy
 * ({@code script-src 'nonce-…' 'strict-dynamic' 'self'}) and under beta13's ({@code script-src 'self'}), both with
 * {@code default-src 'self'}:
 * <ul>
 *   <li><b>{@code rel} contains {@code modulepreload}</b> (any {@code as}) — judged. Destination "script" (or another
 *       script-like one), governed by {@code script-src}; the HTML spec fetches a module preload as
 *       not-parser-inserted, which {@code 'strict-dynamic'} lets through from any host. Measured: fetched under the
 *       public policy, refused under beta13's. {@code as=worker}/{@code sharedworker}/{@code serviceworker}/
 *       {@code audioworklet}/{@code paintworklet} are script-like too (worker destinations fall back from
 *       {@code worker-src} to {@code script-src}, which Runway does not split); Chrome 155 rejects those values
 *       without fetching, another engine may not — so they are judged too. A non-script {@code as}
 *       ({@code style}, {@code json}) is governed by its own directive and fetches nothing foreign; judging every
 *       module preload anyway keeps the rule simple and costs nothing.</li>
 *   <li><b>{@code rel} contains {@code prefetch}</b> (any {@code as}) — judged. CSP3 governs a prefetch by
 *       {@code default-src}, which carries no {@code 'strict-dynamic'}, but Chrome 155 fetches a foreign
 *       {@code <link rel=prefetch>} — with or without {@code as}, {@code as=script|style|image|document}, with
 *       {@code crossorigin} — whenever {@code script-src} has {@code 'strict-dynamic'} (even with an explicit
 *       {@code script-src-elem 'self'}), and refuses it under beta13's policy ({@code default-src 'self'}) and under a
 *       nonce without {@code 'strict-dynamic'}. So it is the same regression and gets the same rule (a prefetch to a
 *       host the operator trusts to run scripts reveals nothing more than the script itself would).</li>
 *   <li><b>{@code rel} contains {@code preload} with a script-like {@code as}</b> ({@code script}, {@code worker},
 *       {@code sharedworker}, {@code serviceworker}, {@code audioworklet}, {@code paintworklet}, {@code xslt}; read
 *       ASCII case-insensitively, trimmed) — judged. CSP3 maps these to {@code script-src-elem}/{@code worker-src}.
 *       Chrome 155 treats a classic preload as parser-inserted, so {@code 'strict-dynamic'} refuses it (even
 *       a same-origin one), and rejects the worker/worklet/xslt values; the strip is defence in depth against an
 *       engine that would fetch it as not-parser-inserted. Measured: not fetched under either policy.</li>
 *   <li><b>Not judged</b> — {@code preload} with no {@code as}, {@code as=fetch}/{@code json} (destination "" →
 *       {@code connect-src}), {@code as=style|font|image|…} (their own directives); {@code stylesheet},
 *       {@code icon}, {@code manifest}, {@code compression-dictionary} (their own directives — measured refused under
 *       both policies). {@code preconnect}, {@code dns-prefetch} and {@code prerender} reach the host under
 *       <em>both</em> policies (CSP governs no directive for the first two; Chrome's prerender/NoState-prefetch was
 *       fetched under beta13's policy too): not a regression of {@code 'strict-dynamic'}, so out of scope here
 *       (KNOWN-LIMITATIONS).</li>
 * </ul>
 * {@code rel} is a token list (ASCII whitespace separated, ASCII case-insensitive), so {@code rel="ModulePreload"}
 * and {@code rel="nofollow modulepreload"} both count (measured: both fetched). Attribute values are decoded first,
 * the first occurrence of an attribute wins (as in the parser), and a link with no {@code href} fetches nothing and
 * is kept.
 *
 * <h2>Where a link is looked for — never looser than the stamp</h2>
 * The removal uses the stamp's own scanner ({@link ScriptNonceStamper#walk}), so the two agree on what is a tag:
 * case, quotes, whitespace, attribute order, character references and comments are read as the tokenizer reads them.
 * A {@code <link>} the scanner sees is removed wherever it sits — inside {@code <svg>}, {@code <template>} or
 * {@code <noscript>} too, where the browser may not fetch it (removing it there is harmless; a template's content
 * fetches once a script clones it).
 *
 * <p>The scanner skips two places where a browser <em>can</em> still find a link, and where a removal would be wrong
 * (it could cut a JavaScript string or an attribute): the content of the raw-text elements ({@code script},
 * {@code style}, {@code title}, {@code textarea}, …) — which are not raw text in SVG/MathML, where
 * {@code <svg><title><link …>} or {@code <svg><style><p><link …>} puts a real HTML {@code <link>} in the
 * document — and an {@code <iframe srcdoc>} document, which inherits the page's policy. For these the stripper does
 * not rewrite: it reports {@link Result#hidden()}, after reading each such content as markup (and each
 * {@code srcdoc} value decoded) with the same scanner, recursively up to {@value #MAX_DEPTH} levels (deeper counts as
 * hidden). The caller then withdraws the nonce for that response — the page gets the host-allowlist policy, which
 * blocks the fetch, exactly as for a copied nonce ({@link ScriptNonceStamper#carriesNonce}). A link written in a
 * comment, in a bogus comment ({@code <?…>}, {@code <!…>}), in an ordinary attribute value or as escaped text
 * ({@code &lt;link …&gt;}) is never markup to a browser and is ignored.
 *
 * <p><b>Where the scanner cannot be sure, the page is hidden too</b> (since 1.0.0-rc1). When the walk
 * reaches a stretch whose tokenization depends on the tree and its readings disagree — a raw-text element or a CDATA
 * section after {@code <svg>}/{@code <math>}/{@code <select>}/{@code <template>}, a {@code <noscript>} whose
 * scripting-on and scripting-off readings part, a {@code <base>} the tree builder may not honour — it stops
 * ({@link ScriptNonceStamper#walk} returns {@code false}) and the result is hidden: nothing after that point can be
 * vouched for. Measured against Chrome 155 on a table of tokenizer corners and a generated corpus (the test's
 * {@code preload-corpus-chrome155.txt}): no body served under the nonce kept a link Chrome parses.
 *
 * <p><b>A removal never splices its neighbours</b>: when the text kept before a removed link ends in {@code <} (or
 * {@code ]}), an empty comment {@code <!---->} takes the link's place ({@link ScriptNonceStamper.Edits#removeTag}),
 * so a lone {@code <} cannot join the text after the link into a new comment or tag.
 *
 * <h2>Determinism</h2>
 * The result depends only on the body, the allowlist and the document URL — all three already fold into the page's
 * ETag (the allowlist through its fingerprint) — so one ETag still describes one body. Nothing is logged here.
 */
public final class ScriptPreloadStripper {

    /** {@code as} values whose CSP3 effective directive is {@code script-src-elem} or {@code worker-src}. */
    private static final Set<String> SCRIPT_LIKE_AS = Set.of("script", "worker", "sharedworker", "serviceworker",
            "audioworklet", "paintworklet", "xslt");

    /** Bound on nested raw-text / {@code srcdoc} levels read as markup; deeper nesting counts as hidden. */
    static final int MAX_DEPTH = ScriptNonceStamper.MAX_NESTING;

    private ScriptPreloadStripper() {
    }

    /**
     * The outcome for one body.
     *
     * @param html   the body with every foreign script-class preload link the scanner sees removed (the same instance
     *               when nothing was removed, and always when {@code hidden})
     * @param hidden whether a foreign script-class preload link sits where the scanner cannot remove it (a raw-text
     *               element's content or an {@code <iframe srcdoc>} document), or the scanner could not follow the
     *               browser's tokenizer through the body; the caller must then withdraw the nonce for this response
     *               and serve the <b>original</b> body
     */
    public record Result(String html, boolean hidden) {
    }

    /**
     * Removes every {@code <link>} that would fetch a script-class resource ({@link ScriptPreloadStripper the forms
     * judged}) from a URL {@code allowlist} does not allow, and reports whether such a link is hidden where it cannot
     * be removed. Never throws; {@code null}/empty input or a {@code null} allowlist/document returns the input
     * unchanged and not hidden.
     *
     * @param allowlist the host-allowlist {@code script-src} the page had before {@code 'strict-dynamic'}
     * @param document  the document's URL (scheme, host, port, path) — what {@code 'self'} and relative URLs mean
     */
    public static Result strip(String html, CspScriptAllowlist allowlist, URI document) {
        if (html == null || html.isEmpty() || allowlist == null || document == null) {
            return new Result(html, false);
        }
        ScriptNonceStamper.Edits edits = new ScriptNonceStamper.Edits(html);
        boolean[] hidden = {false};
        boolean followed = ScriptNonceStamper.walk(html, document, document, 0, true,
                (name, start, nameEnd, tag, contentEnd, base, depth) -> {
                    if (hidden[0]) {
                        return;
                    }
                    if ("link".equals(name) && foreignScriptPreload(tag, allowlist, base, document)) {
                        if (depth == 0) {
                            edits.removeTag(start, tag.end()); // a tag of the document itself: safe to cut
                        } else {
                            hidden[0] = true; // markup only if raw text is: cutting could break a script string
                        }
                    }
                    if (!hidden[0] && srcdocHides(tag, base, allowlist, document, depth + 1)) {
                        hidden[0] = true;
                    }
                });
        // A walk that could not follow the browser's tokenizer to the end cannot vouch for what follows that point.
        boolean report = hidden[0] || !followed;
        return new Result(report ? html : edits.result(), report);
    }

    /**
     * Whether this tag's {@code srcdoc} document (decoded) holds a foreign script-class preload link anywhere, or
     * cannot be read with certainty ({@code depth} is the nesting level being entered). An {@code <iframe srcdoc>}
     * document inherits this page's policy and resolves relative URLs against the embedding document's base. The
     * scanner does not know the element's namespace, so any srcdoc is read (fail closed).
     */
    private static boolean srcdocHides(ScriptNonceStamper.Tag tag, URI base, CspScriptAllowlist allowlist,
            URI document, int depth) {
        String srcdoc = tag.attributes().get("srcdoc");
        return srcdoc != null && hides(ScriptNonceStamper.decodeAttribute(srcdoc), base, allowlist, document, depth);
    }

    /**
     * Whether {@code markup}, read as a document, contains a foreign script-class preload link anywhere — in its own
     * token stream, in raw-text contents read as markup, in nested {@code srcdoc} documents — or cannot be read with
     * certainty (the walk stopped, or nesting passed {@value #MAX_DEPTH}).
     */
    private static boolean hides(String markup, URI base, CspScriptAllowlist allowlist, URI document, int depth) {
        if (markup.isEmpty()) {
            return false;
        }
        if (depth > MAX_DEPTH) {
            return true; // no real page nests this deep: refuse to vouch for it
        }
        boolean[] found = {false};
        boolean followed = ScriptNonceStamper.walk(markup, document, base, depth, true,
                (name, start, nameEnd, tag, contentEnd, innerBase, tagDepth) -> {
                    if (!found[0] && (("link".equals(name) && foreignScriptPreload(tag, allowlist, innerBase, document))
                            || srcdocHides(tag, innerBase, allowlist, document, tagDepth + 1))) {
                        found[0] = true;
                    }
                });
        return found[0] || !followed;
    }

    /**
     * Whether this {@code <link>} would fetch a script-class resource from a URL the allowlist policy did not allow.
     * A link with no {@code href} fetches nothing and is not foreign.
     */
    static boolean foreignScriptPreload(ScriptNonceStamper.Tag tag, CspScriptAllowlist allowlist, URI base,
            URI document) {
        String href = tag.attributes().get("href");
        if (href == null || !scriptClassPreload(tag)) {
            return false;
        }
        return !allowlist.allowsUrl(ScriptNonceStamper.decodeAttribute(href), base, document);
    }

    /** Whether the link's {@code rel} (and, for {@code preload}, its {@code as}) asks for a script-class fetch. */
    private static boolean scriptClassPreload(ScriptNonceStamper.Tag tag) {
        String rel = tag.attributes().get("rel");
        if (rel == null) {
            return false;
        }
        boolean preload = false;
        for (String token : tokens(ScriptNonceStamper.decodeAttribute(rel))) {
            if ("modulepreload".equals(token) || "prefetch".equals(token)) {
                return true;
            }
            preload |= "preload".equals(token);
        }
        if (!preload) {
            return false;
        }
        String as = tag.attributes().get("as");
        return as != null && SCRIPT_LIKE_AS.contains(
                ScriptNonceStamper.asciiLower(trimAsciiWhitespace(ScriptNonceStamper.decodeAttribute(as))));
    }

    /** A DOM token list: split on ASCII whitespace, ASCII-lower-cased (link types are ASCII case-insensitive). */
    private static String[] tokens(String value) {
        return ScriptNonceStamper.asciiLower(trimAsciiWhitespace(value)).split("[ \t\n\f\r]+");
    }

    private static String trimAsciiWhitespace(String value) {
        int from = 0;
        int to = value.length();
        while (from < to && ScriptNonceStamper.isSpace(value.charAt(from))) {
            from++;
        }
        while (to > from && ScriptNonceStamper.isSpace(value.charAt(to - 1))) {
            to--;
        }
        return value.substring(from, to);
    }
}
