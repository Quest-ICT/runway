package mx.quest.runway.head;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Every branch of the edge head-injection filter: splice, all the pass-throughs, charset, overflow. */
class HeadInjectionFilterTest {

    private static final String MARKER = "<meta name=\"runway\" content=\"x\">";

    private static HeadContext context() {
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
        PageMeta page = new PageMeta("about", "About", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        return new HeadContext(site, page, Locale.ENGLISH, "https://acme.test/about", "https://acme.test/about",
                List.of(), false, false);
    }

    private static HeadContributorRegistry registryEmitting(String markup) {
        return new HeadContributorRegistry(List.of(new HeadContributor() {
            @Override public String key() { return "test"; }
            @Override public String render(HeadContext context) { return markup; }
        }));
    }

    /** Runs the filter over a chain that writes {@code body} with {@code contentType}; returns the response. */
    private static MockHttpServletResponse run(HeadInjectionFilter filter, MockHttpServletRequest request,
                                               String body, String contentType) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            res.setContentType(contentType);
            res.getWriter().write(body);
        };
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest requestWithContext() {
        return requestWith(context());
    }

    private static HeadContext context(Locale locale) {
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH, locale), "https://acme.test");
        PageMeta page = new PageMeta("about", "About", "", List.of(Locale.ENGLISH, locale), Locale.ENGLISH, true, false);
        return new HeadContext(site, page, locale, "https://acme.test/about", "https://acme.test/about",
                List.of(), false, false);
    }

    private static MockHttpServletRequest requestWith(HeadContext ctx) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/about");
        request.setAttribute(HeadInjectionFilter.HEAD_CONTEXT_ATTRIBUTE, ctx);
        return request;
    }

    @Test
    void setsHtmlLangFromTheResolvedLocaleReplacingAHardcodedOne() throws Exception {
        // A registry with a contributor that emits NOTHING — proves the lang is set even with no head markup.
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting(""));
        String html = "<!DOCTYPE html>\n<html lang=\"en\"><head><title>t</title></head><body>x</body></html>";
        String out = run(filter, requestWith(context(Locale.forLanguageTag("es"))), html, "text/html;charset=UTF-8")
                .getContentAsString();
        assertTrue(out.contains("<html lang=\"es\">"), out);
        assertFalse(out.contains("lang=\"en\""), "the master's hardcoded lang=en must be replaced by the served locale");
    }

    @Test
    void insertsHtmlLangWhenTheTagHasNone() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting(""));
        String html = "<html class=\"x\"><head></head><body></body></html>";
        String out = run(filter, requestWith(context(Locale.forLanguageTag("fr"))), html, "text/html;charset=UTF-8")
                .getContentAsString();
        assertTrue(out.contains("<html lang=\"fr\" class=\"x\">"), out);
    }

    @Test
    void leavesABodyWithNoHtmlTagUntouched() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting(""));
        String fragment = "<div>just a fragment</div>";
        String out = run(filter, requestWith(context(Locale.forLanguageTag("es"))), fragment, "text/html;charset=UTF-8")
                .getContentAsString();
        assertEquals(fragment, out);
    }

    @Test
    void splicesMarkupBeforeClosingHead() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting(MARKER));
        String html = "<html><head><title>t</title></head><body>b</body></html>";

        String out = run(filter, requestWithContext(), html, "text/html;charset=UTF-8")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(out.contains(MARKER), out);
        assertTrue(out.indexOf(MARKER) < out.indexOf("</head>"), "marker must precede </head>");
    }

    @Test
    void passesThroughWhenNoContextStashed() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting(MARKER));
        String html = "<html><head></head><body>b</body></html>";

        String out = run(filter, new MockHttpServletRequest("GET", "/about"), html, "text/html;charset=UTF-8")
                .getContentAsString(StandardCharsets.UTF_8);

        assertEquals(html, out);
    }

    @Test
    void passesThroughWhenNotHtml() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting(MARKER));
        String json = "{\"</head>\":1}"; // even a literal </head> in a non-HTML body is left alone

        String out = run(filter, requestWithContext(), json, "application/json")
                .getContentAsString(StandardCharsets.UTF_8);

        assertEquals(json, out);
    }

    @Test
    void passesThroughWhenNoClosingHead() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting(MARKER));
        String html = "<html><body>no head here</body></html>";

        String out = run(filter, requestWithContext(), html, "text/html;charset=UTF-8")
                .getContentAsString(StandardCharsets.UTF_8);

        // No </head> → the markup is NOT spliced; the lang is still set (independent of head injection).
        assertFalse(out.contains(MARKER));
        assertEquals("<html lang=\"en\"><body>no head here</body></html>", out);
    }

    @Test
    void matchesClosingHeadCaseInsensitively() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting(MARKER));
        String html = "<HTML><HEAD></HEAD><BODY>b</BODY></HTML>";

        String out = run(filter, requestWithContext(), html, "text/html;charset=UTF-8")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(out.contains(MARKER), out);
        assertTrue(out.indexOf(MARKER) < out.indexOf("</HEAD>"), "marker must precede </HEAD>");
    }

    @Test
    void emptyRegistryPassesThroughUnbuffered() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(new HeadContributorRegistry(List.of()));
        String html = "<html><head></head><body>b</body></html>";

        String out = run(filter, requestWithContext(), html, "text/html;charset=UTF-8")
                .getContentAsString(StandardCharsets.UTF_8);

        assertEquals(html, out);
    }

    @Test
    void headRequestIsNotBufferedSoTheHandlersContentLengthSurvives() throws Exception {
        // A HEAD carries no body to inject <head> markup into. Spring's ResourceHttpRequestHandler serves a HEAD
        // /__assets/** by setting Content-Length and writing NO body. With a NON-empty registry (so the filter
        // would otherwise buffer), the body-less HEAD used to be captured as an empty buffer and re-stamped
        // Content-Length: 0 (BufferingResponseWrapper withholds the handler's real length) — the observed
        // `HEAD /__assets/*.js -> Content-Length: 0`. HEAD must now pass straight through, preserving the length.
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting(MARKER));
        MockHttpServletRequest request = new MockHttpServletRequest("HEAD", "/__assets/captcha-v3.js");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            res.setContentType("text/javascript");
            res.setContentLength(2173); // what a GET returns; a HEAD writes no body (ResourceHttpRequestHandler)
        };
        filter.doFilter(request, response, chain);
        assertEquals(2173, response.getContentLength(), "the handler's Content-Length survives HEAD (not zeroed)");
        assertEquals(0, response.getContentAsByteArray().length, "a HEAD has no body");
    }

    @Test
    void blankContributorMarkupPassesThrough() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting("   "));
        String html = "<html><head></head><body>b</body></html>";

        String out = run(filter, requestWithContext(), html, "text/html;charset=UTF-8")
                .getContentAsString(StandardCharsets.UTF_8);

        // Blank contributor markup → nothing spliced into <head>; only the lang is set.
        assertEquals("<html lang=\"en\"><head></head><body>b</body></html>", out);
    }

    @Test
    void preservesNonAsciiContentAroundTheSplice() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting(MARKER));
        String html = "<html><head><title>Niño · café</title></head><body>Señor André</body></html>";

        String out = run(filter, requestWithContext(), html, "text/html;charset=UTF-8")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(out.contains(MARKER), out);
        assertTrue(out.contains("Niño · café"), "head text must round-trip");
        assertTrue(out.contains("Señor André"), "body text must round-trip");
    }

    @Test
    void overflowStreamsThroughWithoutInjecting() throws Exception {
        // Tiny cap forces overflow on a body larger than 8 bytes; the filter must not rewrite it.
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting(MARKER), 8L);
        String html = "<html><head></head><body>well past eight bytes</body></html>";

        String out = run(filter, requestWithContext(), html, "text/html;charset=UTF-8")
                .getContentAsString(StandardCharsets.UTF_8);

        assertEquals(html, out);
        assertFalse(out.contains(MARKER));
    }

    // ---- the public-page nonce stamp ----------------------------------------------------------

    private static final String NONCE = "PageNonce_0123456789ab";

    private static mx.quest.runway.web.PublicPageNonce nonceConfigured(boolean configured) {
        return new mx.quest.runway.web.PublicPageNonce(configured, java.nio.file.Path.of("unused.key"));
    }

    /** A chain standing in for the page controller: publishes the nonce, then renders {@code body}. */
    private static MockHttpServletResponse runPage(HeadInjectionFilter filter, MockHttpServletRequest request,
                                                   String body) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            req.setAttribute(mx.quest.runway.web.PublicPageNonce.REQUEST_ATTRIBUTE, NONCE);
            res.setContentType("text/html;charset=UTF-8");
            res.getWriter().write(body);
        };
        filter.doFilter(request, response, chain);
        return response;
    }

    /**
     * The latent Phase-B bug: with NO head contributors the filter used to skip buffering entirely, so a site
     * without contributors would have shipped a nonced header over an un-nonced body. Buffering is now decoupled.
     */
    @Test
    void stampsTheNonceEvenWhenNoHeadContributorExists() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(new HeadContributorRegistry(List.of()),
                nonceConfigured(true));
        String out = runPage(filter, requestWithContext(),
                "<html><head></head><body><script src=\"/a.js\"></script>"
                        + "<script type=\"application/ld+json\">{}</script></body></html>")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(out.contains("<script nonce=\"" + NONCE + "\" src=\"/a.js\">"), out);
        assertTrue(out.contains("<script type=\"application/ld+json\">{}</script>"), "JSON-LD is never stamped: " + out);
    }

    /**
     * Scripts a head contributor splices in (e.g. an analytics loader) are judged too — the stamp runs last — and
     * stamped exactly when the allowlist policy would have run them: an unlisted host stays blocked.
     */
    @Test
    void aScriptAHeadContributorInjectedIsStampedOnlyWhenItsHostIsAllowlisted() throws Exception {
        String loader = "<script async src=\"https://analytics.test/a.js\"></script>";
        HeadInjectionFilter unlisted = new HeadInjectionFilter(registryEmitting(loader), nonceConfigured(true));
        String blocked = runPage(unlisted, requestWithContext(), "<html><head></head><body>b</body></html>")
                .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(blocked.contains(loader), "an unlisted host is not stamped: " + blocked);

        HeadInjectionFilter listed = new HeadInjectionFilter(registryEmitting(loader), nonceConfigured(true),
                mx.quest.runway.web.CspScriptAllowlist.parse("'self' https://analytics.test"));
        String out = runPage(listed, requestWithContext(), "<html><head></head><body>b</body></html>")
                .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(out.contains("<script nonce=\"" + NONCE + "\" async src=\"https://analytics.test/a.js\">"), out);
    }

    /** An injected inline script and a foreign-host script in the rendered page are left exactly as rendered. */
    @Test
    void injectedScriptsInThePageAreNotStamped() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(new HeadContributorRegistry(List.of()),
                nonceConfigured(true), mx.quest.runway.web.CspScriptAllowlist.parse("'self' https://www.google.com"));
        String body = "<html><head></head><body><h1>Results for: <script>alert(document.cookie)</script></h1>"
                + "<script src=\"https://evil.example/x.js\"></script>"
                + "<script src=\"https://www.google.com/recaptcha/api.js\"></script></body></html>";
        String out = runPage(filter, requestWithContext(), body).getContentAsString(StandardCharsets.UTF_8);
        assertTrue(out.contains("<script>alert(document.cookie)</script>"), out);
        assertTrue(out.contains("<script src=\"https://evil.example/x.js\">"), out);
        assertTrue(out.contains("<script nonce=\"" + NONCE + "\" src=\"https://www.google.com/recaptcha/api.js\">"),
                out);
    }

    /** 'self' is this request's origin: an absolute URL back to the requested host is same-origin, another is not. */
    @Test
    void selfIsTheRequestOrigin() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(new HeadContributorRegistry(List.of()),
                nonceConfigured(true));
        MockHttpServletRequest request = requestWithContext();
        request.setScheme("https");
        request.setServerName("acme.test");
        request.setServerPort(443);
        request.setRequestURI("/blog/post");
        String body = "<html><head></head><body><script src=\"https://acme.test/a.js\"></script>"
                + "<script src=\"https://other.test/a.js\"></script><script src=\"b.js\"></script></body></html>";
        String out = runPage(filter, request, body).getContentAsString(StandardCharsets.UTF_8);
        assertTrue(out.contains("<script nonce=\"" + NONCE + "\" src=\"https://acme.test/a.js\">"), out);
        assertTrue(out.contains("<script src=\"https://other.test/a.js\">"), out);
        assertTrue(out.contains("<script nonce=\"" + NONCE + "\" src=\"b.js\">"), out);
    }

    @Test
    void theDocumentUrlComesFromTheRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a/b");
        request.setScheme("https");
        request.setServerName("acme.test");
        request.setServerPort(8443);
        assertEquals(java.net.URI.create("https://acme.test:8443/a/b"), HeadInjectionFilter.documentUrl(request));
        request.setServerName("::1");
        request.setRequestURI("/bad path");
        assertEquals(java.net.URI.create("https://[::1]:8443/"), HeadInjectionFilter.documentUrl(request),
                "an IPv6 literal is bracketed; an invalid path falls back to the root");
    }

    /** No published nonce (an error page, a plugin endpoint) ⇒ the body is not touched. */
    @Test
    void noPublishedNonceMeansNoStamp() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(new HeadContributorRegistry(List.of()),
                nonceConfigured(true));
        String html = "<html><head></head><body><script>x()</script></body></html>";
        assertEquals(html, run(filter, new MockHttpServletRequest("GET", "/x"), html, "text/html")
                .getContentAsString(StandardCharsets.UTF_8));
    }

    /** A non-HTML body is never stamped, even if a nonce was somehow published. */
    @Test
    void aNonHtmlBodyIsNeverStamped() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(new HeadContributorRegistry(List.of()),
                nonceConfigured(true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/feed");
        request.setAttribute(mx.quest.runway.web.PublicPageNonce.REQUEST_ATTRIBUTE, NONCE);
        String js = "<script>not html</script>";
        assertEquals(js, run(filter, request, js, "application/javascript").getContentAsString(StandardCharsets.UTF_8));
    }

    /** HEAD keeps bypassing buffering (buffering a HEAD zeroes its Content-Length), nonce feature or not. */
    @Test
    void headStillBypassesBufferingWithTheNonceFeatureOn() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(new HeadContributorRegistry(List.of()),
                nonceConfigured(true));
        MockHttpServletRequest request = new MockHttpServletRequest("HEAD", "/about");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> res.setContentLength(4242));
        assertEquals(4242, response.getContentLength(), "the handler's Content-Length survives HEAD");
    }

    /** With the feature off and no contributors, the filter does not buffer at all (the pre-Phase-B fast path). */
    @Test
    void featureOffAndNoContributorsStillSkipsBuffering() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(new HeadContributorRegistry(List.of()),
                nonceConfigured(false));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/about");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> res.setContentLength(4242));
        assertEquals(4242, response.getContentLength(), "unbuffered: the handler's own length passes through");
    }

    /**
     * Overflow: the body streams out unstamped, so the nonce is withdrawn BEFORE the first byte (the CSP header,
     * stamped at commit, then falls back to the allowlist) and the response is marked no-store so no cache can
     * later pair this body with a nonced 304 header.
     */
    @Test
    void overflowWithdrawsTheNonceAndForbidsStorage() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(new HeadContributorRegistry(List.of()), 8L,
                nonceConfigured(true));
        MockHttpServletRequest request = requestWithContext();
        String html = "<html><head></head><body><script>x()</script></body></html>";
        MockHttpServletResponse response = new MockHttpServletResponse();
        Object[] attributeAtFirstByte = new Object[1];
        jakarta.servlet.http.HttpServletResponseWrapper probe =
                new jakarta.servlet.http.HttpServletResponseWrapper(response) {
                    @Override
                    public jakarta.servlet.ServletOutputStream getOutputStream() throws java.io.IOException {
                        attributeAtFirstByte[0] = request.getAttribute(mx.quest.runway.web.PublicPageNonce.REQUEST_ATTRIBUTE);
                        return super.getOutputStream();
                    }
                };
        FilterChain chain = (req, res) -> {
            req.setAttribute(mx.quest.runway.web.PublicPageNonce.REQUEST_ATTRIBUTE, NONCE);
            ((jakarta.servlet.http.HttpServletResponse) res).setHeader("Cache-Control", "public, max-age=300");
            res.setContentType("text/html;charset=UTF-8");
            res.getWriter().write(html);
        };
        filter.doFilter(request, probe, chain);

        assertEquals(html, response.getContentAsString(StandardCharsets.UTF_8), "streamed verbatim");
        assertEquals(null, attributeAtFirstByte[0], "the nonce was withdrawn before the body reached the response");
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    /**
     * A srcdoc frame inherits the page's policy, nonce included, and the scanner cannot
     * override a nonce inside an attribute value. If the unstamped body already carries the page's own nonce (only
     * possible through an input the ETag does not fold), the response gets no nonce at all — withdrawn before the
     * first byte, so CspFilter emits the host-allowlist policy — no stamp, no validators and no-store.
     */
    @Test
    void aBodyThatAlreadyCarriesThePageNonceGetsNoNonceAtAll() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting(MARKER), nonceConfigured(true));
        String html = "<html><head></head><body><script src=\"/a.js\"></script>"
                + "<iframe srcdoc=\"&lt;script nonce=&quot;" + NONCE + "&quot;&gt;parent.x()&lt;/script&gt;\">"
                + "</iframe></body></html>";
        MockHttpServletRequest request = requestWithContext();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Object[] attributeAtFirstByte = {"unset"};
        jakarta.servlet.http.HttpServletResponseWrapper probe =
                new jakarta.servlet.http.HttpServletResponseWrapper(response) {
                    @Override
                    public jakarta.servlet.ServletOutputStream getOutputStream() throws java.io.IOException {
                        attributeAtFirstByte[0] = request.getAttribute(mx.quest.runway.web.PublicPageNonce.REQUEST_ATTRIBUTE);
                        return super.getOutputStream();
                    }
                };
        FilterChain chain = (req, res) -> {
            req.setAttribute(mx.quest.runway.web.PublicPageNonce.REQUEST_ATTRIBUTE, NONCE);
            jakarta.servlet.http.HttpServletResponse http = (jakarta.servlet.http.HttpServletResponse) res;
            http.addHeader("ETag", "\"v1\"");
            http.addHeader("Cache-Control", "public, max-age=300");
            res.setContentType("text/html;charset=UTF-8");
            res.getWriter().write(html);
        };
        filter.doFilter(request, probe, chain);

        String out = response.getContentAsString(StandardCharsets.UTF_8);
        assertNull(attributeAtFirstByte[0], "the nonce was withdrawn before the body reached the response");
        assertTrue(out.contains("<script src=\"/a.js\"></script>"), "nothing is stamped: " + out);
        assertTrue(out.contains(MARKER), "the head splice still happens: " + out);
        assertFalse(response.containsHeader("ETag"), "no validator may describe this copy");
        assertEquals("no-store", response.getHeader("Cache-Control"));

        // The same page without the copy is stamped and keeps its validators.
        MockHttpServletRequest clean = requestWithContext();
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(clean, ok, (req, res) -> {
            req.setAttribute(mx.quest.runway.web.PublicPageNonce.REQUEST_ATTRIBUTE, NONCE);
            ((jakarta.servlet.http.HttpServletResponse) res).addHeader("ETag", "\"v1\"");
            res.setContentType("text/html;charset=UTF-8");
            res.getWriter().write("<html><head></head><body><script src=\"/a.js\"></script></body></html>");
        });
        assertTrue(ok.getContentAsString(StandardCharsets.UTF_8).contains("<script nonce=\"" + NONCE + "\""));
        assertEquals(NONCE, clean.getAttribute(mx.quest.runway.web.PublicPageNonce.REQUEST_ATTRIBUTE));
        assertEquals("\"v1\"", ok.getHeader("ETag"));
    }

    // ---- foreign script-class preloads are removed where the nonce is in force -----------------

    private static final String FOREIGN_PRELOAD = "<link rel=\"modulepreload\" href=\"https://evil.example/m.js\">";
    private static final String FOREIGN_PREFETCH = "<link rel=\"prefetch\" href=\"//evil.example/p\">";
    private static final String OWN_PRELOAD = "<link rel=\"modulepreload\" href=\"/static/m.js\">";

    /** A chain that publishes the nonce (when {@code nonce} is set), stamps validators, then writes {@code html}. */
    private static FilterChain page(String html, String nonce) {
        return (req, res) -> {
            if (nonce != null) {
                req.setAttribute(mx.quest.runway.web.PublicPageNonce.REQUEST_ATTRIBUTE, nonce);
            }
            jakarta.servlet.http.HttpServletResponse http = (jakarta.servlet.http.HttpServletResponse) res;
            http.addHeader("ETag", "\"v1\"");
            http.addHeader("Cache-Control", "public, max-age=300");
            res.setContentType("text/html;charset=UTF-8");
            res.getWriter().write(html);
        };
    }

    /**
     * Under the nonce, a foreign modulepreload / prefetch goes — including one a head contributor spliced in — while a
     * same-origin preload and its module script stay (the script stamped); the validators are kept, because the
     * removal is a deterministic function of the body the ETag already describes.
     */
    @Test
    void aForeignScriptPreloadIsRemovedUnderTheNonceAndAnOwnOneStays() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting(FOREIGN_PREFETCH), nonceConfigured(true));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(requestWithContext(), response, page("<html><head>" + FOREIGN_PRELOAD + OWN_PRELOAD
                + "</head><body><script type=\"module\" src=\"/static/m.js\"></script></body></html>", NONCE));

        String out = response.getContentAsString(StandardCharsets.UTF_8);
        assertFalse(out.contains("evil.example"), "both foreign links removed (page and head contributor): " + out);
        assertTrue(out.contains(OWN_PRELOAD), "the same-origin preload is kept as written: " + out);
        assertTrue(out.contains("<script nonce=\"" + NONCE + "\" type=\"module\" src=\"/static/m.js\">"), out);
        assertEquals("\"v1\"", response.getHeader("ETag"), "the body under one ETag is still one body");
        assertEquals("public, max-age=300", response.getHeader("Cache-Control"));
    }

    /** No nonce (the host-allowlist policy, which blocks these fetches by itself) ⇒ nothing is removed. */
    @Test
    void withoutANonceNothingIsRemoved() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting(MARKER), nonceConfigured(true));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(requestWithContext(), response,
                page("<html><head>" + FOREIGN_PRELOAD + "</head><body>b</body></html>", null));
        String out = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(out.contains(FOREIGN_PRELOAD), out);
        assertTrue(out.contains(MARKER), "the head splice still happens: " + out);
    }

    /** The overflow path withdraws the nonce and streams verbatim: nothing is removed there either. */
    @Test
    void anOverflowingBodyIsNotRewritten() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting(MARKER), 64, nonceConfigured(true));
        String html = "<html><head>" + FOREIGN_PRELOAD + "</head><body>" + "x".repeat(200) + "</body></html>";
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(requestWithContext(), response, page(html, NONCE));
        assertEquals(html, response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    /**
     * A foreign preload where the scanner cannot remove it (here: an SVG {@code <title>}, which the browser does not
     * treat as raw text) withdraws the nonce before the first byte — CspFilter then emits the host-allowlist policy,
     * which blocks the fetch — and the body goes out untouched, unstamped, without validators and no-store, exactly
     * like a copied nonce.
     */
    @Test
    void aHiddenForeignPreloadWithdrawsTheNonce() throws Exception {
        HeadInjectionFilter filter = new HeadInjectionFilter(registryEmitting(MARKER), nonceConfigured(true));
        String html = "<html><head></head><body>" + FOREIGN_PRELOAD + "<script src=\"/a.js\"></script>"
                + "<svg><title>" + FOREIGN_PRELOAD + "</title></svg></body></html>";
        MockHttpServletRequest request = requestWithContext();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Object[] attributeAtFirstByte = {"unset"};
        jakarta.servlet.http.HttpServletResponseWrapper probe =
                new jakarta.servlet.http.HttpServletResponseWrapper(response) {
                    @Override
                    public jakarta.servlet.ServletOutputStream getOutputStream() throws java.io.IOException {
                        attributeAtFirstByte[0] = request.getAttribute(mx.quest.runway.web.PublicPageNonce.REQUEST_ATTRIBUTE);
                        return super.getOutputStream();
                    }
                };
        filter.doFilter(request, probe, page(html, NONCE));

        String out = response.getContentAsString(StandardCharsets.UTF_8);
        assertNull(attributeAtFirstByte[0], "the nonce was withdrawn before the body reached the response");
        assertTrue(out.contains("<body>" + FOREIGN_PRELOAD + "<script src=\"/a.js\"></script>"),
                "nothing removed, nothing stamped: " + out);
        assertTrue(out.contains(MARKER), "the head splice still happens: " + out);
        assertFalse(response.containsHeader("ETag"), "no validator may describe this response");
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    // --- a throwing head contributor degrades the page to uncacheable --------------------------------

    /** A chain that stamps validators the way the page controller does, then writes an HTML page. */
    private static MockHttpServletResponse runWithValidators(HeadInjectionFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            jakarta.servlet.http.HttpServletResponse http = (jakarta.servlet.http.HttpServletResponse) res;
            http.addHeader("ETag", "\"v1\"");
            http.setDateHeader("Last-Modified", 1_700_000_000_000L);
            http.addHeader("Cache-Control", "public, max-age=60");
            res.setContentType("text/html;charset=UTF-8");
            res.getWriter().write("<html><head><title>t</title></head><body>b</body></html>");
        };
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void aHealthyRenderKeepsTheControllersValidators() throws Exception {
        MockHttpServletResponse response = runWithValidators(new HeadInjectionFilter(registryEmitting(MARKER)),
                requestWithContext());
        assertEquals("\"v1\"", response.getHeader("ETag"));
        assertTrue(response.containsHeader("Last-Modified"));
        assertEquals("public, max-age=60", response.getHeader("Cache-Control"));
        assertTrue(response.getContentAsString().contains(MARKER));
    }

    @Test
    void aThrowingContributorIsDroppedAndThePageGoesOutNoStoreWithoutValidators() throws Exception {
        HeadContributorRegistry registry = new HeadContributorRegistry(List.of(
                new HeadContributor() {
                    @Override public String key() { return "broken"; }
                    @Override public String render(HeadContext context) { throw new IllegalStateException("x"); }
                },
                new HeadContributor() {
                    @Override public String key() { return "fine"; }
                    @Override public String render(HeadContext context) { return MARKER; }
                }));
        MockHttpServletResponse response = runWithValidators(new HeadInjectionFilter(registry), requestWithContext());

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains(MARKER), "the healthy contributor still contributes");
        assertFalse(response.containsHeader("ETag"), "no validator may describe the degraded copy");
        assertFalse(response.containsHeader("Last-Modified"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @Test
    void anEarlierSubstitutionInTheRequestAlsoDegradesThePage() throws Exception {
        MockHttpServletRequest request = requestWithContext();
        request.setAttribute(mx.quest.runway.plugin.ContributionGuard.DEGRADED_ATTRIBUTE, Boolean.TRUE);
        MockHttpServletResponse response = runWithValidators(new HeadInjectionFilter(registryEmitting(MARKER)),
                request);
        assertFalse(response.containsHeader("ETag"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @Test
    void validatorsReachANonHtmlResponseUnchanged() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            ((jakarta.servlet.http.HttpServletResponse) res).setHeader("ETag", "\"css\"");
            res.setContentType("text/css");
            res.getWriter().write("body{}");
        };
        new HeadInjectionFilter(registryEmitting(MARKER)).doFilter(requestWithContext(), response, chain);
        assertEquals("\"css\"", response.getHeader("ETag"));
    }

    /**
     * A response the handler ended with {@code sendError} (the resource handler's 404 for a missing asset) belongs to
     * the container: the filter writes nothing into it and releases no validator. The response below throws on a
     * write after {@code sendError}, as Jetty's closed output does (its {@code EofException} was logged per 404).
     */
    @Test
    void aSendErrorResponseIsLeftToTheContainer() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse() {
            @Override
            public jakarta.servlet.ServletOutputStream getOutputStream() {
                if (getStatus() >= 400) {
                    throw new IllegalStateException("the container closed this response (sendError)");
                }
                return super.getOutputStream();
            }
        };
        FilterChain chain = (req, res) -> {
            HttpServletResponse http = (HttpServletResponse) res;
            http.setHeader("ETag", "\"x\"");
            res.setContentType("text/html");
            res.getWriter().write("<html><head></head><body>partial</body></html>");
            http.sendError(404);
        };

        new HeadInjectionFilter(registryEmitting(MARKER)).doFilter(requestWithContext(), response, chain);

        assertEquals(404, response.getStatus());
        assertNull(response.getHeader("ETag"), "an error response carries no held-back validator");
        assertEquals(0, response.getContentAsByteArray().length, "nothing written by the filter");
    }
}
