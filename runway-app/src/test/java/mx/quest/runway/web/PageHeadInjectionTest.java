package mx.quest.runway.web;
import mx.quest.runway.platform.TestPlatformSettings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.head.HeadContributorRegistry;
import mx.quest.runway.head.HeadInjectionFilter;
import mx.quest.runway.page.PageRouting;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageValidatorService;
import mx.quest.runway.icon.IconHeadContributor;
import mx.quest.runway.icon.SiteIcon;
import mx.quest.runway.icon.SiteIconService;
import mx.quest.runway.icon.SiteManifestService;
import mx.quest.runway.page.RenderModelBuilder;
import mx.quest.runway.seo.SeoConfigService;
import mx.quest.runway.seo.SeoHeadContributor;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.widget.ContentWidgetRegistry;
import mx.quest.runway.widget.WidgetModelBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * End-to-end proof of the edge head-injection seam: a contributor's markup is <b>absent</b> from the
 * rendered template yet <b>present</b> in the response after the filter, and each tenant gets its own
 * (host-materialized) canonical — confirming the markup is composed from the request's
 * {@link HeadContext}, not baked into any site's templates.
 */
class PageHeadInjectionTest {

    private static final Locale EN = Locale.ENGLISH;

    /** A contributor that emits a canonical link from the request context — site-specific by construction. */
    private static final class CanonicalContributor implements HeadContributor {
        @Override public String key() { return "canonical"; }
        @Override public String render(HeadContext context) {
            return "<link rel=\"canonical\" href=\"" + context.canonical() + "\">";
        }
    }

    private record Fixture(PageController controller, HeadInjectionFilter filter, SiteResolver siteResolver) {}

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void seedSite(SiteStore siteStore, SiteResolver siteResolver, StorageLayout layout,
                                 String id, String host, String baseUrl) throws IOException {
        Site site = new Site(id, id, host == null ? List.of() : List.of(host), EN, List.of(EN, Locale.of("es")),
                baseUrl, SiteStatus.ACTIVE);
        siteStore.save(site);
        Path templates = layout.siteDir(id).resolve("templates");
        write(templates.resolve("_master.html"), "<!DOCTYPE html><html><head><title>M</title></head><body><main id=\"content\">m</main></body></html>");
        write(templates.resolve("_master.th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>");
        write(templates.resolve("about.html"), "<!DOCTYPE html><html><head><title>A</title></head><body><main id=\"content\"><h1 id=\"t\">PH</h1></main></body></html>");
        write(templates.resolve("about.th.xml"), "<?xml version=\"1.0\"?><thlogic><attr sel=\"head\" th:replace=\"~{${master} :: head}\"/><attr sel=\"#t\" th:text=\"${pageTitle}\"/></thlogic>");
        write(templates.resolve("about.meta.yml"), "path: /about\nlocales: [en, es]\ntitle:\n  en: About\n");
    }

    private static Fixture wire(Path tmp) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore siteStore = new SiteStore(layout, yaml);
        SiteResolver siteResolver = new SiteResolver(siteStore, TestPlatformSettings.empty());

        GenericApplicationContext appContext = new GenericApplicationContext();
        appContext.refresh();
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new SiteTemplateResolver(layout));

        SiteIconService iconService = new SiteIconService(new mx.quest.runway.storage.SiteFileStore(layout));
        RenderModelBuilder modelBuilder = new RenderModelBuilder(new SeoConfigService(layout, yaml),
                iconService, new SiteManifestService(layout, yaml),
                new mx.quest.runway.site.SiteParamsService(layout, yaml));
        PageController controller = new PageController(siteResolver,
                new PageRouting(new PageStore(layout, yaml)), new mx.quest.runway.content.ContentRouter(new mx.quest.runway.content.ContentSourceRegistry(java.util.List.of())), new mx.quest.runway.content.ContentCatalog(new mx.quest.runway.content.ContentSourceRegistry(java.util.List.of())), modelBuilder,
                new WidgetModelBuilder(new ContentWidgetRegistry(List.of()), layout, new mx.quest.runway.widget.WidgetInstanceConfigService(layout, new mx.quest.runway.crypto.AesGcmSecretCipher(new mx.quest.runway.security.RunwaySecurityProperties()))),
                new PageValidatorService(layout),
                new PageRenderer(engine),
                new ErrorPages(layout, new PageRenderer(engine)), new mx.quest.runway.web.RunwayHttpCacheProperties());
        // The canonical contributor plus the real kernel SEO and icon contributors; with nothing configured
        // the latter two are silent.
        HeadInjectionFilter filter = new HeadInjectionFilter(new HeadContributorRegistry(
                List.of(new CanonicalContributor(), new SeoHeadContributor(), new IconHeadContributor())));

