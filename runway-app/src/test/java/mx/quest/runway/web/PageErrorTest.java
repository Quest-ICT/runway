package mx.quest.runway.web;
import mx.quest.runway.platform.TestPlatformSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.thymeleaf.spring6.SpringTemplateEngine;

/** Error pages: the tenant {@code _<code>} page through its master, with a master-independent loud-fail. */
class PageErrorTest {

    private static final Locale EN = Locale.ENGLISH;

    private record Fixture(PageController controller, SiteResolver siteResolver, StorageLayout layout) {}

    private static void write(StorageLayout layout, String name, String content) throws IOException {
        Path file = layout.siteDir("acme").resolve("templates").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
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
        PageRenderer renderer = new PageRenderer(engine);

        PageController controller = new PageController(siteResolver,
                new PageRouting(new PageStore(layout, yaml)), new mx.quest.runway.content.ContentRouter(new mx.quest.runway.content.ContentSourceRegistry(java.util.List.of())), new mx.quest.runway.content.ContentCatalog(new mx.quest.runway.content.ContentSourceRegistry(java.util.List.of())), new RenderModelBuilder(),
                new WidgetModelBuilder(new ContentWidgetRegistry(List.of()), layout, new mx.quest.runway.widget.WidgetInstanceConfigService(layout, new mx.quest.runway.crypto.AesGcmSecretCipher(new mx.quest.runway.security.RunwaySecurityProperties()))),
                new PageValidatorService(layout), renderer, new ErrorPages(layout, renderer), new mx.quest.runway.web.RunwayHttpCacheProperties());

        Site site = new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN), "https://acme.test", SiteStatus.ACTIVE);
        siteStore.save(site);
        siteResolver.bind(site);
        return new Fixture(controller, siteResolver, layout);
    }

    private static void master(StorageLayout layout) throws IOException {
        write(layout, "_master.html", "<!DOCTYPE html><html><head><title>M</title></head><body><main id=\"content\">m</main></body></html>");
        write(layout, "_master.th.xml", "<?xml version=\"1.0\"?><thlogic></thlogic>");
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addPreferredLocale(EN);
        return request;
    }

    @Test
    void missingPageWithNoCustomErrorPageServesTheStaticFallback(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        master(f.layout());

        ResponseEntity<String> response = f.controller().serve(get("/missing"));

        assertEquals(404, response.getStatusCode().value());
        assertTrue(response.getBody().contains("<h1>404 Not Found</h1>"), () -> response.getBody());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"), "errors are never cached");
        assertTrue(response.getHeaders().getContentType().toString().startsWith("text/html"));
    }

    @Test
    void aSiteCustom404RendersThroughTheMaster(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        master(f.layout());
        write(f.layout(), "_404.html", "<!DOCTYPE html><html><head><title>E</title></head><body><main id=\"content\"><h1 id=\"msg\">PH</h1></main></body></html>");
        write(f.layout(), "_404.th.xml", "<?xml version=\"1.0\"?><thlogic><attr sel=\"head\" th:replace=\"~{${master} :: head}\"/><attr sel=\"#msg\" th:text=\"${pageTitle}\"/></thlogic>");

        ResponseEntity<String> response = f.controller().serve(get("/missing"));

        assertEquals(404, response.getStatusCode().value());
        assertTrue(response.getBody().contains("<title>M</title>"), () -> "composed through the master; got:\n" + response.getBody());
        assertTrue(response.getBody().contains("404 Not Found"), () -> response.getBody());
    }

    /**
     * A master commonly shows the site's params and contact (a phone in the footer). Error pages carry both, so such
     * a master renders there too; without them every error page fell back to the plain platform page.
     */
    @Test
    void anErrorPageThatReadsParamsAndContactRendersInTheSitesLook(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        master(f.layout());
        write(f.layout(), "_404.html", "<!DOCTYPE html><html><head><title>E</title></head><body><main id=\"content\">"
                + "<h1 id=\"msg\">PH</h1><p id=\"phone\">P</p><p id=\"mail\">M</p><p id=\"robots\">R</p></main></body></html>");
        write(f.layout(), "_404.th.xml", "<?xml version=\"1.0\"?><thlogic>"
                + "<attr sel=\"#phone\" th:text=\"${params['phone']}\"/>"
                + "<attr sel=\"#mail\" th:text=\"${contact.email}\"/>"
                + "<attr sel=\"#robots\" th:text=\"${noindex}\"/></thlogic>");
        Site site = new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN), "https://acme.test", SiteStatus.ACTIVE);

        // Without stored values (the bare form) the page still renders, the fields empty.
        String bare = f.controller().serve(get("/missing")).getBody();
        assertTrue(bare.contains("<p id=\"phone\"></p>"), () -> "rendered, not the fallback; got:\n" + bare);

        mx.quest.runway.storage.YamlStore yaml = new mx.quest.runway.storage.YamlStore();
        mx.quest.runway.site.SiteParamsService params = new mx.quest.runway.site.SiteParamsService(f.layout(), yaml);
        mx.quest.runway.site.SiteContactService contact = new mx.quest.runway.site.SiteContactService(f.layout(), yaml);
        params.save("acme", java.util.Map.of("phone", "+52 55 1234 5678"));
        contact.save("acme", new mx.quest.runway.site.SiteContact("hola@acme.test", null, null));
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new SiteTemplateResolver(f.layout()));
        ErrorPages pages = new ErrorPages(f.layout(), new PageRenderer(engine), params, contact);

        ResponseEntity<String> response = pages.render(site, EN, org.springframework.http.HttpStatus.NOT_FOUND);
        assertEquals(404, response.getStatusCode().value());
        String body = response.getBody();
        assertTrue(body.contains("<p id=\"phone\">+52 55 1234 5678</p>"), () -> body);
        assertTrue(body.contains("<p id=\"mail\">hola@acme.test</p>"), () -> body);
        assertTrue(body.contains("<p id=\"robots\">true</p>"), () -> body);
    }

    @Test
    void aBrokenMasterFailsLoudlyToTheStaticFallback(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        // A custom _404 that pulls the master — but the master is absent, so rendering throws.
        write(f.layout(), "_404.html", "<!DOCTYPE html><html><head><title>E</title></head><body><main id=\"content\"><h1 id=\"msg\">CUSTOM</h1></main></body></html>");
        write(f.layout(), "_404.th.xml", "<?xml version=\"1.0\"?><thlogic><attr sel=\"head\" th:replace=\"~{${master} :: head}\"/></thlogic>");

        ResponseEntity<String> response = f.controller().serve(get("/missing"));

        assertEquals(404, response.getStatusCode().value());
        assertTrue(response.getBody().contains("<h1>404 Not Found</h1>"), () -> "static fallback; got:\n" + response.getBody());
        assertFalse(response.getBody().contains("CUSTOM"), "the broken custom page must not partially render");
    }

    @Test
    void aBrokenPageTemplateYieldsA500(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        master(f.layout());
        // A published page whose logic references a non-existent template — rendering throws.
        write(f.layout(), "boom.html", "<!DOCTYPE html><html><head><title>B</title></head><body><main id=\"content\">b</main></body></html>");
        write(f.layout(), "boom.th.xml", "<?xml version=\"1.0\"?><thlogic><attr sel=\"head\" th:replace=\"~{__site__/acme/_nope :: head}\"/></thlogic>");
        write(f.layout(), "boom.meta.yml", "path: /boom\nlocales: [en]\n");

        ResponseEntity<String> response = f.controller().serve(get("/boom"));

        assertEquals(500, response.getStatusCode().value());
        assertTrue(response.getBody().contains("<h1>500 Internal Server Error</h1>"), () -> response.getBody());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
    }

    @Test
    void noMatchingTenantServesTheStatic404(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        f.siteResolver().clear(); // no site bound — an unconfigured host

        ResponseEntity<String> response = f.controller().serve(get("/anything"));

        assertEquals(404, response.getStatusCode().value());
        assertTrue(response.getBody().contains("<h1>404 Not Found</h1>"), () -> response.getBody());
    }

    // ---- 410 Gone, end to end through the controller ---------------------------------------------

    /**
     * A page marked as permanently removed answers {@code 410} through the real serve path.
     *
     * <p>Asserted here rather than only on the routing predicate because the status has to survive the whole
     * response: the branch has to be reached before the near-miss redirect, the error renderer has to accept a
     * status it has never been asked for, and the body has to say the right thing. A predicate that returns
     * {@code true} into a {@code 404} would look correct in every unit test of the predicate.
     */
    @Test
    void aPageMarkedRemovedAnswers410(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        master(f.layout());
        write(f.layout(), "old.meta.yml", "path: /old\ndraft: true\ngone: true\n");

        ResponseEntity<String> response = f.controller().serve(get("/old"));

        assertEquals(410, response.getStatusCode().value());
        assertTrue(response.getBody().contains("410 Gone"), () -> response.getBody());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"),
                "a tombstone is a decision the operator can reverse — it must not be cached as permanent");
    }

    /** Without the marker the same unpublished page is an ordinary {@code 404}. */
    @Test
    void anUnpublishedPageWithoutTheMarkerIsStill404(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        master(f.layout());
        write(f.layout(), "old.meta.yml", "path: /old\ndraft: true\n");

        assertEquals(404, f.controller().serve(get("/old")).getStatusCode().value());
    }

    /**
     * A site may style {@code 410} the way it styles {@code 404} — the renderer is generic over the status,
     * and this pins that the tombstone is a first-class error page rather than a hardcoded fallback.
     *
     * <p><b>The discriminating assertion is the master's title</b>, not the status and not the words "410
     * Gone". Both of those are produced by the built-in static fallback too
     * ({@code <h1>410 Gone</h1>}), so asserting only on them tests nothing this file does not already assert
     * two tests earlier, and would keep passing if {@code ErrorPages} stopped looking up site templates for
     * this status at all. {@code <title>M</title>} comes from the site's master and can only appear when the
     * custom {@code _410} was actually composed through it — which is the whole claim.
     */
    @Test
    void aSiteCustom410RendersThroughTheMaster(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        master(f.layout());
        write(f.layout(), "old.meta.yml", "path: /old\ndraft: true\ngone: true\n");
        write(f.layout(), "_410.html", "<!DOCTYPE html><html><head><title>E</title></head><body><main id=\"content\"><h1 id=\"msg\">PH</h1></main></body></html>");
        write(f.layout(), "_410.th.xml", "<?xml version=\"1.0\"?><thlogic><attr sel=\"head\" th:replace=\"~{${master} :: head}\"/><attr sel=\"#msg\" th:text=\"${pageTitle}\"/></thlogic>");

        ResponseEntity<String> response = f.controller().serve(get("/old"));

        assertEquals(410, response.getStatusCode().value());
        assertTrue(response.getBody().contains("<title>M</title>"),
                () -> "the site's _410 must be composed through the master; got:\n" + response.getBody());
        assertTrue(response.getBody().contains("410 Gone"), () -> response.getBody());
    }
}
