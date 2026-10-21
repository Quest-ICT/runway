package mx.quest.runway.web;
import mx.quest.runway.platform.TestPlatformSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.page.PageRouting;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageValidatorService;
import mx.quest.runway.page.RenderModelBuilder;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
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
import org.thymeleaf.spring6.SpringTemplateEngine;

/** End-to-end localized-path routing: a page with {@code localizedPaths:{es:/acerca-de}} on an en-default site. */
class PageLocalizedPathTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static PageController wire(Path tmp) throws IOException {
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

        PageController controller = new PageController(siteResolver,
                new PageRouting(new PageStore(layout, yaml)), new mx.quest.runway.content.ContentRouter(new mx.quest.runway.content.ContentSourceRegistry(java.util.List.of())), new mx.quest.runway.content.ContentCatalog(new mx.quest.runway.content.ContentSourceRegistry(java.util.List.of())), new RenderModelBuilder(),
                new WidgetModelBuilder(new ContentWidgetRegistry(List.of()), layout, new mx.quest.runway.widget.WidgetInstanceConfigService(layout, new mx.quest.runway.crypto.AesGcmSecretCipher(new mx.quest.runway.security.RunwaySecurityProperties()))),
                new PageValidatorService(layout), new PageRenderer(engine),
                new ErrorPages(layout, new PageRenderer(engine)), new mx.quest.runway.web.RunwayHttpCacheProperties());