        seedSite(siteStore, siteResolver, layout, "acme", "acme.test", "https://acme.test");
        seedSite(siteStore, siteResolver, layout, "other", "other.test", "https://other.test");
        seedSite(siteStore, siteResolver, layout, "bare", null, null); // host-less: the platform's catch-all site
        return new Fixture(controller, filter, siteResolver);
    }

    /** Renders {@code /about} for the bound site and runs the result through the edge filter. */
    private static String injectedHtml(Fixture fixture, String renderedHtml, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            res.setContentType("text/html;charset=UTF-8");
            res.getWriter().write(renderedHtml);
        };
        fixture.filter().doFilter(request, response, chain);
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void markupIsAbsentFromTheTemplateButPresentAfterTheEdge(@TempDir Path tmp) throws Exception {
        Fixture fixture = wire(tmp);
        Site acme = fixture.siteResolver().resolve("acme.test").orElseThrow();
        fixture.siteResolver().bind(acme);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/about");
        request.addPreferredLocale(EN);
        ResponseEntity<String> rendered = fixture.controller().serve(request);
        String html = rendered.getBody();

        // The site template carries no canonical link — injection is template-independent.
        assertFalse(html.contains("rel=\"canonical\""), () -> "template must be clean; got:\n" + html);
        assertInstanceOf(HeadContext.class,
                request.getAttribute(HeadInjectionFilter.HEAD_CONTEXT_ATTRIBUTE), "controller must stash a HeadContext");

        String injected = injectedHtml(fixture, html, request);
        assertTrue(injected.contains("rel=\"canonical\" href=\"https://acme.test/about\""), () -> injected);
        assertTrue(injected.indexOf("rel=\"canonical\"") < injected.indexOf("</head>"), "canonical must land in <head>");
    }

    @Test
    void aSitesSeoConfigIsInjectedIntoTheHeadAndStaysTenantScoped(@TempDir Path tmp) throws Exception {
        Fixture fixture = wire(tmp);
        // acme configures SEO; other.test configures nothing.
        write(tmp.resolve("sites/acme/seo.yml"),
                "ogImage: https://acme.test/og.png\ntwitterCard: summary_large_image\n");

        fixture.siteResolver().bind(fixture.siteResolver().resolve("acme.test").orElseThrow());
        MockHttpServletRequest acmeReq = new MockHttpServletRequest("GET", "/about");
        acmeReq.addPreferredLocale(EN);
        String acmeRendered = fixture.controller().serve(acmeReq).getBody();
        String acme = injectedHtml(fixture, acmeRendered, acmeReq);

        assertFalse(acmeRendered.contains("og:image"), "SEO is edge-injected, never in the template");
        assertTrue(acme.contains("<meta property=\"og:image\" content=\"https://acme.test/og.png\">"), () -> acme);
        assertTrue(acme.contains("<meta name=\"twitter:card\" content=\"summary_large_image\">"), () -> acme);
        assertTrue(acme.indexOf("og:image") < acme.indexOf("</head>"), "SEO lands inside <head>");

        // The unconfigured tenant gets no SEO tags — the config is per-site.
        fixture.siteResolver().clear();
        fixture.siteResolver().bind(fixture.siteResolver().resolve("other.test").orElseThrow());
        MockHttpServletRequest otherReq = new MockHttpServletRequest("GET", "/about");
        otherReq.addPreferredLocale(EN);
        String other = injectedHtml(fixture, fixture.controller().serve(otherReq).getBody(), otherReq);
        assertFalse(other.contains("og:image"), "an unconfigured site emits no SEO tags");
    }

    @Test
    void aSitesIconsAreLinkedIntoTheHead(@TempDir Path tmp) throws Exception {
        Fixture fixture = wire(tmp);
        // acme provides an SVG favicon and a 192 PNG; the head must reference them + the manifest.
        write(tmp.resolve("sites/acme/icons/favicon.svg"), "<svg/>");
        write(tmp.resolve("sites/acme/icons/icon-192.png"), "PNG");

        fixture.siteResolver().bind(fixture.siteResolver().resolve("acme.test").orElseThrow());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/about");
        req.addPreferredLocale(EN);
        String injected = injectedHtml(fixture, fixture.controller().serve(req).getBody(), req);

        assertTrue(injected.contains("<link rel=\"icon\" type=\"image/svg+xml\" href=\"/favicon.svg\">"), () -> injected);
        assertTrue(injected.contains("<link rel=\"manifest\" href=\"/manifest.webmanifest\">"),
                () -> "a manifest-eligible icon triggers the manifest link: " + injected);
        assertTrue(injected.indexOf("rel=\"icon\"") < injected.indexOf("</head>"), "icon links land in <head>");
    }

    @Test
    void eachTenantGetsItsOwnCanonical(@TempDir Path tmp) throws Exception {
        Fixture fixture = wire(tmp);

        fixture.siteResolver().bind(fixture.siteResolver().resolve("acme.test").orElseThrow());
        MockHttpServletRequest acmeReq = new MockHttpServletRequest("GET", "/about");
        acmeReq.addPreferredLocale(EN);
        String acme = injectedHtml(fixture, fixture.controller().serve(acmeReq).getBody(), acmeReq);
        fixture.siteResolver().clear();

        fixture.siteResolver().bind(fixture.siteResolver().resolve("other.test").orElseThrow());
        MockHttpServletRequest otherReq = new MockHttpServletRequest("GET", "/about");
        otherReq.addPreferredLocale(EN);
        String other = injectedHtml(fixture, fixture.controller().serve(otherReq).getBody(), otherReq);

        assertTrue(acme.contains("href=\"https://acme.test/about\""), () -> acme);
        assertTrue(other.contains("href=\"https://other.test/about\""), () -> other);
        assertFalse(other.contains("acme.test"), "no cross-tenant bleed");
        assertNotEquals(acme, other);
    }

    /**
     * SPI 1.0.0: the head context carries the trusted origin, so a contributor can build absolute URLs (JSON-LD) for a
     * host-less site (no hosts, no base URL: nothing to derive one from) — from the request as the trusted-proxy gate
     * left it — while the configured base URL wins over the request's host where there is one. The head's own links
     * keep their rule (root-relative on the host-less site).
     */
    @Test
    void theHeadContextCarriesTheTrustedOrigin(@TempDir Path tmp) throws Exception {
        Fixture fixture = wire(tmp);

        // A host-less site (no hosts, no base URL) — what the default catch-all site is — has no derivable origin.
        fixture.siteResolver().bind(new Site("bare", "bare", List.of(), EN, List.of(EN, Locale.of("es")), null,
                SiteStatus.ACTIVE));
        MockHttpServletRequest bare = new MockHttpServletRequest("GET", "/about");
        bare.setScheme("https");
        bare.setServerName("Bare.Test");
        bare.setServerPort(443);
        bare.addPreferredLocale(EN);
        fixture.controller().serve(bare);
        HeadContext bareHead = (HeadContext) bare.getAttribute(HeadInjectionFilter.HEAD_CONTEXT_ATTRIBUTE);
        org.junit.jupiter.api.Assertions.assertEquals("https://bare.test", bareHead.origin(),
                "a host-less site gets the request origin (lowercased, default port omitted)");
        org.junit.jupiter.api.Assertions.assertEquals("/about", bareHead.canonical(),
                "the canonical keeps its rule: root-relative without a base URL");

        fixture.siteResolver().clear();
        fixture.siteResolver().bind(fixture.siteResolver().resolve("acme.test").orElseThrow());
        MockHttpServletRequest acme = new MockHttpServletRequest("GET", "/about");
        acme.setScheme("http");
        acme.setServerName("acme.test");
        acme.setServerPort(8080);
        acme.addPreferredLocale(EN);
        fixture.controller().serve(acme);
        HeadContext acmeHead = (HeadContext) acme.getAttribute(HeadInjectionFilter.HEAD_CONTEXT_ATTRIBUTE);
        org.junit.jupiter.api.Assertions.assertEquals("https://acme.test", acmeHead.origin(),
                "the configured base URL wins over the request's own host");
    }
}
