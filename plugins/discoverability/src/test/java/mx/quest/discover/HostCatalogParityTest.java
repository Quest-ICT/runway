package mx.quest.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.content.ContentCatalog;
import mx.quest.runway.content.ContentCatalogViewImpl;
import mx.quest.runway.content.ContentSourceRegistry;
import mx.quest.runway.content.RoutableContent;
import mx.quest.runway.page.ContentUrls;
import mx.quest.runway.page.LocaleUrls;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.RenderModelBuilder;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.Alternate;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * host-it: the sitemap and llms.txt over the REAL kernel catalog ({@code ContentCatalog} behind the kernel's
 * {@code ContentCatalogViewImpl}), compared with what 1.6.0 computed from the same catalog through the kernel's URL
 * builders ({@code ContentUrls}, {@code LocaleUrls}) — the before/after proof at unit level, plus the head-vs-sitemap
 * agreement and the test fixture mirror ({@code Entries}) checked against the kernel.
 */
class HostCatalogParityTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.forLanguageTag("es");
    private static final Locale FR = Locale.FRENCH;
    private static final Instant T = Instant.parse("2026-07-17T10:00:00Z");
    private static final SiteView SITE = new SiteView("acme", "Acme", EN, List.of(EN, ES), "https://acme.test");

    private static ContentRef ref(String id, String path, Map<String, String> localized, List<Locale> locales,
                                  boolean published, boolean noindex, String canonical) {
        return new ContentRef(id, path, localized, locales, Map.of("en", "T " + id, "es", "T-es " + id),
                Map.of("en", "D " + id), null, "__site__/acme/" + id, published, T, noindex, canonical, null, Map.of());
    }

    /** Everything the URL rule distinguishes: locales, localized slugs, an unserved locale, canonicals, noindex. */
    private static final List<ContentRef> PAGES = List.of(
            ref("index", "/", Map.of(), List.of(EN, ES), true, false, null),
            ref("about", "/about", Map.of("es", "/acerca"), List.of(EN, ES), true, false, null),
            ref("en-only", "/en-only", Map.of(), List.of(), true, false, null),
            ref("fr-and-es", "/fr-es", Map.of(), List.of(FR, ES), true, false, null),
            ref("french", "/french", Map.of(), List.of(FR), true, false, null),
            ref("es-only", "/solo", Map.of("es", "/solo-es"), List.of(ES), true, false, null),
            ref("draft", "/draft", Map.of(), List.of(EN), false, false, null),
            ref("hidden", "/hidden", Map.of(), List.of(EN), true, true, null),
            ref("dupe", "/dupe", Map.of(), List.of(EN), true, false, "https://acme.test/about"),
            ref("self", "/self", Map.of(), List.of(EN), true, false, "https://acme.test/self"),
            ref("reserved", "/login", Map.of(), List.of(EN), true, false, null));
    private static final List<ContentRef> POSTS = List.of(
            ref("hello", "/blog/hello", Map.of("es", "/blog/hola"), List.of(EN, ES), true, false, null));

    private static ContentSource source(String key, List<ContentRef> refs) {
        return new ContentSource() {
            @Override public String key() { return key; }
            @Override public List<ContentRef> enumerate(SiteView site) { return refs; }
            @Override public ContentBody body(SiteView site, String id, Locale locale) {
                throw new IllegalStateException("the crawl surfaces never read a body");
            }
        };
    }

    private ContentCatalog catalog;
    private ContentCatalogViewImpl view;

    @BeforeEach
    void kernel() {
        CrawlConditional.reset();
        ContentSource broken = new ContentSource() {
            @Override public String key() { return "broken"; }
            @Override public List<ContentRef> enumerate(SiteView site) { throw new IllegalStateException("boom"); }
            @Override public ContentBody body(SiteView site, String id, Locale locale) { return ContentBody.empty(); }
        };
        catalog = new ContentCatalog(new ContentSourceRegistry(
                List.of(source("pages", PAGES), broken, source("blog", POSTS))));
        view = new ContentCatalogViewImpl(catalog, new FakeSiteServices().site(SITE), mock(PageStore.class));
    }

    private static RouteRequest req(String path) {
        return new RouteRequest(SITE, EN, "GET", path, Map.of(), Map.of(), new byte[0], Map.of(), null, "");
    }

    private static String body(mx.quest.runway.spi.model.RouteResponse r) {
        assertEquals(200, r.status());
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    @Test
    void theTestFixtureMirrorBuildsExactlyTheKernelsEntries() {
        for (CatalogEntry kernelEntry : view.entries("acme")) {
            assertEquals(kernelEntry, Entries.of(kernelEntry.sourceKey(), kernelEntry.ref(), SITE),
                    kernelEntry.compositeId());
        }
        assertEquals(10, view.entries("acme").size(),
                "the published, unreserved items of the healthy sources (noindex is listed; the sitemap drops it)");
    }

    /**
     * The 2.0.0 sitemap over the kernel's listing equals the document 1.6.0 built from the same catalog with the
     * kernel's builders — except the one documented fix in this fixture: none (the fixture's canonicals are absolute).
     */
    @Test
    void theSitemapIsTheOneOnePointSixBuiltFromTheSameCatalog() {
        String now = body(new SitemapEndpoint(view, TestSettings.declared()).handle(req("/sitemap.xml")));
        assertEquals(sitemapAsOnePointSixBuiltIt(), now);
        assertFalse(now.contains("/draft") || now.contains("/login") || now.contains("/hidden")
                || now.contains("/dupe"), now);
        assertTrue(now.contains("<loc>https://acme.test/blog/hola</loc>") == false
                && now.contains("<loc>https://acme.test/es/blog/hola</loc>"), "a healthy source survives a broken one");
    }

    /**
     * llms.txt equals 1.6.0's except where an item does not exist at the address 1.6.0 linked (an item missing from
     * the default locale, fixed in 2.0.0) and — from the SPI 1.0.0 kernel — an item whose primary locale the site does
     * not serve, whose x-default is now its first available locale's real address.
     */
    @Test
    void llmsTxtIsTheOneOnePointSixBuiltExceptTheN6Fix() {
        String now = body(new LlmsTxtEndpoint(view, TestSettings.declared().site("acme", Map.of("geo.enabled", true)))
                .handle(req("/llms.txt")));
        String before = llmsAsOnePointSixBuiltIt();
        assertEquals(before.replace("(https://acme.test/solo)", "(https://acme.test/es/solo-es)")
                        .replace("(https://acme.test/fr-es)", "(https://acme.test/es/fr-es)"), now,
                "only the two items that do not exist at the unprefixed path moved — to their real Spanish URLs");
        assertTrue(before.contains("(https://acme.test/solo)"), "1.6.0 linked the unprefixed path, which 404s");
        // fr-and-es: French first, Spanish served. Before SPI 1.0.0 the kernel clamped its x-default to the site
        // default, so CatalogEntry.pathFor fell back to the unprefixed /fr-es (a 404), as 1.6.0 did.
        assertTrue(before.contains("(https://acme.test/fr-es)"), before);
        assertTrue(now.contains("(https://acme.test/es/fr-es)"), now);
    }

    @Test
    void headAlternatesAndSitemapAlternatesProvablyAgree() {
        String xml = body(new SitemapEndpoint(view, TestSettings.declared()).handle(req("/sitemap.xml")));
        for (RoutableContent item : catalog.enumerate(SITE)) {
            if (item.ref().noindex() || item.ref().canonical() != null) {
                continue;
            }
            List<Alternate> head = new RenderModelBuilder()
                    .headContext(SITE, item, EN, "https://acme.test", false).alternates();
            for (Alternate alternate : head) {
                String link = "hreflang=\"" + alternate.hreflang() + "\" href=\"" + alternate.url() + "\"";
                assertTrue(xml.contains(link), "the sitemap carries the head's exact alternate: " + link);
            }
        }
    }

    // ---- 1.6.0's algorithms, verbatim in substance, over the kernel's builders --------------------------------

    private String sitemapAsOnePointSixBuiltIt() {
        String base = "https://acme.test";
        record Entry(String url, String block, String lastmod) { }
        List<Entry> entries = new ArrayList<>();
        for (RoutableContent item : catalog.enumerate(SITE)) {
            if (item.ref().noindex()) {
                continue;
            }
            List<Alternate> alternates = ContentUrls.alternates(item, SITE, base);
            String xDefault = ContentUrls.xDefault(item, SITE, base);
            String canonical = item.ref().canonical();
            if (canonical != null && !canonical.isBlank()) {
                String want = canonical.trim();
                if (alternates.stream().noneMatch(a -> a.url().equals(want)) && !want.equals(xDefault)) {
                    continue;
                }
            }
            StringBuilder links = new StringBuilder();
            for (Alternate alt : alternates) {
                links.append("    <xhtml:link rel=\"alternate\" hreflang=\"").append(alt.hreflang())
                        .append("\" href=\"").append(alt.url()).append("\"/>\n");
            }
            links.append("    <xhtml:link rel=\"alternate\" hreflang=\"x-default\" href=\"").append(xDefault)
                    .append("\"/>\n");
            for (Alternate self : alternates) {
                entries.add(new Entry(self.url(), links.toString(), item.ref().updatedAt().toString()));
            }
        }
        entries.sort(Comparator.comparing(Entry::url));
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\""
                + " xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">\n");
        for (Entry e : entries) {
            xml.append("  <url>\n    <loc>").append(e.url()).append("</loc>\n    <lastmod>").append(e.lastmod())
                    .append("</lastmod>\n").append(e.block()).append("  </url>\n");
        }
        return xml.append("</urlset>\n").toString();
    }

    private String llmsAsOnePointSixBuiltIt() {
        StringBuilder out = new StringBuilder("# Acme\n\n## Pages\n\n");
        for (RoutableContent item : catalog.enumerate(SITE)) {
            if (item.ref().noindex()) {
                continue;
            }
            out.append("- [").append(item.titleFor(EN)).append("](https://acme.test")
                    .append(LocaleUrls.urlFor(item, EN, EN)).append("): ").append(item.descriptionFor(EN))
                    .append('\n');
        }
        return out.toString();
    }
}
