package mx.quest.runway.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import mx.quest.runway.head.HeadInjectionFilter;
import mx.quest.runway.page.PageRouting;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageValidatorService;
import mx.quest.runway.page.RenderModelBuilder;
import mx.quest.runway.platform.TestPlatformSettings;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.ErrorPages;
import mx.quest.runway.web.PageController;
import mx.quest.runway.web.PageRenderer;
import mx.quest.runway.web.RunwayHttpCacheProperties;
import mx.quest.runway.web.SiteTemplateResolver;
import mx.quest.runway.widget.ContentWidgetRegistry;
import mx.quest.runway.widget.WidgetInstanceConfigService;
import mx.quest.runway.widget.WidgetModelBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * The virtual-content acceptance gate: virtual content must behave <b>identically to a
 * real page</b> through every row of the locale-dispatch table (SITE-MANAGER-GUIDE §9) — same statuses, same {@code
 * Location}s,
 * same cookies — because both run the SAME ladder code; plus the collision policy (real page wins; a
 * virtual-vs-virtual clash is a loud 500), the kernel's fail-closed sanitization of source answers, failure
 * containment (a throwing source is a miss, never a 500), and the enumerate-vs-body contract (a {@code 304}
 * revalidation never calls {@code body()}).
 *
 * <p>Runs the REAL controller/ladder/renderer over a temp tenant — Thymeleaf's template cache is ON (the
 * resolver default), per the cache-ON test rule.
 */
class VirtualContentRoutingTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");

    /** A synthetic in-memory source; counts body() calls so the 304-never-fetches-body contract is provable. */
    private static final class FixedSource implements ContentSource {
        private final String key;
        private final List<ContentRef> refs;
        final AtomicInteger bodyCalls = new AtomicInteger();

        FixedSource(String key, List<ContentRef> refs) {
            this.key = key;
            this.refs = refs;
        }

        @Override public String key() { return key; }
        @Override public List<ContentRef> enumerate(SiteView site) { return refs; }
        @Override public ContentBody body(SiteView site, String id, Locale locale) {
            bodyCalls.incrementAndGet();
            return new ContentBody("<p>BODY-" + id + "-" + locale.getLanguage() + "</p>", Map.of());
        }
    }

    private record Fixture(PageController controller, FixedSource blog) {
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** The blog entry: locales + localized slug mirror the real page EXACTLY, so parity is comparable. */
    private static ContentRef entry() {
        return ContentRef.of("my-post", "/blog/my-post", Map.of("es", "/blog/mi-entrada"), List.of(EN, ES),
                Map.of("en", "My post", "es", "Mi entrada"), Map.of("en", "A post"),
                "__site__/acme/entry", null);
    }

    private static Fixture wire(Path tmp, List<ContentSource> sources) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore siteStore = new SiteStore(layout, yaml);
        SiteResolver siteResolver = new SiteResolver(siteStore, TestPlatformSettings.empty());

        GenericApplicationContext appContext = new GenericApplicationContext();
        appContext.refresh();
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new SiteTemplateResolver(layout)); // cacheable=true — cache ON

        FixedSource blog = sources.stream().filter(s -> s instanceof FixedSource).map(FixedSource.class::cast)
                .findFirst().orElse(null);
        PageController controller = new PageController(siteResolver,
                new PageRouting(new PageStore(layout, yaml)),
                new ContentRouter(new ContentSourceRegistry(sources)),
                new ContentCatalog(new ContentSourceRegistry(sources)),
                new RenderModelBuilder(),
                new WidgetModelBuilder(new ContentWidgetRegistry(List.of()), layout,
                        new WidgetInstanceConfigService(layout,
                                new mx.quest.runway.crypto.AesGcmSecretCipher(
                                        new mx.quest.runway.security.RunwaySecurityProperties()))),
                new PageValidatorService(layout),
                new PageRenderer(engine),
                new ErrorPages(layout, new PageRenderer(engine)), new RunwayHttpCacheProperties());

        Site site = new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN, ES), "https://acme.test",
                SiteStatus.ACTIVE);
        siteStore.save(site);
        siteResolver.bind(site);

        Path templates = layout.siteDir("acme").resolve("templates");
        write(templates.resolve("_master.html"), "<!DOCTYPE html><html><head><title>M</title></head><body>"
                + "<header id=\"site-header\">h</header><main id=\"content\">m</main></body></html>");
        write(templates.resolve("_master.th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>");
        // The real page — the parity twin: same locales, same localized-slug shape as the virtual entry.
        write(templates.resolve("about.html"), "<!DOCTYPE html><html><head><title>A</title></head><body>"
                + "<header id=\"site-header\">h</header><main id=\"content\"><h1 id=\"t\">PH</h1></main></body></html>");
        write(templates.resolve("about.th.xml"), "<?xml version=\"1.0\"?><thlogic>"
                + "<attr sel=\"head\" th:replace=\"~{${master} :: head}\"/>"
                + "<attr sel=\"#t\" th:text=\"${pageTitle}\" "
                + "th:attr=\"data-entry=${urls['blog:my-post'] != null ? urls['blog:my-post'] : ''}\"/></thlogic>");
        write(templates.resolve("about.meta.yml"), "path: /about\nlocalizedPaths:\n  es: /acerca\n"
                + "locales: [en, es]\ntitle:\n  en: About\n  es: Acerca\n");
        // The virtual entry's body template (site-namespace so the fixture needs no plugin system): pulls the
        // tenant master's head and renders the ${content.*} payload.
        write(templates.resolve("entry.html"), "<!DOCTYPE html><html><head><title>E</title></head><body>"
                + "<header id=\"site-header\">h</header><main id=\"content\"><h1 id=\"t\">T</h1>"
                + "<div id=\"b\">B</div></main></body></html>");
        write(templates.resolve("entry.th.xml"), "<?xml version=\"1.0\"?><thlogic>"
                + "<attr sel=\"head\" th:replace=\"~{${master} :: head}\"/>"
                + "<attr sel=\"#t\" th:text=\"${content.title}\"/>"
                + "<attr sel=\"#b\" th:utext=\"${content.bodyHtml}\"/></thlogic>");
        return new Fixture(controller, blog);
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addPreferredLocale(EN); // deterministic Accept-Language, like the page ladder test
        return request;
    }

    // --- parity: every dispatch row, page vs virtual, identical outcomes --------------------------------

    @Test
    void everyDispatchRowBehavesIdenticallyForAPageAndAVirtualEntry(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp, List.of(new FixedSource("blog", List.of(entry()))));

        // Row: explicit non-default locale serves (200) — page and virtual, each via its LOCALIZED slug.
        assertEquals(200, f.controller().serve(get("/es/acerca")).getStatusCode().value());
        ResponseEntity<String> vEs = f.controller().serve(get("/es/blog/mi-entrada"));
        assertEquals(200, vEs.getStatusCode().value());
        assertTrue(vEs.getBody().contains("Mi entrada"), "es title rendered");
        assertTrue(vEs.getBody().contains("BODY-my-post-es"), "es body payload rendered");

        // Row: the DEFAULT locale prefixed is never canonical → 301 to the bare path.
        assertEquals(301, f.controller().serve(get("/en/about")).getStatusCode().value());
        ResponseEntity<String> v301 = f.controller().serve(get("/en/blog/my-post"));
        assertEquals(301, v301.getStatusCode().value());
        assertEquals("/blog/my-post", v301.getHeaders().getFirst("Location"));

        // Row: unprefixed default serves in place (the crawler-stable canonical).
        assertEquals(200, f.controller().serve(get("/about")).getStatusCode().value());
        assertEquals(200, f.controller().serve(get("/blog/my-post")).getStatusCode().value());

        // Row: a localized slug never doubles as another locale's URL (fail-closed both kinds).
        assertEquals(404, f.controller().serve(get("/acerca")).getStatusCode().value());
        assertEquals(404, f.controller().serve(get("/blog/mi-entrada")).getStatusCode().value());

        // Row: unprefixed + non-default preference cookie → 302 to the locale's own URL (localized slug).
        MockHttpServletRequest pCookie = get("/about");
        pCookie.setCookies(new Cookie("runway_lang_acme", "es"));
        ResponseEntity<String> p302 = f.controller().serve(pCookie);
        assertEquals(302, p302.getStatusCode().value());
        assertEquals("/es/acerca", p302.getHeaders().getFirst("Location"));
        MockHttpServletRequest vCookie = get("/blog/my-post");
        vCookie.setCookies(new Cookie("runway_lang_acme", "es"));
        ResponseEntity<String> v302 = f.controller().serve(vCookie);
        assertEquals(302, v302.getStatusCode().value());
        assertEquals("/es/blog/mi-entrada", v302.getHeaders().getFirst("Location"));

        // Row: the ?lang= switcher sets the preference cookie and redirects — identical shape for both.
        MockHttpServletRequest pSwitch = get("/about");
        pSwitch.setParameter("lang", "es");
        ResponseEntity<String> pSw = f.controller().serve(pSwitch);
        MockHttpServletRequest vSwitch = get("/blog/my-post");
        vSwitch.setParameter("lang", "es");
        ResponseEntity<String> vSw = f.controller().serve(vSwitch);
        assertEquals(302, pSw.getStatusCode().value());
        assertEquals(302, vSw.getStatusCode().value());
        assertEquals("/es/acerca", pSw.getHeaders().getFirst("Location"));
        assertEquals("/es/blog/mi-entrada", vSw.getHeaders().getFirst("Location"));
        assertTrue(pSw.getHeaders().getFirst("Set-Cookie").startsWith("runway_lang_acme=es"));
        assertEquals(pSw.getHeaders().getFirst("Set-Cookie"), vSw.getHeaders().getFirst("Set-Cookie"),
                "identical preference cookie — same ladder code");
    }

    @Test
    void aVirtualRenderStashesTheHeadContextWithTheCompositeIdentity(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp, List.of(new FixedSource("blog", List.of(entry()))));
        MockHttpServletRequest request = get("/blog/my-post");
        assertEquals(200, f.controller().serve(request).getStatusCode().value());
        Object head = request.getAttribute(HeadInjectionFilter.HEAD_CONTEXT_ATTRIBUTE);
        assertNotNull(head, "the head-injection context is stashed exactly like a page render's");
        PageMeta meta = ((mx.quest.runway.spi.model.HeadContext) head).page();
        assertEquals("blog:my-post", meta.slug(), "the composite <sourceKey>:<id> identity");
    }

    // --- collision policy -----------------------------------------------------------------------------------

    @Test
    void aRealPageAlwaysWinsOverAVirtualEntryOnTheSamePath(@TempDir Path tmp) throws IOException {
        ContentRef shadow = ContentRef.of("shadow", "/about", Map.of(), List.of(EN, ES),
                Map.of("en", "Shadow"), Map.of(), "__site__/acme/entry", null);
        Fixture f = wire(tmp, List.of(new FixedSource("blog", List.of(shadow))));
        ResponseEntity<String> response = f.controller().serve(get("/about"));
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("About"), "the PAGE's title renders — real page wins, structurally");
        assertTrue(!response.getBody().contains("Shadow"), "the virtual shadow never renders");
    }

    @Test
    void twoSourcesClaimingOnePathIsALoud500NamingBoth(@TempDir Path tmp) throws IOException {
        ContentRef a = ContentRef.of("a", "/clash", Map.of(), List.of(EN), Map.of("en", "A"), Map.of(),
                "__site__/acme/entry", null);
        ContentRef b = ContentRef.of("b", "/clash", Map.of(), List.of(EN), Map.of("en", "B"), Map.of(),
                "__site__/acme/entry", null);
        Fixture f = wire(tmp, List.of(new FixedSource("blog", List.of(a)), new FixedSource("shop", List.of(b))));
        assertEquals(500, f.controller().serve(get("/clash")).getStatusCode().value(),
                "a virtual-vs-virtual collision is a configuration conflict — loud, never silently arbitrated");
    }

    // --- fail-closed sanitization + failure containment ------------------------------------------------------

    @Test
    void anUnpublishedAnswerIsDroppedFailClosed(@TempDir Path tmp) throws IOException {
        ContentRef draft = new ContentRef("draft", "/blog/draft", Map.of(), List.of(EN), Map.of("en", "D"),
                Map.of(), null, "__site__/acme/entry", false, null, false, null, null, Map.of());
        // A misbehaving source that answers resolve() with an unpublished ref (the default resolve would skip it).
        ContentSource bad = new ContentSource() {
            @Override public String key() { return "bad"; }
            @Override public List<ContentRef> enumerate(SiteView site) { return List.of(draft); }
            @Override public ContentBody body(SiteView site, String id, Locale locale) { return ContentBody.empty(); }
            @Override public java.util.Optional<ContentRef> resolve(SiteView site, String path, Locale locale) {
                return "/blog/draft".equals(path) ? java.util.Optional.of(draft) : java.util.Optional.empty();
            }
        };
        Fixture f = wire(tmp, List.of(bad));
        assertEquals(404, f.controller().serve(get("/blog/draft")).getStatusCode().value(),
                "the kernel re-filters published fail-closed — omission, never disclosure");
    }

    @Test
    void aBodyTemplateOutsideTheAllowedNamespacesIsDropped(@TempDir Path tmp) throws IOException {
        ContentRef foreign = ContentRef.of("x", "/blog/x", Map.of(), List.of(EN), Map.of("en", "X"), Map.of(),
                "__site__/OTHER-TENANT/entry", null);
        Fixture f = wire(tmp, List.of(new FixedSource("blog", List.of(foreign))));
        assertEquals(404, f.controller().serve(get("/blog/x")).getStatusCode().value(),
                "a source can never point the renderer at another tenant's files");
    }

    @Test
    void aThrowingSourceIsAMissNeverA500(@TempDir Path tmp) throws IOException {
        ContentSource broken = new ContentSource() {
            @Override public String key() { return "broken"; }
            @Override public List<ContentRef> enumerate(SiteView site) { throw new IllegalStateException("boom"); }
            @Override public ContentBody body(SiteView site, String id, Locale locale) { return ContentBody.empty(); }
            @Override public java.util.Optional<ContentRef> resolve(SiteView site, String path, Locale locale) {
                throw new IllegalStateException("boom");
            }
        };
        // The broken source must neither 500 the site nor stop the healthy source from serving.
        Fixture f = wire(tmp, List.of(broken, new FixedSource("blog", List.of(entry()))));
        assertEquals(404, f.controller().serve(get("/nope")).getStatusCode().value(), "miss stays a 404");
        assertEquals(200, f.controller().serve(get("/blog/my-post")).getStatusCode().value(),
                "the healthy source still serves");
    }

    // --- the enumerate/body contract --------------------------------------------------------------------------

    @Test
    void aConditionalRevalidationNeverCallsBody(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp, List.of(new FixedSource("blog", List.of(entry()))));
        ResponseEntity<String> first = f.controller().serve(get("/blog/my-post"));
        assertEquals(200, first.getStatusCode().value());
        assertEquals(1, f.blog().bodyCalls.get(), "the 200 render fetched the body once");

        MockHttpServletRequest revalidate = get("/blog/my-post");
        revalidate.addHeader("If-None-Match", first.getHeaders().getETag());
        assertEquals(304, f.controller().serve(revalidate).getStatusCode().value());
        assertEquals(1, f.blog().bodyCalls.get(), "the 304 path never called body() — metadata-only");
    }

    @Test
    void aPageCanLinkAnEntryByCompositeIdThroughTheUrlMap(@TempDir Path tmp) throws IOException {
        // ${urls['blog:my-post']} in the page's decoupled logic resolves to the entry's locale-correct URL.
        Fixture f = wire(tmp, List.of(new FixedSource("blog", List.of(entry()))));
        ResponseEntity<String> en = f.controller().serve(get("/about"));
        assertTrue(en.getBody().contains("data-entry=\"/blog/my-post\""),
                "the urls map carries <sourceKey>:<id> keys for source content");
        ResponseEntity<String> es = f.controller().serve(get("/es/acerca"));
        assertTrue(es.getBody().contains("data-entry=\"/es/blog/mi-entrada\""),
                "locale-correct: the es render links the es slug under the es prefix");
    }

    @Test
    void withNoVirtualSourcesAMissIsExactlyTodays404(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp, List.of());
        assertEquals(404, f.controller().serve(get("/blog/my-post")).getStatusCode().value());
    }

    // --- the remaining dispatch rows --------------------------------

    @Test
    void aNoPreferenceCrawlerRequestServesTheBareUrlInPlaceForBothKinds(@TempDir Path tmp) throws IOException {
        // NO Accept-Language header at all — the crawler guard must serve the canonical in place, never 302.
        Fixture f = wire(tmp, List.of(new FixedSource("blog", List.of(entry()))));
        assertEquals(200, f.controller().serve(new MockHttpServletRequest("GET", "/about"))
                .getStatusCode().value(), "page: crawler stays on the canonical");
        assertEquals(200, f.controller().serve(new MockHttpServletRequest("GET", "/blog/my-post"))
                .getStatusCode().value(), "virtual: crawler stays on the canonical");
    }

    @Test
    void anEsPreferringBrowserIs302edToTheEsUrlForBothKinds(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp, List.of(new FixedSource("blog", List.of(entry()))));
        MockHttpServletRequest p = new MockHttpServletRequest("GET", "/about");
        p.addPreferredLocale(ES);
        ResponseEntity<String> pr = f.controller().serve(p);
        MockHttpServletRequest v = new MockHttpServletRequest("GET", "/blog/my-post");
        v.addPreferredLocale(ES);
        ResponseEntity<String> vr = f.controller().serve(v);
        assertEquals(302, pr.getStatusCode().value());
        assertEquals("/es/acerca", pr.getHeaders().getFirst("Location"));
        assertEquals(302, vr.getStatusCode().value());
        assertEquals("/es/blog/mi-entrada", vr.getHeaders().getFirst("Location"), "Accept-Language negotiation");
    }

    @Test
    void aForeignPrefixOnAnUnavailableLocaleIs404ForBothKinds(@TempDir Path tmp) throws IOException {
        ContentRef enOnly = ContentRef.of("en-only", "/blog/en-only", Map.of(), List.of(EN),
                Map.of("en", "EN only"), Map.of(), "__site__/acme/entry", null);
        Fixture f = wire(tmp, List.of(new FixedSource("blog", List.of(enOnly))));
        assertEquals(404, f.controller().serve(get("/es/blog/en-only")).getStatusCode().value(),
                "an entry not published in the prefixed locale is a 404, exactly like a page");
    }

    @Test
    void switcherToAnUnavailableLocaleFallsBackToTheDefaultForBothKinds(@TempDir Path tmp) throws IOException {
        ContentRef enOnly = ContentRef.of("en-only", "/blog/en-only", Map.of(), List.of(EN),
                Map.of("en", "EN only"), Map.of(), "__site__/acme/entry", null);
        Fixture f = wire(tmp, List.of(new FixedSource("blog", List.of(enOnly))));
        MockHttpServletRequest v = get("/blog/en-only");
        v.setParameter("lang", "es"); // supported by the site but not by this entry → target the site default
        ResponseEntity<String> vr = f.controller().serve(v);
        assertEquals(302, vr.getStatusCode().value());
        assertEquals("/blog/en-only", vr.getHeaders().getFirst("Location"), "falls back to the default's URL");
        assertTrue(vr.getHeaders().getFirst("Set-Cookie").startsWith("runway_lang_acme=en"));
    }

    @Test
    void anEmptyLocalesEntryIsSiteDefaultOnly(@TempDir Path tmp) throws IOException {
        ContentRef defaultOnly = ContentRef.of("plain", "/blog/plain", Map.of(), List.of(),
                Map.of("en", "Plain"), Map.of(), "__site__/acme/entry", null);
        Fixture f = wire(tmp, List.of(new FixedSource("blog", List.of(defaultOnly))));
        assertEquals(200, f.controller().serve(get("/blog/plain")).getStatusCode().value());
        assertEquals(404, f.controller().serve(get("/es/blog/plain")).getStatusCode().value(),
                "empty locales = the page convention: site default only");
    }

    // --- the remaining sanitize/degrade branches -------------------------------------------

    @Test
    void aSourceAnsweringForADifferentPathIsDropped(@TempDir Path tmp) throws IOException {
        ContentRef elsewhere = entry(); // pathFor(en) = /blog/my-post — NOT the requested path
        ContentSource lying = new ContentSource() {
            @Override public String key() { return "lying"; }
            @Override public List<ContentRef> enumerate(SiteView site) { return List.of(elsewhere); }
            @Override public ContentBody body(SiteView site, String id, Locale locale) { return ContentBody.empty(); }
            @Override public java.util.Optional<ContentRef> resolve(SiteView site, String path, Locale locale) {
                return java.util.Optional.of(elsewhere); // answers EVERY path with the same ref
            }
        };
        Fixture f = wire(tmp, List.of(lying));
        assertEquals(404, f.controller().serve(get("/somewhere-else")).getStatusCode().value(),
                "the path-echo re-check drops an answer that does not actually serve the requested path");
        assertEquals(200, f.controller().serve(get("/blog/my-post")).getStatusCode().value(),
                "the honest path still serves");
    }

    @Test
    void aBodyFailureRenderIsServedUncacheablyWithNoValidator(@TempDir Path tmp) throws IOException {
        ContentSource failing = new ContentSource() {
            @Override public String key() { return "blog"; }
            @Override public List<ContentRef> enumerate(SiteView site) { return List.of(entry()); }
            @Override public ContentBody body(SiteView site, String id, Locale locale) {
                throw new IllegalStateException("transient IO failure");
            }
        };
        Fixture f = wire(tmp, List.of(failing));
        ResponseEntity<String> response = f.controller().serve(get("/blog/my-post"));
        assertEquals(200, response.getStatusCode().value(), "chrome still renders — degrade, not a 500");
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"),
                "the degraded render must never be cacheable");
        assertEquals(null, response.getHeaders().getETag(),
                "no validator — a healthy render must never 304-pin the degraded output");
        assertTrue(response.getBody().contains("My post"), "metadata (title) still renders");
    }
}
