package mx.quest.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.PageView;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.testkit.FakeContentCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The settings change that came with the SPI: 1.x read only the values a site had STORED and applied its own code
 * fallbacks; since 2.0.0 the host's {@code PluginSettings} applies the descriptor's declared {@code default:}s first.
 * If a code fallback and its declared default ever disagreed, an unchanged site would render differently after the
 * upgrade. This renders every surface twice — with the declared defaults applied and with none — for a site that
 * stored nothing, and for one that stored blank values the form may write, and requires identical bytes.
 */
class DescriptorDefaultsTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final SiteView SITE = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");

    /** Stored values a site may hold without meaning anything: blank fields the settings form saved. */
    private static final Map<String, Object> BLANKS = Map.ofEntries(
            Map.entry("jsonld.type", ""), Map.entry("robots.ai-policy", ""), Map.entry("robots.ai-training", ""),
            Map.entry("robots.ai-retrieval", ""), Map.entry("robots.ai-user", ""), Map.entry("geo.speakable", ""),
            Map.entry("software.os", ""), Map.entry("software.currency", ""), Map.entry("org.sameas", ""),
            Map.entry("geo.enabled", true), Map.entry("software.enabled", true), Map.entry("software.name", "App"),
            Map.entry("software.price", "0"));

    @BeforeEach
    void freshValidators() {
        CrawlConditional.reset();
    }

    @Test
    void theDeclaredDefaultsAreTheCodeFallbacks() {
        // Every key with a declared default — so a key added later without its twin in code fails here.
        assertEquals(java.util.Set.of("jsonld.enabled", "jsonld.type", "jsonld.breadcrumbs", "org.sameas",
                        "sitemap.enabled", "robots.ai-policy", "robots.ai-training", "robots.ai-retrieval",
                        "robots.ai-user", "geo.enabled", "geo.speakable", "software.enabled", "software.os",
                        "software.currency", "indexnow.enabled"),
                TestSettings.declaredDefaults().keySet(), "the set of declared per-site defaults this test covers");
        for (Map<String, Object> stored : List.of(Map.<String, Object>of(), BLANKS,
                Map.<String, Object>of("geo.enabled", true, "software.enabled", true, "software.name", "App",
                        "software.price", "0", "org.name", "Acme Corp"))) {
            assertEquals(render(TestSettings.bare().site("acme", stored)),
                    render(TestSettings.declared().site("acme", stored)), "stored: " + stored);
        }
    }

    @Test
    void theRenderingIsNotTriviallyEmpty() {
        String all = render(TestSettings.declared().site("acme", Map.of("geo.enabled", true)));
        assertFalse(all.isBlank());
        for (String part : List.of("\"@type\":\"WebSite\"", "SpeakableSpecification", "<urlset", "## Pages",
                "User-agent: *", "Sitemap: https://acme.test/sitemap.xml")) {
            assertTrue(all.contains(part), "the comparison covers " + part);
        }
    }

    /** Every settings-driven surface, concatenated: head JSON-LD, robots.txt, sitemap, llms.txt, card values. */
    private static String render(PluginSettings settings) {
        CrawlConditional.reset();
        ContentRef about = ContentRef.of("about", "/about", Map.of(), List.of(EN), Map.of("en", "About"),
                Map.of("en", "Who"), "__site__/acme/about", Instant.parse("2026-07-17T10:00:00Z"));
        ContentRef home = ContentRef.of("index", "/", Map.of(), List.of(EN), Map.of("en", "Home"), Map.of(),
                "__site__/acme/index", Instant.parse("2026-07-17T10:00:00Z"));
        FakeContentCatalog catalog = new FakeContentCatalog()
                .entry("acme", Entries.page(home, SITE)).entry("acme", Entries.page(about, SITE));
        StringBuilder out = new StringBuilder();
        StructuredDataHead head = new StructuredDataHead(settings, Map.of());
        for (String path : List.of("/", "/about")) {
            PageMeta meta = new PageMeta(path.equals("/") ? "index" : "about", "T", "D", List.of(EN), EN, true, false);
            out.append(head.render(new HeadContext(SITE, meta, EN, "https://acme.test" + path,
                    "https://acme.test" + path, List.of(), false, false))).append('\n');
        }
        DiscoverabilityProperties props = new DiscoverabilityProperties();
        out.append(text(new RobotsTxtEndpoint(settings, props).handle(req("/robots.txt"))));
        out.append(text(new SitemapEndpoint(catalog, settings).handle(req("/sitemap.xml"))));
        out.append(text(new LlmsTxtEndpoint(catalog, settings).handle(req("/llms.txt"))));
        out.append(new IndexNowKeyEndpoint(settings, props).handle(req("/indexnow-key.txt")).status()).append('\n');
        DiscoverAdminModel model = new DiscoverAdminModel(settings, catalog);
        out.append(model.jsonldEnabled("acme")).append(model.jsonldType("acme")).append(model.sitemapEnabled("acme"))
                .append(model.geoEnabled("acme")).append(model.orgConfigured("acme")).append('\n');
        out.append(new FaqAdvisoryCheck(settings).inspect(new PageView(SITE,
                new PageMeta("faq", "F", "", List.of(EN), EN, true, false), EN, "https://acme.test/faq",
                "<details><summary>a</summary>b</details><details><summary>c</summary>d</details>")));
        return out.toString();
    }

    private static RouteRequest req(String path) {
        return new RouteRequest(SITE, EN, "GET", path, Map.of(), Map.of(), new byte[0], Map.of(), null, "");
    }

    private static String text(mx.quest.runway.spi.model.RouteResponse response) {
        return response.status() + "\n" + new String(response.body(), StandardCharsets.UTF_8) + "\n";
    }
}
