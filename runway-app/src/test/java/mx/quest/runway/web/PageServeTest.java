package mx.quest.runway.web;
import mx.quest.runway.platform.TestPlatformSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.thymeleaf.spring6.SpringTemplateEngine;

/** End-to-end: a page on disk is served over an HTTP request, with master chrome pulled in and bound content. */
class PageServeTest {

    private record Fixture(SiteResolver siteResolver, PageController controller) {
    }

    private static StorageLayout layoutIn(Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return StorageLayout.from(props);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static Fixture wire(Path tmp) throws IOException {
        StorageLayout layout = layoutIn(tmp);
        YamlStore yaml = new YamlStore();
        SiteStore siteStore = new SiteStore(layout, yaml);
        SiteResolver siteResolver = new SiteResolver(siteStore, TestPlatformSettings.empty());
        PageStore pageStore = new PageStore(layout, yaml);

        GenericApplicationContext appContext = new GenericApplicationContext();
        appContext.refresh();
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new SiteTemplateResolver(layout));
        PageRenderer renderer = new PageRenderer(engine);

        PageController controller = new PageController(
                siteResolver, new PageRouting(pageStore), new mx.quest.runway.content.ContentRouter(new mx.quest.runway.content.ContentSourceRegistry(java.util.List.of())), new mx.quest.runway.content.ContentCatalog(new mx.quest.runway.content.ContentSourceRegistry(java.util.List.of())), new RenderModelBuilder(),
                new WidgetModelBuilder(new ContentWidgetRegistry(List.of()), layout, new mx.quest.runway.widget.WidgetInstanceConfigService(layout, new mx.quest.runway.crypto.AesGcmSecretCipher(new mx.quest.runway.security.RunwaySecurityProperties()))),
                new PageValidatorService(layout), renderer, new ErrorPages(layout, renderer), new mx.quest.runway.web.RunwayHttpCacheProperties());

        // A site, bound as the current request's tenant.
        Site site = new Site("acme", "Acme Corp", List.of("acme.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), "https://acme.test", SiteStatus.ACTIVE);
        siteStore.save(site);
        siteResolver.bind(site);

        // The site's templates: a master, and an "about" page that pulls the master's chrome.
        Path templates = layout.siteDir("acme").resolve("templates");
        write(templates.resolve("_master.html"), """
                <!DOCTYPE html>
                <html><head><title>Master</title></head>
                <body><header id="site-header"><span id="brand">PLACEHOLDER</span></header>
                <main id="content">m</main><footer id="site-footer">Footer</footer></body></html>""");
        write(templates.resolve("_master.th.xml"), """
                <?xml version="1.0"?>
                <thlogic><attr sel="#brand" th:text="${site.displayName()}"/></thlogic>""");
        write(templates.resolve("about.html"), """
                <!DOCTYPE html>
                <html><head><title>About</title></head>
                <body><header id="site-header">PH</header>
                <main id="content"><h1 id="about-title">PH</h1></main>
                <footer id="site-footer">PH</footer></body></html>""");
        write(templates.resolve("about.th.xml"), """
                <?xml version="1.0"?>
                <thlogic>
                  <attr sel="head" th:replace="~{${master} :: head}"/>
                  <attr sel="#site-header" th:replace="~{${master} :: #site-header}"/>
                  <attr sel="#site-footer" th:replace="~{${master} :: #site-footer}"/>
                  <attr sel="#about-title" th:text="${pageTitle}"/>
                </thlogic>""");
        write(templates.resolve("about.meta.yml"), "path: /about\ntemplate: _master\ntitle:\n  en: About Us\n");

        return new Fixture(siteResolver, controller);
    }

    @Test
    void servesARenderedPageWithMasterChromeAndBoundContent(@TempDir Path tmp) throws IOException {
        PageController controller = wire(tmp).controller();
        ResponseEntity<String> response = controller.serve(new MockHttpServletRequest("GET", "/about"));

        assertEquals(200, response.getStatusCode().value());
        String html = response.getBody();
        assertTrue(html.contains("About Us"), () -> "page title bound; got:\n" + html);     // ${pageTitle}
        assertTrue(html.contains("Acme Corp"), () -> "master chrome + logic applied; got:\n" + html); // master #brand
        assertTrue(html.contains("Footer"), "master footer pulled in");
    }

    @Test
    void unknownPathIs404(@TempDir Path tmp) throws IOException {
        PageController controller = wire(tmp).controller();
        assertEquals(404, controller.serve(new MockHttpServletRequest("GET", "/missing")).getStatusCode().value());
    }

    @Test
    void noResolvedSiteIs404(@TempDir Path tmp) throws IOException {
        Fixture fixture = wire(tmp);
        fixture.siteResolver().clear(); // no tenant bound for this request
        assertEquals(404, fixture.controller().serve(new MockHttpServletRequest("GET", "/about")).getStatusCode().value());
    }

    // --- a redirect must not discard what the visitor typed -----------------------------------

    /**
     * A near-miss path redirects to the canonical URL <b>carrying the query string</b>.
     *
     * <p>Redirects previously built {@code Location} from a bare path, so {@code /about/?ref=newsletter}
     * lost {@code ref} on the hop. On a multilingual site the locale redirects are the common path, so this
     * discarded real visitors' parameters — search terms, campaign tags, deep-link state — not just crawler
     * tidiness.
     */
    @Test
    void aRedirectCarriesTheQueryString(@TempDir Path tmp) throws IOException {
        PageController controller = wire(tmp).controller();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/about/");
        request.setQueryString("ref=newsletter&q=espresso");

        ResponseEntity<String> response = controller.serve(request);

        assertEquals(HttpStatus.MOVED_PERMANENTLY, response.getStatusCode());
        String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertEquals("/about?ref=newsletter&q=espresso", location,
                "the redirect must preserve every parameter the visitor sent");
    }

    /**
     * A query string is client input and the value lands in a response header, so CR/LF must not survive —
     * a newline there would let a client forge headers (response splitting). Belt and braces: the container
     * should never deliver such a request line, but the cost of being wrong is severe and the guard is free.
     */
    @Test
    void aRedirectLocationCannotBeSplitByACraftedQuery(@TempDir Path tmp) throws IOException {
        PageController controller = wire(tmp).controller();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/about/");
        request.setQueryString("a=1\r\nX-Injected: yes");

        String location = controller.serve(request).getHeaders().getFirst(HttpHeaders.LOCATION);

        assertTrue(location != null && location.indexOf('\r') < 0 && location.indexOf('\n') < 0,
                "no CR/LF may reach the Location header: " + location);
    }

    // --- a near-miss is a redirect, not a dead end ---------------------------------------------

    /**
     * A trailing slash redirects to the canonical path instead of 404ing.
     *
     * <p>Exact matching is safe against duplicate content — there is no {@code 200} at the variant — but it
     * turns every inbound link carrying a stray slash into a dead end, and those links are usually on somebody
     * else's site where nobody can go back and fix them.
     */
    @Test
    void aTrailingSlashRedirectsToTheCanonicalPath(@TempDir Path tmp) throws IOException {
        ResponseEntity<String> response = wire(tmp).controller()
                .serve(new MockHttpServletRequest("GET", "/about/"));

        assertEquals(HttpStatus.MOVED_PERMANENTLY, response.getStatusCode());
        assertEquals("/about", response.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    /** Letter case is the same class of near-miss — a hand-typed capital should not be a dead end. */
    @Test
    void aCaseDifferenceRedirectsToTheCanonicalPath(@TempDir Path tmp) throws IOException {
        ResponseEntity<String> response = wire(tmp).controller()
                .serve(new MockHttpServletRequest("GET", "/About"));

        assertEquals(HttpStatus.MOVED_PERMANENTLY, response.getStatusCode());
        assertEquals("/about", response.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    /**
     * The redirect must terminate in ONE hop: following it lands a 200, never another redirect. A near-miss
     * rule that produced a chain — or worse, redirected a path to itself — would be a loop on the busiest
     * possible route.
     */
    @Test
    void theNearMissRedirectTerminatesInOneHop(@TempDir Path tmp) throws IOException {
        PageController controller = wire(tmp).controller();
        String location = controller.serve(new MockHttpServletRequest("GET", "/About/"))
                .getHeaders().getFirst(HttpHeaders.LOCATION);

        ResponseEntity<String> followed = controller.serve(new MockHttpServletRequest("GET", location));
        assertEquals(HttpStatus.OK, followed.getStatusCode(), "one hop to a 200, no chain and no loop");
    }

    /** A genuinely unknown path must still 404 — the near-miss rule must not turn every miss into a redirect. */
    @Test
    void anUnrelatedPathStill404s(@TempDir Path tmp) throws IOException {
        ResponseEntity<String> response = wire(tmp).controller()
                .serve(new MockHttpServletRequest("GET", "/no-such-page"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
