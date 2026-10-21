package mx.quest.runway.web;
import mx.quest.runway.platform.TestPlatformSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.thymeleaf.spring6.SpringTemplateEngine;

/** Conditional GET: a 200 carries strong validators, and a matching If-None-Match short-circuits to 304. */
class PageConditionalGetTest {

    private static final Locale EN = Locale.ENGLISH;

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

        Site site = new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN), "https://acme.test", SiteStatus.ACTIVE);
        siteStore.save(site);
        siteResolver.bind(site);

        Path t = layout.siteDir("acme").resolve("templates");
        write(t.resolve("_master.html"), "<!DOCTYPE html><html><head><title>M</title></head><body><main id=\"content\">m</main></body></html>");
        write(t.resolve("_master.th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>");
        write(t.resolve("about.html"), "<!DOCTYPE html><html><head><title>A</title></head><body><main id=\"content\"><h1 id=\"t\">PH</h1></main></body></html>");
        write(t.resolve("about.th.xml"), "<?xml version=\"1.0\"?><thlogic><attr sel=\"head\" th:replace=\"~{${master} :: head}\"/><attr sel=\"#t\" th:text=\"${pageTitle}\"/></thlogic>");
        write(t.resolve("about.meta.yml"), "path: /about\nlocales: [en]\ntitle:\n  en: About\n");
        return controller;
    }

    private static MockHttpServletRequest get() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/about");
        request.addPreferredLocale(EN);
        return request;
    }

    @Test
    void okCarriesStrongValidators(@TempDir Path tmp) throws IOException {
        ResponseEntity<String> response = wire(tmp).serve(get());

        assertEquals(200, response.getStatusCode().value());
        String etag = response.getHeaders().getETag();
        assertNotNull(etag, "a 200 must carry an ETag");
        assertTrue(etag.startsWith("\"") && etag.endsWith("\""), "strong, quoted ETag");
        assertEquals(-1, response.getHeaders().getLastModified(),
                "no Last-Modified: it could not move with every input the ETag folds");
        // The default public-page Cache-Control now carries a bounded max-age (parity default), not no-cache;
        // the ETag above still busts the cache the instant the content changes.
        assertTrue(response.getHeaders().getCacheControl().contains("max-age=300"),
                "the configured public-pages default is applied");
    }

    @Test
    void perPageCacheControlOverridesTheDefault(@TempDir Path tmp) throws IOException {
        PageController controller = wire(tmp);
        // Re-write the page's meta with an explicit per-page Cache-Control override.
        write(tmp.resolve("sites/acme/templates/about.meta.yml"),
                "path: /about\nlocales: [en]\ntitle:\n  en: About\ncacheControl: \"no-cache\"\n");

        ResponseEntity<String> response = controller.serve(get());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-cache", response.getHeaders().getCacheControl(),
                "a per-page override is used verbatim, not the platform default");
        assertNotNull(response.getHeaders().getETag(), "the ETag still coexists with the override");
    }

    @Test
    void matchingIfNoneMatchShortCircuitsTo304(@TempDir Path tmp) throws IOException {
        PageController controller = wire(tmp);
        String etag = controller.serve(get()).getHeaders().getETag();

        MockHttpServletRequest conditional = get();
        conditional.addHeader(HttpHeaders.IF_NONE_MATCH, etag);
        ResponseEntity<String> response = controller.serve(conditional);

        assertEquals(304, response.getStatusCode().value());
        assertNull(response.getBody(), "304 carries no body");
        assertEquals(etag, response.getHeaders().getETag(), "304 echoes the ETag");
    }

    @Test
    void staleIfNoneMatchStillRenders(@TempDir Path tmp) throws IOException {
        MockHttpServletRequest conditional = get();
        conditional.addHeader(HttpHeaders.IF_NONE_MATCH, "\"deadbeef\"");
        ResponseEntity<String> response = wire(tmp).serve(conditional);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("About"), "a non-matching ETag forces a fresh render");
    }
}