        Site site = new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN, ES), "https://acme.test", SiteStatus.ACTIVE);
        siteStore.save(site);
        siteResolver.bind(site);

        Path t = layout.siteDir("acme").resolve("templates");
        write(t.resolve("_master.html"), "<!DOCTYPE html><html><head><title>M</title></head><body><main id=\"content\">m</main></body></html>");
        write(t.resolve("_master.th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>");
        write(t.resolve("about.html"), "<!DOCTYPE html><html><head><title>A</title></head><body><main id=\"content\"><h1 id=\"t\">PH</h1><a id=\"lnk\" href=\"#\">to products</a></main></body></html>");
        write(t.resolve("about.th.xml"), "<?xml version=\"1.0\"?><thlogic><attr sel=\"head\" th:replace=\"~{${master} :: head}\"/><attr sel=\"#t\" th:text=\"${pageTitle}\"/><attr sel=\"#lnk\" th:attr=\"href=${urls['prod']}\"/></thlogic>");
        write(t.resolve("about.meta.yml"), "path: /about\nlocales: [en, es]\nlocalizedPaths:\n  es: /acerca-de\ntitle:\n  en: About\n  es: Acerca\n");
        // A multi-segment localized path, to exercise routing + the switcher round-trip beyond one segment.
        write(t.resolve("prod.html"), "<!DOCTYPE html><html><head><title>P</title></head><body><main id=\"content\"><h1 id=\"t\">PH</h1></main></body></html>");
        write(t.resolve("prod.th.xml"), "<?xml version=\"1.0\"?><thlogic><attr sel=\"head\" th:replace=\"~{${master} :: head}\"/><attr sel=\"#t\" th:text=\"${pageTitle}\"/></thlogic>");
        write(t.resolve("prod.meta.yml"), "path: /products\nlocales: [en, es]\nlocalizedPaths:\n  es: /productos/mi-producto\ntitle:\n  en: Products\n  es: Productos\n");
        // The documented locale-shadow footgun: a page whose path's leading segment is a configured
        // locale code (es) is unreachable — its canonical /es is re-parsed as the Spanish prefix.
        write(t.resolve("esp.meta.yml"), "path: /es\nlocales: [en]\n");
        return controller;
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addPreferredLocale(EN); // deterministic Accept-Language
        return request;
    }

    @Test
    void defaultServesUnprefixed(@TempDir Path tmp) throws IOException {
        ResponseEntity<String> response = wire(tmp).serve(get("/about"));
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("About"));
    }

    @Test
    void localizedPrefixedUrlServesTheLocale(@TempDir Path tmp) throws IOException {
        ResponseEntity<String> response = wire(tmp).serve(get("/es/acerca-de"));
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Acerca"), () -> response.getBody());
    }

    @Test
    void nonLocalizedLocaleUrlIs404(@TempDir Path tmp) throws IOException {
        // The Spanish URL is /es/acerca-de, so /es/about must NOT resolve.
        assertEquals(404, wire(tmp).serve(get("/es/about")).getStatusCode().value());
    }

    @Test
    void localizedSlugIsUnreachableUnprefixedOrUnderDefaultPrefix(@TempDir Path tmp) throws IOException {
        PageController controller = wire(tmp);
        assertEquals(404, controller.serve(get("/acerca-de")).getStatusCode().value());
        assertEquals(404, controller.serve(get("/en/acerca-de")).getStatusCode().value());
    }

    @Test
    void defaultPrefixRedirectsToUnprefixed(@TempDir Path tmp) throws IOException {
        ResponseEntity<String> response = wire(tmp).serve(get("/en/about"));
        assertEquals(301, response.getStatusCode().value());
        assertEquals("/about", response.getHeaders().getFirst("Location"));
    }

    @Test
    void switcherRedirectsToTheLocalizedUrlAndSetsCookie(@TempDir Path tmp) throws IOException {
        MockHttpServletRequest request = get("/about");
        request.setParameter("lang", "es");
        ResponseEntity<String> response = wire(tmp).serve(request);
        assertEquals(302, response.getStatusCode().value());
        assertEquals("/es/acerca-de", response.getHeaders().getFirst("Location"));
        assertTrue(response.getHeaders().getFirst("Set-Cookie").contains("runway_lang_acme=es"));
    }

    @Test
    void preferenceCookieRedirectsUnprefixedToLocalizedUrl(@TempDir Path tmp) throws IOException {
        MockHttpServletRequest request = get("/about");
        request.setCookies(new Cookie("runway_lang_acme", "es"));
        ResponseEntity<String> response = wire(tmp).serve(request);
        assertEquals(302, response.getStatusCode().value());
        assertEquals("/es/acerca-de", response.getHeaders().getFirst("Location"));
    }

    @Test
    void multiSegmentLocalizedPathRoutesAndRoundTrips(@TempDir Path tmp) throws IOException {
        PageController controller = wire(tmp);

        assertEquals(200, controller.serve(get("/es/productos/mi-producto")).getStatusCode().value());

        MockHttpServletRequest switching = get("/products");
        switching.setParameter("lang", "es");
        ResponseEntity<String> response = controller.serve(switching);
        assertEquals(302, response.getStatusCode().value());
        assertEquals("/es/productos/mi-producto", response.getHeaders().getFirst("Location"), "switcher round-trips multi-segment");
    }

    /**
     * Trailing-slash variants are now <b>canonicalised with a 301</b> rather than 404'd — including on a
     * locale-prefixed path, where the redirect must keep the prefix rather than dropping the visitor into the
     * default language.
     *
     * <p>This replaces an earlier assertion that locked in the 404. That test carried its own explanation —
     * "no canonicalization yet — deferred" — so it recorded a known gap rather than a deliberate rule, and
     * closing the gap is what changed it. The property it really protected still holds: there is no {@code 200}
     * at the variant, so no duplicate content is served; the variant now points at the canonical instead of
     * being a dead end.
     */
    @Test
    void trailingSlashVariantsAreCanonicalisedNotRejected(@TempDir Path tmp) throws IOException {
        PageController controller = wire(tmp);

        ResponseEntity<String> localized = controller.serve(get("/es/acerca-de/"));
        assertEquals(301, localized.getStatusCode().value());
        assertEquals("/es/acerca-de", localized.getHeaders().getFirst("Location"),
                "the locale prefix must survive the canonicalisation");

        ResponseEntity<String> defaultLocale = controller.serve(get("/about/"));
        assertEquals(301, defaultLocale.getStatusCode().value());
        assertEquals("/about", defaultLocale.getHeaders().getFirst("Location"));

        // The invariant the old test was really protecting: never a 200 at the variant.
        assertEquals(301, controller.serve(get("/about/")).getStatusCode().value());
    }

    @Test
    void byIdLinksResolveToTheTargetsLocaleCorrectUrl(@TempDir Path tmp) throws IOException {
        PageController controller = wire(tmp);
        // The English render links to /products; the Spanish render of the same link → /es/productos/mi-producto.
        assertTrue(controller.serve(get("/about")).getBody().contains("href=\"/products\""));
        assertTrue(controller.serve(get("/es/acerca-de")).getBody().contains("href=\"/es/productos/mi-producto\""),
                () -> controller.serve(get("/es/acerca-de")).getBody());
    }

    @Test
    void aPathShadowedByALocaleCodeIsUnreachableButFailsSafe(@TempDir Path tmp) throws IOException {
        // The /es page's own canonical URL is re-parsed as the Spanish prefix, so it 404s — a
        // documented fail-safe footgun (never serves another page; site-aware validation is deferred).
        assertEquals(404, wire(tmp).serve(get("/es")).getStatusCode().value());
    }
}
