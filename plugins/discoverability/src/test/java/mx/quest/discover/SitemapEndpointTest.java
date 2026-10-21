package mx.quest.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.testkit.FakeContentCatalog;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * /sitemap.xml over the host's {@code ContentCatalogView}: the whole published content surface — pages AND plugin
 * content — with the hreflang alternates and {@code x-default} the host computed, {@code <lastmod>} from
 * {@code updatedAt}, noindex and canonicalised-elsewhere items left out, the index/shards past 10,000 URLs, and the
 * conditional GET. Disabled ⇒ 404; base from the trusted origin when the site has none.
 *
 * <p>The listing itself (published only, safe and unreserved paths, a throwing source contributing nothing) and the
 * URL computation are the host's, tested in the kernel; the host-it profile runs this endpoint over the real kernel
 * catalog ({@code HostCatalogParityTest}), including the head-vs-sitemap agreement.
 */
class SitemapEndpointTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.forLanguageTag("es");
    private static final Instant UPDATED = Instant.parse("2026-07-17T10:00:00Z");
    private static final SiteView SITE = new SiteView("acme", "Acme", EN, List.of(EN, ES), "https://acme.test");

    @BeforeEach
    void freshValidators() {
        CrawlConditional.reset(); // per-site validator state is static — isolate from sibling tests
    }

    /** The endpoint over a listing of {@code refs} (as pages) for {@link #SITE}'s locales, sitemap on. */
    private static SitemapEndpoint endpoint(SiteView site, ContentRef... refs) {
        FakeContentCatalog catalog = new FakeContentCatalog().site("acme");
        for (ContentRef ref : refs) {
            catalog.entry("acme", Entries.page(ref, site));
        }
        return new SitemapEndpoint(catalog, TestSettings.declared());
    }

    private static SitemapEndpoint endpoint(ContentRef... refs) {
        return endpoint(SITE, refs);
    }

    private static final ContentRef ABOUT = ContentRef.of("about", "/about", Map.of(), List.of(EN, ES),
            Map.of("en", "About", "es", "Acerca"), Map.of("en", "About page"), "__site__/acme/about", UPDATED);
    private static final ContentRef ENTRY = ContentRef.of("my-post", "/blog/my-post",
            Map.of("es", "/blog/mi-entrada"), List.of(EN, ES), Map.of("en", "My post"), Map.of(),
            "__plugin__/blog/entry", UPDATED);

    /** Published, but the site tells robots not to index it — must not be advertised. */
    private static final ContentRef NOINDEXED = new ContentRef("hidden", "/hidden", Map.of(), List.of(EN),
            Map.of("en", "Hidden"), Map.of(), null, "__site__/acme/hidden", true, UPDATED, true, null, null,
            Map.of());
    /** Published and indexable, but declares a DIFFERENT canonical — it is not the authoritative URL. */
    private static final ContentRef DUPLICATE = new ContentRef("dupe", "/dupe", Map.of(), List.of(EN),
            Map.of("en", "Dupe"), Map.of(), null, "__site__/acme/dupe", true, UPDATED, false,
            "https://acme.test/about", null, Map.of());
    /** Published with a SELF-referential canonical — the ordinary case, which must still be listed. */
    private static final ContentRef SELF_CANONICAL = new ContentRef("self", "/self", Map.of(), List.of(EN),
            Map.of("en", "Self"), Map.of(), null, "__site__/acme/self", true, UPDATED, false,
            "https://acme.test/self", null, Map.of());

    private static RouteRequest req(SiteView site, Map<String, String> headers) {
        return req(site, headers, "/sitemap.xml");
    }

    private static RouteRequest req(SiteView site, Map<String, String> headers, String path) {
        return new RouteRequest(site, EN, "GET", path, Map.of(), headers, new byte[0], Map.of(), null, "");
    }

    /** {@code n} trivial published pages — the fixture for the sharding tests. */
    private static ContentRef[] pages(int n) {
        ContentRef[] many = new ContentRef[n];
        for (int i = 0; i < n; i++) {
            many[i] = new ContentRef("p" + i, "/p" + i, Map.of(), List.of(EN), Map.of("en", "P" + i), Map.of(),
                    null, "__site__/acme/p" + i, true, UPDATED, false, null, null, Map.of());
        }
        return many;
    }

    private static String body(RouteResponse r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    @Test
    void servesTheWholeContentSurfaceWithHreflangAndLastmod() {
        FakeContentCatalog catalog = new FakeContentCatalog()
                .entry("acme", Entries.page(ABOUT, SITE))
                .entry("acme", Entries.of("blog", ENTRY, SITE));
        RouteResponse r = new SitemapEndpoint(catalog, TestSettings.declared()).handle(req(SITE, Map.of()));

        assertEquals(200, r.status());
        assertEquals("application/xml; charset=utf-8", r.headers().get("Content-Type"));
        String xml = body(r);
        // Page-like items, both locales, the default unprefixed.
        assertTrue(xml.contains("<loc>https://acme.test/about</loc>"), "default locale is unprefixed");
        assertTrue(xml.contains("<loc>https://acme.test/es/about</loc>"), "other locale is /<lang>-prefixed");
        assertTrue(xml.contains("hreflang=\"en\"") && xml.contains("hreflang=\"es\""));
        assertTrue(xml.contains("hreflang=\"x-default\""));
        // PLUGIN content is a first-class sitemap citizen — with its LOCALIZED slug.
        assertTrue(xml.contains("<loc>https://acme.test/blog/my-post</loc>"), "virtual entry listed");
        assertTrue(xml.contains("<loc>https://acme.test/es/blog/mi-entrada</loc>"), "its localized slug too");
        assertTrue(xml.contains("<lastmod>" + UPDATED + "</lastmod>"), "lastmod emitted from updatedAt");
    }

    /**
     * The exact document — every byte — for a small bilingual site. 2.0.0 reads its URLs from the host's listing
     * instead of computing them; this pins the document so that move cannot change what a crawler receives.
     */
    @Test
    void theDocumentIsByteForByteWhatOnePointSixServed() {
        FakeContentCatalog catalog = new FakeContentCatalog()
                .entry("acme", Entries.of("blog", ENTRY, SITE))
                .entry("acme", Entries.page(ABOUT, SITE));
        String xml = body(new SitemapEndpoint(catalog, TestSettings.declared()).handle(req(SITE, Map.of())));
        String about = """
                    <xhtml:link rel="alternate" hreflang="en" href="https://acme.test/about"/>
                    <xhtml:link rel="alternate" hreflang="es" href="https://acme.test/es/about"/>
                    <xhtml:link rel="alternate" hreflang="x-default" href="https://acme.test/about"/>
                """;
        String post = """
                    <xhtml:link rel="alternate" hreflang="en" href="https://acme.test/blog/my-post"/>
                    <xhtml:link rel="alternate" hreflang="es" href="https://acme.test/es/blog/mi-entrada"/>
                    <xhtml:link rel="alternate" hreflang="x-default" href="https://acme.test/blog/my-post"/>
                """;
        String lastmod = "    <lastmod>2026-07-17T10:00:00Z</lastmod>\n";
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\" "
                + "xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">\n"
                + "  <url>\n    <loc>https://acme.test/about</loc>\n" + lastmod + about + "  </url>\n"
                + "  <url>\n    <loc>https://acme.test/blog/my-post</loc>\n" + lastmod + post + "  </url>\n"
                + "  <url>\n    <loc>https://acme.test/es/about</loc>\n" + lastmod + about + "  </url>\n"
                + "  <url>\n    <loc>https://acme.test/es/blog/mi-entrada</loc>\n" + lastmod + post + "  </url>\n"
                + "</urlset>\n", xml);
    }

    @Test
    void conditionalGetRevalidatesWith304AndTheHeadersMatch() {
        SitemapEndpoint endpoint = endpoint(ABOUT);

        RouteResponse fresh = endpoint.handle(req(SITE, Map.of()));
        assertEquals(200, fresh.status());
        String lastModified = fresh.headers().get("Last-Modified");
        assertTrue(lastModified != null && !lastModified.isBlank(), "the 200 carries Last-Modified");
        assertEquals("no-cache", fresh.headers().get("Cache-Control"), "cache-but-revalidate posture");

        RouteResponse revalidated = endpoint.handle(req(SITE, Map.of("If-Modified-Since", lastModified)));
        assertEquals(304, revalidated.status(), "an unchanged sitemap revalidates without re-serializing");
        assertEquals(0, revalidated.body().length, "the 304 carries no body");
        assertEquals(lastModified, revalidated.headers().get("Last-Modified"), "same validator");

        RouteResponse older = endpoint.handle(req(SITE,
                Map.of("If-Modified-Since", "Mon, 01 Jan 2001 00:00:00 GMT")));
        assertEquals(200, older.status(), "an outdated copy gets the full document again");
    }

    @Test
    void disabledReturns404() {
        FakePluginSettings off = TestSettings.declared().site("acme", Map.of("sitemap.enabled", false));
        RouteResponse r = new SitemapEndpoint(new FakeContentCatalog().entry("acme", Entries.page(ABOUT, SITE)), off)
                .handle(req(SITE, Map.of()));
        assertEquals(404, r.status());
        assertEquals("no-store", r.headers().get("Cache-Control"));
    }

    @Test
    void enabledByDefault() {
        assertEquals(200, endpoint(ABOUT).handle(req(SITE, Map.of())).status(), "sitemap.enabled defaults on");
    }

    /**
     * Without a configured base URL the absolute URLs use the request's TRUSTED origin (scheme, host and port as
     * the kernel's trusted-proxy gate left them). 1.x read the raw Host header and guessed {@code https}.
     */
    @Test
    void takesTheBaseFromTheTrustedOriginWhenBaseUrlUnset() {
        SiteView noBase = new SiteView("acme", "Acme", EN, List.of(EN, ES), null);
        RouteRequest request = new RouteRequest(noBase, EN, "GET", "/sitemap.xml", Map.of(),
                Map.of("Host", "spoofed.test"), new byte[0], Map.of(), null, "http://acme.test:8080");
        String xml = body(endpoint(noBase, ABOUT).handle(request));
        assertTrue(xml.contains("<loc>http://acme.test:8080/about</loc>"), xml);
        assertFalse(xml.contains("spoofed") || xml.contains("https://"), "never the Host header, never a guess");
    }

    @Test
    void aTrailingSlashOnTheConfiguredBaseIsDropped() {
        SiteView slash = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test/");
        String xml = body(endpoint(slash, ABOUT).handle(req(slash, Map.of())));
        assertTrue(xml.contains("<loc>https://acme.test/about</loc>"), xml);
    }

    /**
     * A sitemap is a list of URLs the site RECOMMENDS for indexing, so listing a page it separately tells robots
     * not to index contradicts itself — sitemaps.org treats such a file as a quality signal against the site.
     *
     * <p>Filtered in the plugin rather than in {@code ContentCatalog} on purpose: the router, the search indexer
     * and llms.txt all legitimately need the full published set, so narrowing the catalog would break them.
     */
    @Test
    void noindexContentIsPublishedButNeverAdvertised() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        String xml = body(endpoint(site, ABOUT, NOINDEXED).handle(req(site, Map.of())));

        assertTrue(xml.contains("<loc>https://acme.test/about</loc>"), "indexable content still listed");
        assertFalse(xml.contains("/hidden"), "noindex content must not appear in the sitemap");
    }

    /**
     * An item declaring a canonical elsewhere has said it is not the authoritative URL; advertising it would put
     * a self-declared non-canonical address in the sitemap. A canonical equal to the item's OWN url is the normal
     * self-referential case and must not be swept up by the same rule — that is the half a naive check breaks.
     */
    @Test
    void anItemCanonicalisedElsewhereIsOmittedButASelfCanonicalOneIsKept() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        String xml = body(endpoint(site, ABOUT, DUPLICATE, SELF_CANONICAL).handle(req(site, Map.of())));

        assertFalse(xml.contains("/dupe"), "an item canonicalised elsewhere is not the authoritative URL");
        assertTrue(xml.contains("<loc>https://acme.test/self</loc>"), "a SELF-referential canonical stays listed");
        assertTrue(xml.contains("<loc>https://acme.test/about</loc>"), "an item with no canonical stays listed");
    }

    /**
     * A ROOT-RELATIVE canonical names an address on this site and is resolved against the base before comparing, as
     * the kernel resolves it for the {@code <head>}. 1.x compared the raw {@code /self} with the absolute URLs, so a
     * page whose canonical was its own path was dropped from the sitemap.
     */
    @Test
    void aRootRelativeSelfCanonicalIsKeptAndARootRelativeElsewhereOneIsOmitted() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN, ES), "https://acme.test");
        ContentRef self = new ContentRef("self", "/self", Map.of(), List.of(EN, ES), Map.of("en", "Self"), Map.of(),
                null, "__site__/acme/self", true, UPDATED, false, "/self", null, Map.of());
        ContentRef elsewhere = new ContentRef("copy", "/copy", Map.of(), List.of(EN), Map.of("en", "Copy"), Map.of(),
                null, "__site__/acme/copy", true, UPDATED, false, "/self", null, Map.of());
        String xml = body(endpoint(site, self, elsewhere).handle(req(site, Map.of())));

        assertTrue(xml.contains("<loc>https://acme.test/self</loc>"), xml);
        assertTrue(xml.contains("<loc>https://acme.test/es/self</loc>"), "every locale of a self-canonical item");
        assertFalse(xml.contains("/copy"), "a root-relative canonical naming ANOTHER page still points elsewhere");
    }

    /**
     * An item that exists only in a non-default locale: its one address and its x-default are the host's (the
     * Spanish URL), never the unprefixed default path, which the site does not serve.
     */
    @Test
    void aNonDefaultLocaleOnlyItemUsesTheHostsAddressesForLocAndXDefault() {
        ContentRef solo = ContentRef.of("solo", "/solo", Map.of("es", "/solo-es"), List.of(ES), Map.of("es", "Solo"),
                Map.of(), "__site__/acme/solo", UPDATED);
        String xml = body(endpoint(solo).handle(req(SITE, Map.of())));
        assertTrue(xml.contains("<loc>https://acme.test/es/solo-es</loc>"), xml);
        assertTrue(xml.contains("hreflang=\"x-default\" href=\"https://acme.test/es/solo-es\""), xml);
        assertFalse(xml.contains("https://acme.test/solo\""), "the unprefixed default path does not serve: " + xml);
        assertFalse(xml.contains("hreflang=\"en\""), "no alternate for a locale the item does not exist in");
    }

    /** A canonical naming one of the item's OTHER locales' addresses is still self-referential. */
    @Test
    void aCanonicalNamingAnotherLocaleOfTheSameItemIsSelf() {
        ContentRef ref = new ContentRef("about", "/about", Map.of(), List.of(EN, ES), Map.of("en", "About"), Map.of(),
                null, "__site__/acme/about", true, UPDATED, false, "https://acme.test/es/about", null, Map.of());
        String xml = body(endpoint(ref).handle(req(SITE, Map.of())));
        assertTrue(xml.contains("<loc>https://acme.test/about</loc>"), xml);
    }

    // ---- sitemap index -------------------------------------------------------------------------------

    /**
     * A small site keeps a single {@code <urlset>} — no index, no second round trip.
     *
     * <p>This is the shape almost every tenant has, and splitting a 40-URL site across an index would cost a
     * crawler two fetches to learn nothing.
     */
    @Test
    void asmallSiteIsStillASinglePlainUrlset() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        String xml = body(endpoint(site, pages(40)).handle(req(site, Map.of())));

        assertTrue(xml.contains("<urlset"), xml.substring(0, Math.min(200, xml.length())));
        assertFalse(xml.contains("<sitemapindex"), "no index for a site that fits in one file");
        assertEquals(40, xml.split("<loc>", -1).length - 1);
    }

    /** …and its shard paths 404 rather than serving the same document at a second address. */
    @Test
    void aSmallSiteAnswers404OnTheShardPaths() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");

        RouteResponse response = endpoint(site, pages(40)).handle(req(site, Map.of(), "/sitemap-1.xml"));

        assertEquals(404, response.status(),
                "two URLs serving one document is the duplicate-content shape this plugin exists to avoid");
    }

    /**
     * Past one shard's worth, {@code /sitemap.xml} becomes an index naming exactly the shards that exist.
     *
     * <p>25,000 URLs is three shards of 10,000 — the third holding 5,000 — so the count catches both an
     * off-by-one in the ceiling division and an index that advertises a shard with nothing in it.
     */
    @Test
    void aLargeSiteGetsAnIndexNamingExactlyTheShardsThatExist() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        String xml = body(endpoint(site, pages(25_000)).handle(req(site, Map.of())));

        assertTrue(xml.contains("<sitemapindex"), "over one shard's worth, the entry point is an index");
        assertEquals(3, xml.split("<loc>", -1).length - 1, "25,000 URLs is 3 shards, not 2 and not 4");
        assertTrue(xml.contains("<loc>https://acme.test/sitemap-1.xml</loc>"), xml);
        assertTrue(xml.contains("<loc>https://acme.test/sitemap-3.xml</loc>"), xml);
        assertFalse(xml.contains("/sitemap-4.xml"), "an empty shard must never be advertised");
    }

    /** Each shard carries its own slice, full shards first, and the last one holds the remainder. */
    @Test
    void eachShardCarriesItsOwnSliceAndTheLastHoldsTheRemainder() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        SitemapEndpoint endpoint = endpoint(site, pages(25_000));

        String first = body(endpoint.handle(req(site, Map.of(), "/sitemap-1.xml")));
        String last = body(endpoint.handle(req(site, Map.of(), "/sitemap-3.xml")));

        assertEquals(10_000, first.split("<loc>", -1).length - 1);
        assertEquals(5_000, last.split("<loc>", -1).length - 1);
        assertTrue(first.contains("<urlset") && last.contains("<urlset"));
    }

    /**
     * Shards partition the URL set: every URL appears in exactly one, and none is lost at a boundary.
     *
     * <p>Asserted as a set rather than by counting, because a boundary bug that drops one URL and duplicates
     * another leaves the totals right.
     */
    @Test
    void theShardsPartitionTheUrlSetWithNoGapOrOverlap() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        SitemapEndpoint endpoint = endpoint(site, pages(25_000));

        List<String> all = new java.util.ArrayList<>();
        for (int shard = 1; shard <= 3; shard++) {
            all.addAll(locs(body(endpoint.handle(req(site, Map.of(), "/sitemap-" + shard + ".xml")))));
        }

        assertEquals(25_000, all.size(), "no URL is served twice");
        assertEquals(25_000, new java.util.HashSet<>(all).size(), "…and none is served twice under two names");
        assertTrue(all.contains("https://acme.test/p0") && all.contains("https://acme.test/p24999"),
                "the first and last items both survive the split");
    }

    /**
     * Shard membership is a function of the CONTENT, not of source-registration order.
     *
     * <p>The host's listing is grouped by source in an order it does not promise, which says nothing across
     * restarts or across the nodes of a cluster. If shard boundaries followed it, two nodes behind one load
     * balancer would serve <b>different content for the same shard number</b>, and a crawler alternating
     * between them would watch URLs appear and vanish with nothing in the response to explain it.
     */
    @Test
    void shardMembershipDoesNotDependOnSourceOrder() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        ContentRef[] refs = pages(25_000);
        ContentRef[] reversed = new ContentRef[refs.length];
        for (int i = 0; i < refs.length; i++) {
            reversed[i] = refs[refs.length - 1 - i];
        }

        String oneWay = body(endpoint(site, refs).handle(req(site, Map.of(), "/sitemap-2.xml")));
        String other = body(endpoint(site, reversed).handle(req(site, Map.of(), "/sitemap-2.xml")));

        assertEquals(oneWay, other, "the same shard number must mean the same URLs on every node");
    }

    /** A shard number past the end 404s — nothing links to it, so the only way there is to type it. */
    @Test
    void aShardBeyondTheEndIs404() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        SitemapEndpoint endpoint = endpoint(site, pages(25_000));

        assertEquals(404, endpoint.handle(req(site, Map.of(), "/sitemap-4.xml")).status());
        assertEquals(200, endpoint.handle(req(site, Map.of(), "/sitemap-3.xml")).status());
    }

    /** Every shard route is declared, because the host matches routes by exact path — no patterns. */
    @Test
    void theIndexAndEveryShardAreDeclaredRoutes() {
        List<String> paths = endpoint().routes().stream()
                .map(mx.quest.runway.spi.model.Route::pathPattern).toList();

        assertTrue(paths.contains("/sitemap.xml"));
        assertTrue(paths.contains("/sitemap-1.xml"));
        assertTrue(paths.contains("/sitemap-20.xml"));
        assertFalse(paths.contains("/sitemap-21.xml"), "the ceiling is real and must be visible in the routes");
    }

    /** The {@code <loc>} values of a sitemap document. */
    private static List<String> locs(String xml) {
        List<String> found = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<loc>([^<]+)</loc>").matcher(xml);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }
}
