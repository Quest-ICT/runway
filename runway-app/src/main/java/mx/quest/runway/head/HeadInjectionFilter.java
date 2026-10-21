package mx.quest.runway.head;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.quest.runway.plugin.ContributionGuard;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.web.CspScriptAllowlist;
import mx.quest.runway.web.PublicPageNonce;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Splices {@link mx.quest.runway.spi.HeadContributor}-contributed markup into a rendered page's
 * {@code <head>} <b>at the response edge</b> — after the page is rendered, on the HTML bytes,
 * independently of which master the site uses. This is how technical SEO/GEO/analytics markup lands
 * on every page without any code inside a tenant's templates (the multi-tenant rule's edge-injection
 * seam).
 *
 * <p>It also sets the page's <b>{@code <html lang="…">}</b> from the resolved request locale (the same
 * edge seam, the same reason): a tenant master hardcodes one {@code lang} for its snapshot, so a page
 * served in another language would otherwise mis-declare it — an a11y/SEO defect. Setting it here fixes
 * every page of every site (and stays correct per served locale) without touching a single template.
 *
 * <h2>Why a buffering filter, not a render-time step</h2>
 * The filter sits at the outer edge of the chain, so it works for <em>any</em> HTML response (page
 * renders today, plugin-served HTML later) and runs before response compression
 * gets a chance to see the bytes. It cannot read {@link mx.quest.runway.site.SiteResolver#current()}
 * — by the time its post-chain code runs the request's site binding has already been cleared — so the
 * render handler stashes a host-materialized {@link HeadContext} under {@link #HEAD_CONTEXT_ATTRIBUTE}
 * for the filter to consume.
 *
 * <h2>The public-page CSP nonce stamp</h2>
 * When a page response published a {@link PublicPageNonce} (an ETag-derived value — see that class), this filter
 * stamps it via {@link ScriptNonceStamper}, <b>after</b> the head splice so contributor-injected scripts are judged
 * as well, on every executable {@code <script>} the host-allowlist policy would have run ({@link CspScriptAllowlist}:
 * same-origin or allowlisted {@code src}; never an inline script under the shipped default) — so a page moved onto
 * {@code 'strict-dynamic'} never runs a script the allowlist blocked. {@code CspFilter} puts the same value in the
 * header. {@code 'self'} and relative URLs are judged against this request's URL (scheme/host/port as the
 * trusted-proxy gate left them). When the unstamped body <b>already contains</b> the nonce (in any character-reference
 * encoding — {@link ScriptNonceStamper#carriesNonce}), markup copied it in; the response then withdraws the nonce
 * before commit (so {@code CspFilter} emits the host-allowlist policy), stays unstamped, and goes out without
 * validators and {@code no-store}, like a degraded render. Before the stamp, {@link ScriptPreloadStripper} removes
 * every {@code <link>} that would fetch a script-class resource ({@code modulepreload}, {@code prefetch},
 * {@code preload as=script}…) from a URL the host-allowlist policy refused — under {@code 'strict-dynamic'} the
 * browser fetches such a preload from any host; one it cannot remove (inside a raw-text element or an
 * {@code <iframe srcdoc>}), or a body whose tokenization the scanner cannot follow with certainty, withdraws the
 * nonce the same way as a copied nonce. Neither step runs once the nonce is
 * withdrawn: the host-allowlist policy blocks both scripts and preloads by itself. Buffering is therefore decoupled
 * from "any head contributor present": with the feature configured, every
 * non-{@code HEAD} response is buffered (it was already, wherever a contributor exists — i.e. every real install).
 *
 * <h2>Pass-through (never corrupt a response)</h2>
 * The body streams verbatim, with no rewrite, when any of these hold: the registry is empty and the nonce
 * feature is off (no buffering at all); the request is a {@code HEAD}; the buffered body overflowed the cap (then
 * any public nonce is withdrawn before the first byte commits and the response is marked {@code no-store}, so the
 * header never promises a nonce the body lacks); neither a {@link HeadContext} nor a nonce was stashed; or the
 * response is not {@code text/html}. The head splice is skipped when the contributors produced no markup or the
 * body has no {@code </head>}. The splice point is the <b>first</b> {@code </head>} (case-insensitive); markup is
 * decoded/encoded with the response's own charset so non-ASCII content round-trips intact.
 *
 * <h2>Failure containment</h2>
 * While buffering, the validators the controller stamped ({@code ETag}, {@code Last-Modified}) are held back
 * ({@link ValidatorHoldingResponse}). If a head contributor throws — or a widget or {@code headInputs} call was
 * substituted earlier in the request ({@link ContributionGuard#isDegraded}) — the page goes out without them and
 * with {@code Cache-Control: no-store}, so the degraded copy can never be revalidated into a {@code 304}. On the
 * healthy path (and before the first byte of an overflowing body) they are released unchanged.
 *
 * <p>A response the handler ended with {@code sendError} is left to the container untouched: nothing is written and the
 * held validators are dropped ({@link mx.quest.runway.web.BufferingResponseWrapper#isErrorSent()}).
 *
 * <p>It sets {@code Content-Length} for the body it writes. It is not the outermost egress filter:
 * {@code CompressionFilter} ({@link mx.quest.runway.web.FilterOrder#CORE_COMPRESSION}) wraps it. When that filter
 * engages (compression on and an encoding the client accepts; never for {@code HEAD}) it buffers this output —
 * its buffer withholds the length — and sets the length of the body it finally sends; otherwise it passes through
 * and the length set here is the one on the wire.
 */
public class HeadInjectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HeadInjectionFilter.class);

    /** Request attribute under which a render handler stashes the {@link HeadContext} to inject for. */
    public static final String HEAD_CONTEXT_ATTRIBUTE = "runway.head.context";

    /** Default buffer cap: bodies larger than this stream through unrewritten (they are not pages). */
    private static final long DEFAULT_MAX_BUFFER_BYTES = 8L * 1024 * 1024;

    private static final String CLOSE_HEAD = "</head>";

    /** The opening {@code <html …>} tag (case-insensitive); {@code <html\b} never matches inside a DOCTYPE. */
    private static final Pattern HTML_TAG = Pattern.compile("<html\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    /** A {@code lang="…"} (or {@code '…'}) attribute inside that tag. */
    private static final Pattern LANG_ATTR = Pattern.compile("\\blang\\s*=\\s*([\"'])[^\"']*\\1", Pattern.CASE_INSENSITIVE);

    private final HeadContributorRegistry registry;
    private final long maxBufferBytes;
    /** The public-page CSP nonce source, or {@code null} (feature absent — the pre-Phase-B behaviour). */
    private final PublicPageNonce publicNonce;
    /** Which scripts may carry the public nonce: those the host-allowlist {@code script-src} allowed. */
    private final CspScriptAllowlist scriptAllowlist;

    public HeadInjectionFilter(HeadContributorRegistry registry) {
        this(registry, DEFAULT_MAX_BUFFER_BYTES, null, null);
    }

    /** Head injection plus the public-page nonce stamp, judged against the shipped {@code 'self'}-only allowlist. */
    public HeadInjectionFilter(HeadContributorRegistry registry, PublicPageNonce publicNonce) {
        this(registry, DEFAULT_MAX_BUFFER_BYTES, publicNonce, null);
    }

    /**
     * Production form: head injection plus the public-page nonce stamp.
     *
     * @param scriptAllowlist the policy's host-allowlist {@code script-src}; {@code null} means {@code 'self'} only
     */
    public HeadInjectionFilter(HeadContributorRegistry registry, PublicPageNonce publicNonce,
                               CspScriptAllowlist scriptAllowlist) {
        this(registry, DEFAULT_MAX_BUFFER_BYTES, publicNonce, scriptAllowlist);
    }

    /** Package-visible constructor letting tests exercise the overflow path with a small cap. */
    HeadInjectionFilter(HeadContributorRegistry registry, long maxBufferBytes) {
        this(registry, maxBufferBytes, null, null);
    }

    HeadInjectionFilter(HeadContributorRegistry registry, long maxBufferBytes, PublicPageNonce publicNonce) {
        this(registry, maxBufferBytes, publicNonce, null);
    }

    HeadInjectionFilter(HeadContributorRegistry registry, long maxBufferBytes, PublicPageNonce publicNonce,
                        CspScriptAllowlist scriptAllowlist) {
        this.registry = registry;
        this.maxBufferBytes = maxBufferBytes;
        this.publicNonce = publicNonce;
        this.scriptAllowlist = scriptAllowlist != null ? scriptAllowlist : CspScriptAllowlist.selfOnly();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Buffer when there is anything to do to a body: head markup to inject, OR a public-page nonce to stamp.
        // (Gating only on "any head contributor present" would silently skip the nonce stamp on exactly the sites
        // with no contributors — the latent Phase-B bug the scope doc called out.)
        boolean nonceStamping = publicNonce != null && publicNonce.configured();
        if ((registry.isEmpty() && !nonceStamping) || "HEAD".equalsIgnoreCase(request.getMethod())) {
            // nothing to do: nothing can ever be injected or stamped. HEAD: it carries no body to rewrite,
            // and buffering a body-less response makes BufferingResponseWrapper (which WITHHOLDS Content-Length so a
            // rewrite can recompute it) re-stamp Content-Length: 0 from the empty buffer — which zeroed the
            // handler's correct length on HEAD /__assets (Spring's ResourceHttpRequestHandler writes no HEAD body).
            // General rule: any egress filter wrapping with BufferingResponseWrapper must bypass HEAD.
            chain.doFilter(request, response); // skip buffering entirely
            return;
        }

        // On overflow the body streams out unrewritten, so its scripts will carry no nonce: withdraw the nonce BEFORE
        // the first byte commits (CspFilter then stamps the host-allowlist policy, which those scripts satisfy), and
        // mark the response no-store — otherwise a later 304 (computed before rendering, so it would carry the
        // nonced header) could be paired with this un-nonced body in a cache. Pages over the cap are pathological.
        ValidatorHoldingResponse[] holder = new ValidatorHoldingResponse[1];
        Runnable onOverflow = () -> {
            // The body will stream unrewritten, so it is the healthy representation: its validators go out first.
            holder[0].releaseValidators();
            if (nonceStamping && request.getAttribute(PublicPageNonce.REQUEST_ATTRIBUTE) != null) {
                PublicPageNonce.withdraw(request);
                response.setHeader("Cache-Control", "no-store");
            }
        };
        ValidatorHoldingResponse wrapper = new ValidatorHoldingResponse(response, maxBufferBytes, onOverflow);
        holder[0] = wrapper;
        chain.doFilter(request, wrapper);
        wrapper.flushBuffer();
        if (wrapper.isOverflowed()) {
            return; // too large to rewrite; already streamed verbatim
        }
        if (wrapper.isErrorSent()) {
            // The handler answered with sendError (e.g. the resource handler's 404 for a missing /__assets file): the
            // container owns the response now and renders the error page in its own dispatch. There is no page body
            // to rewrite, and writing the empty buffer into the closed response threw (and logged) an EofException.
            // The held-back validators are simply never released — an error response carries none.
            return;
        }

        byte[] body = wrapper.capturedBytes();
        HeadContext context = headContext(request);
        String nonce = request.getAttribute(PublicPageNonce.REQUEST_ATTRIBUTE) instanceof String n ? n : null;
        if ((context == null && nonce == null) || !isHtml(wrapper.getContentType())) {
            wrapper.releaseValidators();
            write(response, body);
            return;
        }

        Charset charset = charsetOf(wrapper);
        String html = new String(body, charset);
        String out = html;
        boolean degraded = false;

        if (context != null) {
            // 1) Set <html lang> to the page's resolved locale — a cross-cutting a11y/SEO concern applied at the
            //    edge, so a tenant's templates never have to carry (and can never get wrong) their own lang. A page
            //    whose master hardcodes lang="en" served in Spanish now correctly declares lang="es".
            out = applyHtmlLang(out, context.locale().toLanguageTag());

            // 2) Splice contributor <head> markup (SEO/GEO/icons) before the first </head>, if any was produced.
            HeadContributorRegistry.Rendered rendered = registry.renderChecked(context);
            degraded = rendered.degraded();
            String markup = rendered.markup();
            if (!markup.isBlank()) {
                int idx = indexOfClosingHead(out);
                if (idx >= 0) {
                    out = out.substring(0, idx) + markup + "\n" + out.substring(idx);
                }
            }
        }

        // 3) Stamp the public-page CSP nonce on every executable <script> the allowlist policy would have run — LAST,
        //    so scripts a head contributor just spliced in are judged too. The header half (same value, derived from
        //    the ETag) is CspFilter's.
        //    Unless the body already carries this very nonce: markup copied the published value in (through an input the
        //    ETag does not fold — e.g. inside an <iframe srcdoc>, which inherits the page's policy). Then this response
        //    gets no nonce at all: CspFilter reads the attribute at commit and emits the host-allowlist policy, the
        //    body stays unstamped, and the copy unlocks nothing.
        //    Before the stamp, foreign script-class preloads go: under 'strict-dynamic' a <link
        //    rel=modulepreload|prefetch> to a host the allowlist policy refused is fetched anyway — a beacon channel
        //    'self' closed. One the scanner cannot remove (inside raw text or an <iframe srcdoc>) withdraws the nonce
        //    instead, like a copied one: the allowlist policy then blocks the fetch. So does a body the scanner cannot
        //    follow the browser's tokenizer through: it cannot vouch for what comes after.
        boolean nonceWithdrawn = false;
        if (nonce != null) {
            URI document = documentUrl(request);
            if (ScriptNonceStamper.carriesNonce(out, nonce)) {
                nonceWithdrawn = true;
                PublicPageNonce.withdraw(request);
                log.warn("Public page {} already contains its own CSP nonce (copied into the markup); served on the "
                        + "host-allowlist policy, unstamped and no-store", request.getRequestURI());
            } else {
                ScriptPreloadStripper.Result preloads = ScriptPreloadStripper.strip(out, scriptAllowlist, document);
                if (preloads.hidden()) {
                    nonceWithdrawn = true; // same consequences: allowlist policy, unstamped, no validators, no-store
                    PublicPageNonce.withdraw(request);
                    log.debug("Public page {} hides a foreign script preload where the edge cannot remove it (or "
                            + "markup the edge cannot read with certainty); served on the host-allowlist policy, "
                            + "unstamped and no-store", request.getRequestURI());
                } else {
                    out = ScriptNonceStamper.stamp(preloads.html(), nonce, scriptAllowlist, document);
                }
            }
        }

        if (nonceWithdrawn || degraded || ContributionGuard.isDegraded(request)) {
            // nonceWithdrawn: the validators describe the nonced representation, and a later 304 for them would carry
            // the nonced header — so this body must never be stored or revalidated.
            // A head contributor (or, earlier, a widget or headInputs) was substituted (contained): this body is not the
            // representation the page's validators describe. No validators, no-store — so no client or CDN can
            // revalidate the broken copy into a 304, and the next request renders afresh.
            wrapper.dropValidators();
            response.setHeader("Cache-Control", "no-store");
        } else {
            wrapper.releaseValidators();
        }
        // Re-encode only if something actually changed (keeps the verbatim pass-through for untouched bodies).
        write(response, out.equals(html) ? body : out.getBytes(charset));
    }

    /**
     * Returns {@code html} with the opening {@code <html>} tag's {@code lang} attribute set to {@code langTag}
     * (replacing an existing value, else inserting one). A no-op — the same string — when there is no
     * {@code <html>} tag or {@code langTag} is blank, so a non-page or fragment body is never corrupted.
     */
    private static String applyHtmlLang(String html, String langTag) {
        if (langTag == null || langTag.isBlank()) {
            return html;
        }
        Matcher tag = HTML_TAG.matcher(html);
        if (!tag.find()) {
            return html;
        }
        String opening = tag.group();
        Matcher lang = LANG_ATTR.matcher(opening);
        String replacement = "lang=\"" + langTag + "\"";
        int afterName = "<html".length(); // preserve the original tag-name case (e.g. <HTML>) when inserting
        String newOpening = lang.find()
                ? opening.substring(0, lang.start()) + replacement + opening.substring(lang.end())
                : opening.substring(0, afterName) + " " + replacement + opening.substring(afterName);
        return html.substring(0, tag.start()) + newOpening + html.substring(tag.end());
    }

    /**
     * This request's URL as the browser addressed it — scheme, host and port as the trusted-proxy gate left them, plus
     * the path relative URLs resolve against. Falls back to the origin's root when the path is not a valid URI path,
     * and to {@code null} (nothing is then stamped) when even the origin is not.
     */
    static URI documentUrl(HttpServletRequest request) {
        String host = request.getServerName();
        if (host != null && host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]"; // an IPv6 literal
        }
        String origin = request.getScheme() + "://" + host + ":" + request.getServerPort();
        String path = request.getRequestURI();
        try {
            return new URI(origin + (path == null || !path.startsWith("/") ? "/" : path));
        } catch (URISyntaxException badPath) {
            try {
                return new URI(origin + "/");
            } catch (URISyntaxException badOrigin) {
                return null;
            }
        }
    }

    private static HeadContext headContext(HttpServletRequest request) {
        Object attribute = request.getAttribute(HEAD_CONTEXT_ATTRIBUTE);
        return (attribute instanceof HeadContext context) ? context : null;
    }

    private static boolean isHtml(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html");
    }

    private static Charset charsetOf(HttpServletResponse response) {
        String name = response.getCharacterEncoding();
        if (name == null || name.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name);
        } catch (RuntimeException ex) {
            return StandardCharsets.UTF_8;
        }
    }

    /** The index of the first {@code </head>} (case-insensitive), or {@code -1} when absent. */
    private static int indexOfClosingHead(String html) {
        int limit = html.length() - CLOSE_HEAD.length();
        for (int i = 0; i <= limit; i++) {
            if (html.regionMatches(true, i, CLOSE_HEAD, 0, CLOSE_HEAD.length())) {
                return i;
            }
        }
        return -1;
    }

    private static void write(HttpServletResponse response, byte[] bytes) throws IOException {
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }
}
