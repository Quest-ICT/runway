package mx.quest.runway.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The CSP edge filter: sets {@code Content-Security-Policy-Report-Only} by default, {@code Content-Security-Policy}
 * under enforce, nothing under off, and nothing on a non-HTML response — and never touches the existing security
 * headers.
 *
 * <p>The load-bearing case is {@link #headerSurvivesADownstreamThatCommitsTheResponse()}: it reproduces the live
 * bug where head-injection commits the response (writes the body + flushes) <em>inside the CSP filter</em>, before
 * a post-chain {@code setHeader} could run. The filter must stamp the header at commit time, not post-chain, so the
 * header survives independently of filter ordering and of whether compression is buffering.
 */
class CspFilterTest {

    private static final String REPORT_ONLY = "Content-Security-Policy-Report-Only";
    private static final String ENFORCE = "Content-Security-Policy";

    private static CspFilter filter(RunwayCspProperties.Mode mode) {
        RunwayCspProperties props = new RunwayCspProperties();
        props.setMode(mode);
        return new CspFilter(props);
    }

    /** Runs the filter over a chain that produces a response of {@code contentType}; returns the response. */
    private static MockHttpServletResponse run(CspFilter filter, String contentType) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/some/page");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            if (contentType != null) {
                res.setContentType(contentType);
            }
            res.getWriter().write("<html><head></head><body>ok</body></html>");
        };
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void reportOnlyByDefaultOnHtml() throws Exception {
        MockHttpServletResponse response = run(filter(RunwayCspProperties.Mode.REPORT), "text/html;charset=UTF-8");
        assertThat(response.getHeader(REPORT_ONLY)).contains("default-src 'self'");
        assertThat(response.getHeader(REPORT_ONLY)).contains("report-uri /__csp-report");
        assertThat(response.getHeader(REPORT_ONLY)).endsWith("report-to csp-endpoint");
        // CSP-C: the Reporting-Endpoints header is set alongside, pairing the report-to group with the collector.
        assertThat(response.getHeader("Reporting-Endpoints")).isEqualTo("csp-endpoint=\"/__csp-report\"");
        assertThat(response.getHeader(ENFORCE)).isNull();
    }

    @Test
    void enforcingHeaderUnderEnforceMode() throws Exception {
        MockHttpServletResponse response = run(filter(RunwayCspProperties.Mode.ENFORCE), "text/html");
        assertThat(response.getHeader(ENFORCE)).contains("default-src 'self'");
        assertThat(response.getHeader(REPORT_ONLY)).isNull();
    }

    @Test
    void noHeaderWhenModeOff() throws Exception {
        MockHttpServletResponse response = run(filter(RunwayCspProperties.Mode.OFF), "text/html");
        assertThat(response.getHeader(ENFORCE)).isNull();
        assertThat(response.getHeader(REPORT_ONLY)).isNull();
    }

    @Test
    void noHeaderOnNonHtmlResponse() throws Exception {
        CspFilter filter = filter(RunwayCspProperties.Mode.REPORT);
        assertThat(run(filter, "application/json").getHeader(REPORT_ONLY)).isNull();
        assertThat(run(filter, "text/css").getHeader(REPORT_ONLY)).isNull();
        assertThat(run(filter, "image/png").getHeader(REPORT_ONLY)).isNull();
        assertThat(run(filter, null).getHeader(REPORT_ONLY)).isNull(); // no content type at all
    }

    // ---- per-request nonce -------------------------------------

    private static final java.util.regex.Pattern NONCE_RE =
            java.util.regex.Pattern.compile("'nonce-([A-Za-z0-9_-]{16,})'");

    /** The substituted base64url nonce inside a policy's script-src, or {@code null} if absent. */
    private static String nonceOf(String policy) {
        if (policy == null) {
            return null;
        }
        java.util.regex.Matcher m = NONCE_RE.matcher(policy);
        return m.find() ? m.group(1) : null;
    }

    private static FilterChain htmlChain() {
        return (req, res) -> {
            res.setContentType("text/html;charset=UTF-8");
            res.getWriter().write("<html><head></head><body>ok</body></html>");
        };
    }

    @Test
    void everyHtmlResponseCarriesAFreshSubstitutedNonceMatchingTheRequestAttribute() throws Exception {
        CspFilter filter = filter(RunwayCspProperties.Mode.REPORT);

        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/app/login");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilter(req1, res1, htmlChain());

        String header1 = res1.getHeader(REPORT_ONLY);
        String nonce1 = nonceOf(header1);
        assertThat(nonce1).as("script-src carries a real substituted nonce").isNotNull();
        // The literal placeholder is always replaced before the header reaches the wire.
        assertThat(header1).doesNotContain(RunwayCspProperties.NONCE_PLACEHOLDER);
        // The header nonce equals the value published to the render layer — the core header==body invariant.
        assertThat(req1.getAttribute(CspFilter.NONCE_REQUEST_ATTRIBUTE)).isEqualTo(nonce1);

        // A second request gets a DIFFERENT nonce (kills any build-once/cached-string replay).
        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/app/login");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(req2, res2, htmlChain());
        assertThat(nonceOf(res2.getHeader(REPORT_ONLY))).isNotNull().isNotEqualTo(nonce1);
    }

    @Test
    void offModeMintsNoNonce() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(RunwayCspProperties.Mode.OFF).doFilter(request, response, htmlChain());
        assertThat(response.getHeader(REPORT_ONLY)).isNull();
        assertThat(request.getAttribute(CspFilter.NONCE_REQUEST_ATTRIBUTE))
                .as("no policy, no nonce — the filter is a pure pass-through when OFF").isNull();
    }

    // ---- strict-dynamic, per kernel surface --------------------

    private static MockHttpServletResponse runPath(CspFilter filter, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, htmlChain());
        return response;
    }

    @Test
    void strictDynamicAndNoStoreOnANoncedKernelSurface() throws Exception {
        MockHttpServletResponse res = runPath(filter(RunwayCspProperties.Mode.REPORT), "/app/login");
        String header = res.getHeader(REPORT_ONLY);
        assertThat(header).as("kernel surface gets strict-dynamic").contains("'strict-dynamic'");
        assertThat(nonceOf(header)).as("still nonced").isNotNull();
        assertThat(header).contains("'self'");                                    // old-browser fallback retained
        assertThat(header).doesNotContain(RunwayCspProperties.STRICT_DYNAMIC_PLACEHOLDER); // placeholder substituted
        assertThat(res.getHeader("Cache-Control")).as("nonced strict page must not be shared-cached").isEqualTo("no-store");
    }

    @Test
    void aPublicTenantPageGetsNeitherStrictDynamicNorANonce() throws Exception {
        // Public pages are shared-cacheable (public, max-age=300) and their scripts are not nonced by this filter.
        // A nonce there would be inert yet make an identical cacheable response vary per request (and a cache that
        // stored header+body together would serve one frozen nonce to everyone) — so emit none at all. The policy
        // is then byte-identical to pre-migration.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/some-public-page");
        request.setRequestURI("/some-public-page");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(RunwayCspProperties.Mode.REPORT).doFilter(request, res, htmlChain());

        String header = res.getHeader(REPORT_ONLY);
        assertThat(header).as("public policy is the plain pre-migration one").contains("script-src 'self';");
        assertThat(nonceOf(header)).as("no nonce on a shared-cacheable public page").isNull();
        assertThat(header).doesNotContain("nonce-");
        assertThat(header).as("a public page's un-nonced scripts must keep 'self' — no strict-dynamic")
                .doesNotContain("'strict-dynamic'");
        // Both placeholders were substituted away — neither literal ever ships.
        assertThat(header).doesNotContain(RunwayCspProperties.NONCE_PLACEHOLDER)
                .doesNotContain(RunwayCspProperties.STRICT_DYNAMIC_PLACEHOLDER);
        assertThat(request.getAttribute(CspFilter.NONCE_REQUEST_ATTRIBUTE))
                .as("no nonce published to the render layer either").isNull();
        assertThat(res.getHeader("Cache-Control")).as("public pages keep their cacheability").isNull();
    }

    // ---- the ETag-derived public-page nonce ----------------------------------------------------

    private static final String PAGE_NONCE = "PublicPageNonce0123456";

    /** A chain standing in for the page controller: publishes the page nonce, then answers {@code status}. */
    private static FilterChain pageChain(int status) {
        return (req, res) -> {
            req.setAttribute(PublicPageNonce.REQUEST_ATTRIBUTE, PAGE_NONCE); // published AFTER the CSP wrap
            HttpServletResponse http = (HttpServletResponse) res;
            http.setHeader("Cache-Control", "public, max-age=300");
            if (status == 304) {
                http.setStatus(304); // no content type, no body — exactly Spring's 304
                return;
            }
            http.setContentType("text/html;charset=UTF-8");
            res.getOutputStream().write("<html><body>p</body></html>".getBytes(StandardCharsets.UTF_8));
        };
    }

    @Test
    void aPublicPageWithAPublishedNonceGetsThatNonceAndStrictDynamicButKeepsItsCacheControl() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/about");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(RunwayCspProperties.Mode.ENFORCE).doFilter(request, res, pageChain(200));

        String header = res.getHeader(ENFORCE);
        assertThat(nonceOf(header)).as("the header carries the page's derived nonce").isEqualTo(PAGE_NONCE);
        assertThat(header).contains("script-src 'nonce-" + PAGE_NONCE + "' 'strict-dynamic' 'self'");
        assertThat(res.getHeader("Cache-Control")).as("a version-stable nonce may be shared-cached")
                .isEqualTo("public, max-age=300");
        assertThat(request.getAttribute(CspFilter.NONCE_REQUEST_ATTRIBUTE))
                .as("no per-request kernel nonce is minted on a public page").isNull();
    }

    /** The 304 has no content type and no body, yet must carry the same header as the 200 it revalidates. */
    @Test
    void aPublicPage304CarriesTheSameNoncedHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/about");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(RunwayCspProperties.Mode.REPORT).doFilter(request, res, pageChain(304));

        assertThat(res.getStatus()).isEqualTo(304);
        assertThat(nonceOf(res.getHeader(REPORT_ONLY))).isEqualTo(PAGE_NONCE);
        assertThat(res.getHeader(REPORT_ONLY)).contains("'strict-dynamic'");
    }

    /** A 304 WITHOUT a page nonce (an asset, a console resource) still gets no CSP header — unchanged behaviour. */
    @Test
    void aBare304GetsNoHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__assets/x.css");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(RunwayCspProperties.Mode.REPORT).doFilter(request, res,
                (req, r) -> ((HttpServletResponse) r).setStatus(304));
        assertThat(res.getHeader(REPORT_ONLY)).isNull();
    }

    /** The estate-wide rollback switch also drops strict-dynamic from a public page (the nonce stays additive). */
    @Test
    void strictDynamicOffGloballyAlsoAppliesToPublicPages() throws Exception {
        RunwayCspProperties props = new RunwayCspProperties();
        props.setMode(RunwayCspProperties.Mode.REPORT);
        props.setStrictDynamic(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/about");
        MockHttpServletResponse res = new MockHttpServletResponse();
        new CspFilter(props).doFilter(request, res, pageChain(200));
        assertThat(res.getHeader(REPORT_ONLY)).doesNotContain("'strict-dynamic'");
    }

    @Test
    void thePublicNonceSwitchIsOnByDefaultAndEachGateTurnsItOff() {
        RunwayCspProperties props = new RunwayCspProperties();
        assertThat(props.isPublicStrictDynamic()).isTrue();
        assertThat(props.isPublicNonceActive()).isTrue();
        props.setPublicStrictDynamic(false);
        assertThat(props.isPublicNonceActive()).isFalse();
        props.setPublicStrictDynamic(true);
        props.setStrictDynamic(false);
        assertThat(props.isPublicNonceActive()).isFalse();
        props.setStrictDynamic(true);
        props.setMode(RunwayCspProperties.Mode.OFF);
        assertThat(props.isPublicNonceActive()).isFalse();
    }

    @Test
    void aNoncedKernelPageIsForcedNoStoreEvenWhenAWeakerCacheControlWasSet() throws Exception {
        // Reported from a live deployment against 0.5.0-beta1: a nonced login page reached the wire with NO Cache-Control.
        // "Never stored" is an invariant for a nonced page, so enforce it over any value that doesn't say no-store.
        CspFilter filter = filter(RunwayCspProperties.Mode.REPORT);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/login");
        request.setRequestURI("/app/login");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(request, res, (req, response) -> {
            response.setContentType("text/html");
            ((HttpServletResponse) response).setHeader("Cache-Control", "max-age=600"); // a weaker, cacheable value
            response.getWriter().write("<html><head></head><body>ok</body></html>");
        });
        assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void aStrongerExistingNoStoreCacheControlIsNotDowngraded() throws Exception {
        CspFilter filter = filter(RunwayCspProperties.Mode.REPORT);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/login");
        request.setRequestURI("/app/login");
        MockHttpServletResponse res = new MockHttpServletResponse();
        String springSecurityValue = "no-cache, no-store, max-age=0, must-revalidate";
        filter.doFilter(request, res, (req, response) -> {
            response.setContentType("text/html");
            ((HttpServletResponse) response).setHeader("Cache-Control", springSecurityValue);
            response.getWriter().write("<html><head></head><body>ok</body></html>");
        });
        assertThat(res.getHeader("Cache-Control")).isEqualTo(springSecurityValue);
    }

    @Test
    void strictDynamicOffGloballyEvenOnKernelSurfaces() throws Exception {
        RunwayCspProperties props = new RunwayCspProperties();
        props.setStrictDynamic(false);
        MockHttpServletResponse res = runPath(new CspFilter(props), "/app/login");
        String header = res.getHeader(REPORT_ONLY);
        assertThat(nonceOf(header)).as("rollback keeps the nonce on kernel surfaces").isNotNull();
        assertThat(header).as("flag off → nonce-only, no strict-dynamic").doesNotContain("'strict-dynamic'");
        assertThat(res.getHeader("Cache-Control"))
                .as("a NONCED page is never shared-cached, with or without strict-dynamic").isEqualTo("no-store");
    }

    @Test
    void setupIsNotAStrictSurfaceSoATenantPageAtThatSlugIsNotBlocked() throws Exception {
        // /setup has NO owning controller in ACTIVE mode (the wizard is @Conditional on WIZARD mode) and is not
        // reserved, so a tenant may publish a public page at slug "setup". It must NOT get strict-dynamic, which
        // would block that page's un-nonced scripts under enforce.
        MockHttpServletResponse res = runPath(filter(RunwayCspProperties.Mode.REPORT), "/setup");
        String header = res.getHeader(REPORT_ONLY);
        assertThat(header).as("/setup is a tenant-occupiable slug → treated exactly like a public page")
                .contains("script-src 'self';").doesNotContain("'strict-dynamic'").doesNotContain("nonce-");
        assertThat(res.getHeader("Cache-Control")).as("stays cacheable like any public page").isNull();
    }

    @Test
    void defersToAHandlerThatSetItsOwnCspEvenUnderADifferentModeHeaderName() throws Exception {
        // A handler (e.g. the sandboxed page preview) sets its own enforce-name CSP. The filter in REPORT mode must
        // NOT stamp a second (report-only) header alongside it — that would flood /__csp-report during the bake.
        CspFilter filter = filter(RunwayCspProperties.Mode.REPORT);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__admin/pages/x/preview");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            res.setContentType("text/html");
            ((HttpServletResponse) res).setHeader("Content-Security-Policy", "sandbox allow-scripts");
            res.getWriter().write("<html><head></head><body>ok</body></html>");
        };
        filter.doFilter(request, response, chain);
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("sandbox allow-scripts");
        assertThat(response.getHeader(REPORT_ONLY))
                .as("no second (report-only) CSP header alongside the handler's own").isNull();
    }

    @Test
    void doesNotDisturbOtherSecurityHeaders() throws Exception {
        CspFilter filter = filter(RunwayCspProperties.Mode.REPORT);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/page");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            res.setContentType("text/html");
            ((HttpServletResponse) res).setHeader("X-Frame-Options", "SAMEORIGIN");
            ((HttpServletResponse) res).setHeader("X-Content-Type-Options", "nosniff");
            res.getWriter().write("<html><head></head><body>ok</body></html>");
        };
        filter.doFilter(request, response, chain);
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader(REPORT_ONLY)).isNotNull(); // CSP added alongside, not replacing
    }

    /**
     * Regression for the live bug: a plain request (no {@code Accept-Encoding}, so compression never buffers) lost
     * the CSP header entirely. Root cause — the inner {@code HeadInjectionFilter} writes the spliced body and
     * <b>commits</b> the response inside its post, which runs before CSP's post; a real servlet container then
     * drops the post-chain {@code setHeader}. This test mimics that exactly: the downstream sets {@code text/html},
     * writes the body via the output stream, and {@code flushBuffer()}s — committing the response — all <em>inside</em>
     * the CSP filter. The response honors the real-container contract (once committed, {@code setHeader} is a no-op),
     * unlike a bare {@link MockHttpServletResponse}. The header MUST still be present.
     */
    @Test
    void headerSurvivesADownstreamThatCommitsTheResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/login"); // no Accept-Encoding
        MockHttpServletResponse real = new MockHttpServletResponse();
        CommitEnforcingResponse response = new CommitEnforcingResponse(real);
        FilterChain committingChain = (req, res) -> {
            HttpServletResponse http = (HttpServletResponse) res;
            http.setContentType("text/html;charset=UTF-8");
            // Write the body straight to the output stream (as HeadInjectionFilter does) and flush → commit.
            res.getOutputStream().write("<html><head></head><body>ok</body></html>".getBytes(StandardCharsets.UTF_8));
            res.flushBuffer();
            // After commit, any late header set must be ignored — as it would be on the real container.
            http.setHeader("X-Late", "dropped");
        };

        filter(RunwayCspProperties.Mode.REPORT).doFilter(request, response, committingChain);

        assertThat(real.isCommitted()).as("downstream must have committed the response").isTrue();
        assertThat(real.getHeader(REPORT_ONLY)).as("CSP header must survive a commit-before-post").isNotNull();
        assertThat(real.getHeader(REPORT_ONLY)).contains("default-src 'self'");
        assertThat(real.getHeader("X-Late")).as("late header proves the commit contract is enforced").isNull();
        assertThat(real.getContentAsString()).isEqualTo("<html><head></head><body>ok</body></html>");
    }

    /** The same commit-before-post scenario under enforce mode delivers the enforcing header name. */
    @Test
    void enforcingHeaderSurvivesCommitBeforePost() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/login");
        MockHttpServletResponse real = new MockHttpServletResponse();
        CommitEnforcingResponse response = new CommitEnforcingResponse(real);
        FilterChain committingChain = (req, res) -> {
            ((HttpServletResponse) res).setContentType("text/html");
            res.getOutputStream().write("<html><head></head><body>ok</body></html>".getBytes(StandardCharsets.UTF_8));
            res.flushBuffer();
        };

        filter(RunwayCspProperties.Mode.ENFORCE).doFilter(request, response, committingChain);

        assertThat(real.getHeader(ENFORCE)).isNotNull();
        assertThat(real.getHeader(REPORT_ONLY)).isNull();
    }

    /** A non-HTML committing response (e.g. an asset streamed and flushed) still gets no CSP header. */
    @Test
    void noHeaderOnCommittingNonHtmlResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/asset.js");
        MockHttpServletResponse real = new MockHttpServletResponse();
        CommitEnforcingResponse response = new CommitEnforcingResponse(real);
        FilterChain committingChain = (req, res) -> {
            ((HttpServletResponse) res).setContentType("application/javascript");
            res.getOutputStream().write("console.log(1)".getBytes(StandardCharsets.UTF_8));
            res.flushBuffer();
        };

        filter(RunwayCspProperties.Mode.REPORT).doFilter(request, response, committingChain);

        assertThat(real.getHeader(REPORT_ONLY)).isNull();
    }

    /**
     * A response wrapper that faithfully models a real servlet container's commit contract, which
     * {@link MockHttpServletResponse} does not: once the response is committed, header mutations are dropped, and
     * flushing / writing past the buffer commits the response. Only what the CSP fix relies on is modeled; the
     * body and header state live on the wrapped {@link MockHttpServletResponse} so tests can assert against it.
     */
    private static final class CommitEnforcingResponse extends HttpServletResponseWrapper {

        private final MockHttpServletResponse real;

        private CommitEnforcingResponse(MockHttpServletResponse real) {
            super(real);
            this.real = real;
        }

        @Override
        public void setHeader(String name, String value) {
            if (!real.isCommitted()) {
                super.setHeader(name, value);
            }
        }

        @Override
        public void addHeader(String name, String value) {
            if (!real.isCommitted()) {
                super.addHeader(name, value);
            }
        }

        @Override
        public boolean containsHeader(String name) {
            return real.containsHeader(name);
        }

        @Override
        public boolean isCommitted() {
            return real.isCommitted();
        }

        @Override
        public void flushBuffer() throws IOException {
            real.setCommitted(true);
            super.flushBuffer();
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            return new CommitOnWriteStream(super.getOutputStream());
        }

        /** Marks the response committed the moment the first byte is written, as a container's buffer flush would. */
        private final class CommitOnWriteStream extends ServletOutputStream {

            private final ServletOutputStream delegate;

            private CommitOnWriteStream(ServletOutputStream delegate) {
                this.delegate = delegate;
            }

            @Override
            public void write(int b) throws IOException {
                real.setCommitted(true);
                delegate.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                real.setCommitted(true);
                delegate.write(b, off, len);
            }

            @Override
            public void write(byte[] b) throws IOException {
                real.setCommitted(true);
                delegate.write(b);
            }

            @Override
            public void flush() throws IOException {
                real.setCommitted(true);
                delegate.flush();
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
            }
        }
    }
}
