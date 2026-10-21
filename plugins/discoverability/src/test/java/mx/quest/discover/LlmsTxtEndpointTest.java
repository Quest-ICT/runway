package mx.quest.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.testkit.FakeContentCatalog;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * /llms.txt: opt-in (geo.enabled) Markdown index of the host's published listing, in the site's default locale;
 * disabled ⇒ 404. The listing (published only, safe paths, reserved paths dropped) is the host's; what this
 * endpoint adds is the selection (noindex out), the labels, the summary and the absolute URLs.
 */
class LlmsTxtEndpointTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.forLanguageTag("es");

    private final SiteView site = new SiteView("acme", "Acme", EN, List.of(EN, ES), "https://acme.test");
    private final FakePluginSettings settings = TestSettings.declared();
    private final FakeContentCatalog catalog = new FakeContentCatalog().site("acme");
    private final LlmsTxtEndpoint endpoint = new LlmsTxtEndpoint(catalog, settings);

    private static final ContentRef ABOUT = ContentRef.of("about", "/about", Map.of(), List.of(EN),
            Map.of("en", "About"), Map.of("en", "Who we are."), "__site__/acme/about", null);
    private static final ContentRef ENTRY = ContentRef.of("my-post", "/blog/my-post", Map.of(), List.of(EN),
            Map.of("en", "My post"), Map.of("en", "A fine read."), "__plugin__/blog/entry", null);
    /** Published in Spanish only, on an English-default site. */
    private static final ContentRef SPANISH_ONLY = ContentRef.of("solo", "/solo", Map.of("es", "/solo-es"),
            List.of(ES), Map.of("es", "Sólo en español"), Map.of(), "__site__/acme/solo", null);
    private static final ContentRef HIDDEN = new ContentRef("hidden", "/hidden", Map.of(), List.of(EN),
            Map.of("en", "Hidden"), Map.of(), null, "__site__/acme/hidden", true, null, true, null, null, Map.of());

    @BeforeEach
    @org.junit.jupiter.api.AfterEach
    void freshValidators() {
        CrawlConditional.reset(); // also restores the system clock a test may have stepped
    }

    private RouteRequest req(SiteView s, Map<String, String> headers, String origin) {
        return new RouteRequest(s, EN, "GET", "/llms.txt", Map.of(), headers, new byte[0], Map.of(), null, origin);
    }

    private String body(RouteResponse r) {
        assertEquals(200, r.status());
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    private void geo(Map<String, Object> extra) {
        Map<String, Object> cfg = new java.util.HashMap<>(Map.of("geo.enabled", true));
        cfg.putAll(extra);
        settings.site("acme", cfg);
    }

    @Test
    void servesMarkdownIndexWhenGeoEnabled() {
        geo(Map.of("geo.summary", "We build things."));
        catalog.entry("acme", Entries.page(ABOUT, site)).entry("acme", Entries.of("blog", ENTRY, site));

        RouteResponse r = endpoint.handle(req(site, Map.of(), "http://ignored.test"));
        assertEquals("text/plain; charset=utf-8", r.headers().get("Content-Type"));
        assertEquals("""
                # Acme

                > We build things.

                ## Pages

                - [About](https://acme.test/about): Who we are.
                - [My post](https://acme.test/blog/my-post): A fine read.
                """, body(r), "the exact 1.x document shape; plugin content is a first-class citizen");
    }

    @Test
    void disabledReturns404WhenGeoOff() {
        settings.site("acme", Map.of("geo.enabled", false));
        RouteResponse r = endpoint.handle(req(site, Map.of(), ""));
        assertEquals(404, r.status());
        assertEquals("no-store", r.headers().get("Cache-Control"));
    }

    @Test
    void geoIsOffByDefault() {
        assertEquals(404, endpoint.handle(req(site, Map.of(), "")).status(), "GEO is opt-in");
    }

    /**
     * An intended change: an item that does not exist in the default locale is linked where it exists
     * (its primary locale's real URL), never at the unprefixed default path that 1.x produced and the site 404s.
     */
    @Test
    void anItemMissingFromTheDefaultLocaleIsLinkedWhereItExists() {
        geo(Map.of());
        catalog.entry("acme", Entries.page(SPANISH_ONLY, site));

        String md = body(endpoint.handle(req(site, Map.of(), "")));
        assertTrue(md.contains("- [Sólo en español](https://acme.test/es/solo-es)"), md);
        assertFalse(md.contains("https://acme.test/solo)"), "never the default path, which 404s: " + md);
    }

    @Test
    void noindexContentIsNotAdvertised() {
        geo(Map.of());
        catalog.entry("acme", Entries.page(ABOUT, site)).entry("acme", Entries.page(HIDDEN, site));
        String md = body(endpoint.handle(req(site, Map.of(), "")));
        assertTrue(md.contains("[About]"));
        assertFalse(md.contains("Hidden"), md);
    }

    @Test
    void labelsAreOneLineWithoutLinkMetacharactersAndFallBackToTheId() {
        geo(Map.of("geo.summary", "Line one\nline two"));
        ContentRef bracketed = ContentRef.of("faq", "/faq", Map.of(), List.of(EN), Map.of("en", "FAQ [updated]\nnow"),
                Map.of("en", "Two\nlines"), "__site__/acme/faq", null);
        ContentRef untitled = ContentRef.of("no-title", "/no-title", Map.of(), List.of(EN), Map.of(), Map.of(),
                "__site__/acme/nt", null);
        catalog.entry("acme", Entries.page(bracketed, site)).entry("acme", Entries.page(untitled, site));

        String md = body(endpoint.handle(req(site, Map.of(), "")));
        assertTrue(md.contains("> Line one line two\n"), md);
        assertTrue(md.contains("- [FAQ (updated) now](https://acme.test/faq): Two lines\n"), md);
        assertTrue(md.contains("- [no-title](https://acme.test/no-title)\n"), md);
    }

    @Test
    void withoutABaseUrlTheTrustedOriginIsUsed() {
        geo(Map.of());
        SiteView noBase = new SiteView("acme", "Acme", EN, List.of(EN, ES), null);
        catalog.entry("acme", Entries.page(ABOUT, noBase));
        String md = body(endpoint.handle(req(noBase, Map.of("Host", "spoofed.test"), "http://acme.test:8080")));
        assertTrue(md.contains("(http://acme.test:8080/about)"), md);
        assertFalse(md.contains("spoofed"), md);
    }

    @Test
    void conditionalGetRevalidatesWith304() {
        geo(Map.of());
        catalog.entry("acme", Entries.page(ABOUT, site));
        RouteResponse fresh = endpoint.handle(req(site, Map.of(), ""));
        String lastModified = fresh.headers().get("Last-Modified");
        assertEquals("no-cache", fresh.headers().get("Cache-Control"));
        RouteResponse again = endpoint.handle(req(site, Map.of("if-modified-since", lastModified), ""));
        assertEquals(304, again.status());
        assertEquals(0, again.body().length);
    }

    @Test
    void anUnknownSiteListsNothingButStillAnswers() {
        geo(Map.of());
        SiteView ghost = new SiteView("ghost", "Ghost", EN, List.of(EN), "https://ghost.test");
        settings.site("ghost", Map.of("geo.enabled", true));
        assertEquals("# Ghost\n\n## Pages\n\n", body(endpoint.handle(req(ghost, Map.of(), ""))));
    }

    // --- the validator folds what the document prints besides the items (2.1.0) -----------------------------

    private RouteResponse revalidate(SiteView s, RouteResponse fresh) {
        return endpoint.handle(req(s, Map.of("If-Modified-Since", fresh.headers().get("Last-Modified")), ""));
    }

    /** Stepping the validator's clock past HTTP-date granularity, so an advance is observable. */
    private static void tick(int seconds) {
        java.time.Instant at = java.time.Instant.now().plusSeconds(seconds);
        CrawlConditional.clock = () -> at;
    }

    @Test
    void editingTheSummaryAdvancesLastModified() {
        geo(Map.of("geo.summary", "We build things."));
        catalog.entry("acme", Entries.page(ABOUT, site));
        RouteResponse fresh = endpoint.handle(req(site, Map.of(), ""));
        assertEquals(304, revalidate(site, fresh).status(), "unchanged: 304");

        tick(5);
        geo(Map.of("geo.summary", "We build better things."));
        RouteResponse after = revalidate(site, fresh);
        assertEquals(200, after.status(), "a crawler holding the old summary refetches");
        assertTrue(body(after).contains("> We build better things."));
    }

    @Test
    void renamingTheSiteAdvancesLastModified() {
        geo(Map.of());
        catalog.entry("acme", Entries.page(ABOUT, site));
        RouteResponse fresh = endpoint.handle(req(site, Map.of(), ""));

        tick(5);
        SiteView renamed = new SiteView("acme", "Acme Industries", EN, List.of(EN, ES), "https://acme.test");
        RouteResponse after = revalidate(renamed, fresh);
        assertEquals(200, after.status(), "the H1 is the site name: a rename is a document change");
        assertTrue(body(after).startsWith("# Acme Industries\n"));
    }

    @Test
    void aNewBaseUrlAdvancesLastModified() {
        geo(Map.of());
        catalog.entry("acme", Entries.page(ABOUT, site));
        RouteResponse fresh = endpoint.handle(req(site, Map.of(), ""));

        tick(5);
        SiteView moved = new SiteView("acme", "Acme", EN, List.of(EN, ES), "https://www.acme.test");
        RouteResponse after = revalidate(moved, fresh);
        assertEquals(200, after.status(), "every link starts with the base URL");
        assertTrue(body(after).contains("(https://www.acme.test/about)"));
    }

    /**
     * A host-less site reached under two names serves each its own links; the request origin is deliberately NOT
     * folded, so each crawler's copy stays valid for the address it fetched and the validator does not advance on
     * every alternate request.
     */
    @Test
    void theRequestOriginOfAHostlessSiteDoesNotMoveTheValidator() {
        geo(Map.of());
        SiteView hostless = new SiteView("acme", "Acme", EN, List.of(EN, ES), null);
        catalog.entry("acme", Entries.page(ABOUT, hostless));
        RouteResponse first = endpoint.handle(req(hostless, Map.of(), "http://one.test"));
        tick(5);
        RouteResponse second = endpoint.handle(req(hostless, Map.of(), "http://two.test"));
        assertTrue(body(second).contains("(http://two.test/about)"));
        assertEquals(first.headers().get("Last-Modified"), second.headers().get("Last-Modified"));
    }

    @Test
    void editingTheSummaryDoesNotMoveTheSitemapsValidator() {
        geo(Map.of("geo.summary", "One."));
        catalog.entry("acme", Entries.page(ABOUT, site));
        SitemapEndpoint sitemap = new SitemapEndpoint(catalog, settings);
        RouteRequest sitemapReq = new RouteRequest(site, EN, "GET", "/sitemap.xml", Map.of(), Map.of(), new byte[0],
                Map.of(), null, "");
        String sitemapValidator = sitemap.handle(sitemapReq).headers().get("Last-Modified");
        endpoint.handle(req(site, Map.of(), ""));
        tick(5);
        geo(Map.of("geo.summary", "Two."));
        endpoint.handle(req(site, Map.of(), ""));
        assertEquals(sitemapValidator, sitemap.handle(sitemapReq).headers().get("Last-Modified"),
                "the sitemap does not print the summary");
    }
}
