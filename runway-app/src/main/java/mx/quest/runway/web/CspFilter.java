package mx.quest.runway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sets the Content-Security-Policy header on every {@code text/html} response — the kernel's CSP edge filter,
 * wired at {@link FilterOrder#CORE_CSP_FINALIZE}. That slot sits <b>outer</b> of head-injection ({@code -150})
 * and <b>inner</b> of compression ({@code -200}).
 *
 * <h2>Why the header is stamped at commit time, not post-chain</h2>
 * The CSP header depends on the response's content type ({@code text/html} only), which is not settled until the
 * downstream handler has run. The naive approach — set the header in the filter's {@code post}, after
 * {@code chain.doFilter} — is <b>broken</b>: the inner {@link mx.quest.runway.head.HeadInjectionFilter}
 * ({@code -150}) writes the finished body and thereby <b>commits</b> the response inside <em>its</em> {@code post},
 * which runs <b>before</b> this (outer) filter's {@code post}. Once the response is committed a real servlet
 * container silently drops any further {@code setHeader}, so the CSP header vanished on any response that
 * head-injection wrote directly — i.e. every plain request. It only "worked" when the outermost compression filter
 * ({@code -200}) happened to buffer the whole response (client sent {@code Accept-Encoding}), deferring the commit
 * to the very end so this filter's {@code post} still ran against an uncommitted response. CSP correctness must not
 * hinge on compression being active.
 *
 * <p>The fix is ordering- and compression-independent: the filter wraps the response so that the header is applied
 * <b>lazily, at the first commit-triggering call</b> (a body write, {@code flushBuffer}, {@code sendError}, or a
 * status/redirect). At that moment the content type is known, so the wrapper decides HTML-or-not and — for an HTML
 * document — stamps the header while the response is still uncommitted, <em>before</em> any inner filter's write
 * reaches the wire. No buffering, no post-chain dependence, no reliance on who wraps whom.
 *
 * <h2>What it covers</h2>
 * Both realms, uniformly: public tenant pages <b>and</b> the operator console — every {@code text/html}
 * response. This is safe because the default {@link RunwayCspProperties.Mode#REPORT report-only} mode only
 * <em>reports</em> violations (to {@link CspReportController}); it never blocks a resource. A non-HTML response
 * (an asset, a JSON API, a manifest, an image) is left untouched — CSP governs document loads, not the
 * sub-resources themselves.
 *
 * <h2>Per-request nonce</h2>
 * {@code script-src} leads with a per-request {@code 'nonce-<x>'}. The filter mints a 128-bit base64url nonce in
 * its {@code pre} (before the handler renders), publishes it as the {@value #NONCE_REQUEST_ATTRIBUTE} request
 * attribute for the render layer (a {@code @ModelAttribute} advice exposes it to every template), and the wrapper
 * splices the SAME value into the header's {@code script-src} placeholder at commit — so the header nonce and the
 * rendered {@code <script nonce>} always agree. That per-request nonce is minted <b>only on the kernel
 * surfaces</b> (console, login, recovery), whose templates carry {@code nonce="${cspNonce}"} and are {@code no-store}.
 *
 * <h2>Public pages: an ETag-derived nonce</h2>
 * A public tenant page is shared-cacheable and revalidates with {@code 304}, so it cannot take a per-request nonce
 * (see {@link PublicPageNonce}). Instead the page controller publishes a nonce <b>derived from the page's ETag</b>
 * under {@link PublicPageNonce#REQUEST_ATTRIBUTE}; the wrapper reads it <b>at commit</b> (it does not exist yet in
 * {@code pre}) and stamps {@code 'nonce-<x>' 'strict-dynamic'} — on the {@code 200} and on the content-type-less
 * {@code 304} alike, so both carry the same header — while {@code HeadInjectionFilter} stamps the same value on
 * every executable {@code <script>} in the body. The page keeps its own {@code Cache-Control}: the nonce is stable
 * for the content version, so caching it is correct. A response with no page ETag (an error page, a plugin
 * endpoint, the maintenance page) gets no nonce and keeps the host-allowlist policy.
 *
 * <h2>Cheap by construction</h2>
 * The policy <b>template</b> is built <b>once</b> at construction ({@link RunwayCspProperties#buildPolicy()}) and
 * the header name once ({@link RunwayCspProperties#headerName()}). Per request: one {@code SecureRandom.nextBytes},
 * a content-type check, one {@code String.replace} (the nonce into {@code script-src}) and a single
 * {@code setHeader} at commit — no body buffering. When {@link RunwayCspProperties.Mode#OFF} the header name is
 * {@code null} and the filter is a pure pass-through (no nonce is minted).
 *
 * <h2>Additive, never subtractive</h2>
 * It only <b>adds</b> the CSP header; it never touches {@code X-Frame-Options} ({@code SAMEORIGIN}) or
 * {@code X-Content-Type-Options} ({@code nosniff}), which Spring Security still owns. CSP complements those
 * headers; it does not replace them.
 *
 * <h2>Egress-filter contract</h2>
 * Like the other egress filters, its {@code pre} runs before the site is bound (see {@link FilterOrder}), so it
 * decides purely from the response's content type and the pre-built policy — it never reads
 * {@code SiteResolver.current()}. The policy is platform-wide, except on a site's <b>public</b> pages: there, at
 * commit, the site that answered ({@link mx.quest.runway.site.SiteContextFilter#SITE_ID_ATTRIBUTE}) may have switched
 * on some of the operator's approved embed hosts, and {@link SiteCspPolicies} supplies the policy with them added.
 * The consoles, sign-in and recovery pages never get a site's additions.
 */
public class CspFilter extends OncePerRequestFilter {

    /** The request-attribute key under which the per-request CSP nonce is published for the render layer to read. */
    public static final String NONCE_REQUEST_ATTRIBUTE = "cspNonce";

    /** The header name for the configured mode, or {@code null} when CSP is off (then the filter is inert). */
    private final String headerName;

    /**
     * The immutable, pre-built policy <b>template</b> set on every HTML response. It carries
     * {@link RunwayCspProperties#NONCE_PLACEHOLDER} inside {@code script-src}; {@link CspHeaderResponse} replaces
     * that token with the request's fresh nonce at stamp time, so the header matches the rendered body.
     */
    private final String policy;

    /** The {@code Reporting-Endpoints} header value paired with the policy's {@code report-to} group, or {@code null}. */
    private final String reportingEndpoints;

    /**
     * Source of per-request nonces — one shared, thread-safe instance so the per-request cost is a single
     * {@code nextBytes}, never a re-seed. 16 bytes ⇒ a 22-char base64url token (128 bits of entropy).
     */
    private final SecureRandom nonceRandom = new SecureRandom();

    /** Whether {@code 'strict-dynamic'} is enabled ({@link RunwayCspProperties#isStrictDynamic()}); off ⇒ the
     *  nonce-only behaviour everywhere (and no public-page nonce is ever published — {@link PublicPageNonce} is
     *  configured off). Captured once; the policy template carries the placeholder only when on. */
    private final boolean strictDynamicEnabled;

    /** Site id → that site's public-page policy template, or {@code null}; see the constructor. */
    private final java.util.function.Function<String, String> sitePolicy;

    public CspFilter(RunwayCspProperties props) {
        this(props, java.util.List.of());
    }

    /**
     * @param pluginHosts extra CSP source hosts contributed by {@link CspHostContributor}s (e.g. the CAPTCHA
     *                    provider domains), folded into the policy alongside {@code runway.csp.extra-hosts}
     */
    public CspFilter(RunwayCspProperties props, java.util.List<String> pluginHosts) {
        this(props, pluginHosts, null);
    }

    /**
     * @param sitePolicy for a site id, the policy template for that site's public pages, or {@code null} for the
     *                   platform one ({@link SiteCspPolicies}); {@code null} turns per-site policies off
     */
    public CspFilter(RunwayCspProperties props, java.util.List<String> pluginHosts,
                     java.util.function.Function<String, String> sitePolicy) {
        this.sitePolicy = sitePolicy;
        this.headerName = props.headerName();
        this.policy = props.buildPolicy(pluginHosts);
        this.reportingEndpoints = props.reportingEndpointsHeader();
        this.strictDynamicEnabled = props.isStrictDynamic();
    }

    /**
     * When CSP is off there is nothing to do for any request — skip the filter entirely. (When on, the wrapper's
     * per-request content-type check decides HTML vs not at commit; no buffering is ever needed either way.)
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return headerName == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // A per-request nonce is minted ONLY for the kernel surfaces whose templates actually carry
        // nonce="${cspNonce}". A public tenant page never gets one: it would make an otherwise-identical
        // SHARED-CACHEABLE response (`public, max-age=300`) vary per request, and a cache that stored header+body
        // together would then serve one frozen nonce to everyone (raised from a live deployment).
        // Public pages get the ETag-DERIVED PublicPageNonce instead, picked up at commit — see the class Javadoc.
        // Decided on the decoded path the dispatcher routes on (RequestPaths), so /app/%6Cogin gets the sign-in page's
        // nonce and policy like /app/login: the page rendered is the same.
        boolean kernelSurface = noncedKernelSurface(RequestPaths.normalized(request));
        // Mint in PRE — BEFORE the handler renders — and publish it so the render layer and the header share ONE
        // value (the migration's core invariant: header nonce == body nonce). shouldNotFilter already skipped this
        // filter when CSP is OFF, so a nonce is only ever minted when a policy will be sent.
        String nonce = kernelSurface ? freshNonce() : null;
        if (nonce != null) {
            request.setAttribute(NONCE_REQUEST_ATTRIBUTE, nonce);
        }
        // 'strict-dynamic' rides the same surfaces, and can be switched off estate-wide for rollback (the nonce
        // stays, so the nonce-only behaviour remains).
        boolean strict = strictDynamicEnabled && kernelSurface;
        // Wrap so the header is stamped at the first commit-triggering call (see class Javadoc) — before any inner
        // filter's write reaches the wire, regardless of filter ordering or whether compression is buffering.
        CspHeaderResponse wrapped = new CspHeaderResponse(response, request, nonce, strict, kernelSurface);
        chain.doFilter(request, wrapped);
        // A public page's 304 may reach here with nothing written (no body, no flush through this wrapper). It must
        // still carry the 200's header — same derived nonce — so stamp it now while the response is uncommitted.
        if (request.getAttribute(PublicPageNonce.REQUEST_ATTRIBUTE) != null) {
            wrapped.applyHeaderIfHtml();
        }
    }

    /**
     * Whether {@code path} is a nonced kernel-controlled HTML surface — the operator console and the login /
     * recovery pages (the templates that carry the nonce). Only these get {@code 'strict-dynamic'}. OPT-IN by design: a
     * kernel surface accidentally omitted here is merely left unhardened (keeps 'self'+host-allowlist), never broken
     * — whereas defaulting a PUBLIC page into strict-dynamic would block its un-nonced scripts.
     *
     * <p>Every path here must be one a real, always-present controller OWNS (so it beats the tenant {@code /**}
     * catch-all and a tenant can never occupy it): {@code /login}//{@code /logout} and {@code /forgot-password}/
     * {@code /reset} (+ their {@code /app/*} app-realm twins) all have unconditional controllers, and
     * {@code /__app}//{@code /__admin} are reserved. <b>{@code /setup} is deliberately excluded</b>: the wizard that
     * owns it is {@code @Conditional} on WIZARD mode, so in ACTIVE mode {@code /setup} is a free tenant slug —
     * hardening it would strict-dynamic-block a tenant's own page. The wizard's own scripts are nonced and still
     * load under {@code 'nonce' 'self'} without strict-dynamic, so leaving {@code /setup} unhardened is safe.
     *
     * <p>The set is the two realm definitions, {@link AppRealmPaths} and {@link TenantConsolePaths}, which both
     * include their realm's recovery pages; {@code path} is the decoded path ({@link RequestPaths#normalized}).
     */
    private static boolean noncedKernelSurface(String path) {
        return AppRealmPaths.isAppRealm(path) || TenantConsolePaths.isConsole(path);
    }

    /** A fresh 128-bit base64url nonce (no padding) — CSP-valid ({@code A-Za-z0-9-_}) and HTML-attribute-safe. */
    private String freshNonce() {
        byte[] bytes = new byte[16];
        nonceRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Whether the response's base media type is {@code text/html} (charset/parameters ignored). */
    private static boolean isHtml(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html");
    }

    /**
     * A response wrapper that stamps the CSP header exactly once, at the first operation that commits (or is about
     * to commit) the response, and only when the base content type is {@code text/html}. This guarantees the
     * header is present before the body reaches the client no matter which inner filter performs the write —
     * closing the "committed before the outer filter's post" hole that made CSP depend on compression buffering.
     */
    private final class CspHeaderResponse extends HttpServletResponseWrapper {

        /** Set once the header has been applied (or a non-HTML type observed) so we never re-stamp or re-check. */
        private boolean settled;

        /** The request's nonce, spliced into {@code script-src} at stamp time so the header matches the body. */
        private final String nonce;

        /** Whether this response is a nonced kernel surface that gets {@code 'strict-dynamic'} (+ {@code no-store}). */
        private final boolean strict;

        /** Read at stamp time for the {@link PublicPageNonce} the page controller publishes after this wrap. */
        private final HttpServletRequest request;

        /** Whether this is a console, sign-in or recovery page: never given a site's additions. */
        private final boolean kernelSurface;

        private ServletOutputStream outputStream;
        private PrintWriter writer;

        private CspHeaderResponse(HttpServletResponse response, HttpServletRequest request, String nonce,
                                  boolean strict, boolean kernelSurface) {
            super(response);
            this.kernelSurface = kernelSurface;
            this.request = request;
            this.nonce = nonce;
            this.strict = strict;
        }

        /**
         * The platform policy, or — on a site's public page, when the site switched on approved embed hosts — the
         * site's. The site id comes from the request attribute, which outlives the thread binding (a 304 is stamped
         * after the chain returned).
         */
        private String policyForThisResponse() {
            if (sitePolicy == null || kernelSurface
                    || !(request.getAttribute(mx.quest.runway.site.SiteContextFilter.SITE_ID_ATTRIBUTE)
                            instanceof String siteId)) {
                return policy;
            }
            String site = sitePolicy.apply(siteId);
            return site != null ? site : policy;
        }

        /**
         * Stamps the CSP header if the response is an uncommitted {@code text/html} document and the header has not
         * already been set. Idempotent: after the first call it is a no-op. Called immediately before any operation
         * that could commit the response.
         */
        private void applyHeaderIfHtml() {
            if (settled) {
                return;
            }
            HttpServletResponse real = (HttpServletResponse) getResponse();
            // If it is already committed there is nothing we can do — but with this wrapper that only happens when a
            // downstream committed through a path that bypassed us, which the write/flush hooks below prevent.
            // Defer to a handler that already set its OWN CSP under EITHER header name (e.g. the sandboxed page
            // preview sets `Content-Security-Policy: sandbox allow-scripts`): only checking our mode's header name
            // would double-stamp — adding our report-only policy alongside the preview's enforce one, which floods
            // /__csp-report with the preview's (un-nonced, sandboxed) scripts during a report-only bake.
            // A public page's ETag-derived nonce, published by the controller AFTER this wrapper was built — so it is
            // read here, at commit, never in pre. Only a kernel surface's own nonce outranks it (they never coexist).
            String pageNonce = nonce == null && request.getAttribute(PublicPageNonce.REQUEST_ATTRIBUTE)
                    instanceof String published ? published : null;
            // A 304 has no content type, yet it must carry the same header as the 200 it revalidates.
            boolean document = isHtml(real.getContentType())
                    || (pageNonce != null && real.getStatus() == HttpServletResponse.SC_NOT_MODIFIED);
            if (!real.isCommitted() && document
                    && !real.containsHeader("Content-Security-Policy")
                    && !real.containsHeader("Content-Security-Policy-Report-Only")) {
                // Resolve both script-src placeholders for THIS response: the nonce expression (kernel surface or
                // public page version) or nothing, then 'strict-dynamic' or nothing. Every other directive is constant.
                String effectiveNonce = nonce != null ? nonce : pageNonce;
                boolean effectiveStrict = strict || (pageNonce != null && strictDynamicEnabled);
                real.setHeader(headerName, policyForThisResponse()
                        .replace(RunwayCspProperties.NONCE_PLACEHOLDER,
                                effectiveNonce == null ? "" : "'nonce-" + effectiveNonce + "' ")
                        .replace(RunwayCspProperties.STRICT_DYNAMIC_PLACEHOLDER,
                                effectiveStrict ? "'strict-dynamic' " : ""));
                // Pair the policy's report-to group with a Reporting-Endpoints header (CSP-C) so a modern browser
                // routes violation reports to the same in-kernel collector. Additive; report-uri stays for legacy.
                if (reportingEndpoints != null) {
                    real.setHeader("Reporting-Endpoints", reportingEndpoints);
                }
                // A nonced page's nonce is load-bearing: a shared-cached copy replayed with a stale nonce would let
                // a stored-XSS payload carrying that nonce execute. Kernel console/login pages are per-session
                // dynamic anyway, so "never stored" is an INVARIANT here, not a default: enforce it whenever the
                // effective value doesn't already forbid storing. (Being merely conditional on "no Cache-Control
                // present" was too weak — Spring Security's CacheControlHeadersWriter itself bails out when the
                // response already carries an Expires/Pragma, which can leave a nonced page with no
                // Cache-Control at all. Reported from a live deployment.) An existing value that
                // already says no-store is left untouched, so a stronger handler-set policy is never downgraded.
                if (nonce != null) {
                    String cacheControl = real.getHeader("Cache-Control");
                    if (cacheControl == null || !cacheControl.toLowerCase(Locale.ROOT).contains("no-store")) {
                        real.setHeader("Cache-Control", "no-store");
                    }
                }
            }
            settled = true;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (outputStream == null) {
                outputStream = new HeaderStampingStream(getResponse().getOutputStream());
            }
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                // The real writer's first write commits; stamp on every write() via the underlying stream is not
                // reachable through PrintWriter, so stamp up front here. The content type is set before getWriter()
                // in every render path (Spring sets it, then obtains the writer), so it is known at this point.
                applyHeaderIfHtml();
                writer = getResponse().getWriter();
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            applyHeaderIfHtml();
            super.flushBuffer();
        }

        @Override
        public void sendError(int sc) throws IOException {
            applyHeaderIfHtml();
            super.sendError(sc);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            applyHeaderIfHtml();
            super.sendError(sc, msg);
        }

        @Override
        public void sendRedirect(String location) throws IOException {
            // A redirect is not an HTML document body; do not stamp, just mark settled so a later write won't retry.
            settled = true;
            super.sendRedirect(location);
        }

        /**
         * Delegates every write to the real stream, stamping the CSP header on the very first write (before any byte
         * reaches the wire, so the response is still uncommitted at that instant). This is the path
         * {@link mx.quest.runway.head.HeadInjectionFilter} takes when it writes the spliced body directly to the
         * real response — the exact case the post-chain approach missed.
         */
        private final class HeaderStampingStream extends ServletOutputStream {

            private final ServletOutputStream delegate;

            private HeaderStampingStream(ServletOutputStream delegate) {
                this.delegate = delegate;
            }

            @Override
            public void write(int b) throws IOException {
                applyHeaderIfHtml();
                delegate.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                applyHeaderIfHtml();
                delegate.write(b, off, len);
            }

            @Override
            public void write(byte[] b) throws IOException {
                applyHeaderIfHtml();
                delegate.write(b);
            }

            @Override
            public void flush() throws IOException {
                applyHeaderIfHtml();
                delegate.flush();
            }

            @Override
            public boolean isReady() {
                return delegate.isReady();
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                delegate.setWriteListener(writeListener);
            }
        }
    }
}
