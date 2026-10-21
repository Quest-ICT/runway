package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import mx.quest.runway.content.ContentCatalog;
import mx.quest.runway.content.ContentRouter;
import mx.quest.runway.content.ContentSourceRegistry;
import mx.quest.runway.crypto.AesGcmSecretCipher;
import mx.quest.runway.i18n.I18nProperties;
import mx.quest.runway.i18n.SiteAwareMessageSource;
import mx.quest.runway.page.FragmentTemplates;
import mx.quest.runway.page.LoadedSiteInputs;
import mx.quest.runway.page.PageRouting;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageValidatorService;
import mx.quest.runway.page.RenderModelBuilder;
import mx.quest.runway.platform.TestPlatformSettings;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.security.RunwaySecurityProperties;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.site.SiteParamsService;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.model.WidgetView;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.widget.ContentWidgetRegistry;
import mx.quest.runway.widget.WidgetInstanceConfigService;
import mx.quest.runway.widget.WidgetModelBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.thymeleaf.cache.TemplateCacheKey;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * The ETag invariant, checked for further render inputs:
 * <b>a public page's ETag describes exactly the body served under it</b>. Every test changes one input the way an
 * operator or site manager does (a file on disk, a console save), then revalidates with the ETag it held: it must get
 * a {@code 200} with the new body, never a {@code 304} that pins the old one.
 * <ul>
 *   <li>chrome fragments ({@code _nav}, any non-page template the master or a page pulls in);</li>
 *   <li>site template parameters, widget-instance config, the cross-page URL map (pages and content sources);</li>
 *   <li>a render whose output depends on the query <em>and</em> a widget (the search box on {@code ?q=}), whose
 *       data the kernel cannot see: served without validators, never {@code 304};</li>
 *   <li>no {@code Last-Modified} on a page, so an {@code If-Modified-Since}-only client never gets a {@code 304}
 *       across a change the ETag sees;</li>
 *   <li>a fragment save evicts that site's parsed templates only.</li>
 * </ul>
 * Every test runs the real render path over a cache-ON engine.
 */
class RenderInputsEtagTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final FileTime AN_HOUR_AGO = FileTime.from(Instant.now().minusSeconds(3600));
    private static final List<PluginSettingDef> ECHO_SCHEMA =
            List.of(new PluginSettingDef("label", "string", null, "Label", null));

    /** A query-aware widget backed by data the kernel cannot see (a search index): echoes config, query and data. */
    private static final class EchoWidget implements ContentWidget {
        final AtomicReference<String> corpus = new AtomicReference<>("hits-1");

        @Override public String key() {
            return "echo";
        }

        @Override public RenderFragment render(WidgetView v) {
            return RenderFragment.of("<span class=\"w\">cfg=" + v.config().getOrDefault("label", "none")
                    + " q=" + v.query().getOrDefault("q", "-") + " data=" + corpus.get() + "</span>");
        }
    }

    /** A content source whose enumeration (and so the URL map) the test can change. */
    private static final class Posts implements ContentSource {
        volatile List<ContentRef> refs = List.of(post("p1", "/posts/one"));

        static ContentRef post(String id, String path) {
            return new ContentRef(id, path, Map.of(), List.of(EN), Map.of("en", "Post " + id), Map.of(), "",
                    "__site__/acme/post", true, Instant.parse("2026-01-01T00:00:00Z"), false, null, null,
                    Map.of("master", "_vmaster"));
        }

        @Override public String key() {
            return "posts";
        }

        @Override public List<ContentRef> enumerate(SiteView site) {
            return "acme".equals(site.id()) ? refs : List.of();
        }

        @Override public ContentBody body(SiteView site, String id, Locale locale) {
            return new ContentBody("<p>post body " + id + "</p>", Map.of());
        }
    }

    private record Rig(PageController controller, StorageLayout layout, SiteResolver sites, SiteStore siteStore,
                       SpringTemplateEngine engine, FragmentTemplates fragments, SiteParamsService params,
                       WidgetInstanceConfigService widgetConfig, EchoWidget echo, Posts posts,
                       mx.quest.runway.site.SiteContactService contact) {

        Path templates(String siteId) {
            return layout.siteDir(siteId).resolve("templates");
        }

        ResponseEntity<String> serve(String siteId, String uri, String query, String ifNoneMatch, Long ifModifiedSince) {
            sites.clear();
            sites.bind(siteStore.find(siteId).orElseThrow());
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            if (query != null) {
                request.setQueryString(query);
                for (String pair : query.split("&")) {
                    String[] kv = pair.split("=", 2);
                    request.addParameter(kv[0], kv.length > 1 ? kv[1] : "");
                }
            }
            request.addPreferredLocale(EN);
            if (ifNoneMatch != null) {
                request.addHeader(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
            }
            if (ifModifiedSince != null) {
                request.addHeader(HttpHeaders.IF_MODIFIED_SINCE, ifModifiedSince);
            }
            return controller.serve(request);
        }

        ResponseEntity<String> ok(String siteId, String uri) {
            ResponseEntity<String> response = serve(siteId, uri, null, null, null);
            assertEquals(200, response.getStatusCode().value(), () -> "body: " + response.getBody());
            return response;
        }

        /** Revalidates with the ETag the client holds; the answer must be a fresh 200 carrying {@code newText}. */
        ResponseEntity<String> revalidatesToNew(String siteId, String uri, ResponseEntity<String> held,
                                               String oldText, String newText) {
            String etag = held.getHeaders().getETag();
            assertNotNull(etag, "the held response carried an ETag");
            ResponseEntity<String> after = serve(siteId, uri, null, etag, null);
            assertEquals(200, after.getStatusCode().value(),
                    "the change must move the ETag: the old one may not revalidate to 304 (stale body pinned)");
            assertNotEquals(etag, after.getHeaders().getETag());
            assertTrue(after.getBody().contains(newText), () -> "new body expected (" + newText + "): " + after.getBody());
            assertFalse(after.getBody().contains(oldText), after.getBody());
            assertEquals(304, serve(siteId, uri, null, after.getHeaders().getETag(), null).getStatusCode().value(),
                    "and the new pair revalidates");
            return after;
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void settle(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                if (Files.isRegularFile(f)) {
                    Files.setLastModifiedTime(f, AN_HOUR_AGO);
                }
            }
        }
        Files.setLastModifiedTime(dir, AN_HOUR_AGO);
    }

    private static Rig rig(Path tmp, String... siteIds) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore siteStore = new SiteStore(layout, yaml);
        SiteResolver siteResolver = new SiteResolver(siteStore, TestPlatformSettings.empty());
        SiteAwareMessageSource messages = new SiteAwareMessageSource(siteResolver, layout, new I18nProperties());

        GenericApplicationContext appContext = new GenericApplicationContext();
        appContext.refresh();
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new SiteTemplateResolver(layout));
        engine.setDecoupledTemplateLogicResolver(new WidgetAwareDecoupledLogicResolver());
        engine.setTemplateEngineMessageSource(messages);
        TemplateCacheService templateCache = new TemplateCacheService(engine);

        SiteParamsService params = new SiteParamsService(layout, yaml);
        WidgetInstanceConfigService widgetConfig = new WidgetInstanceConfigService(layout,
                new AesGcmSecretCipher(new RunwaySecurityProperties()));
        PageStore pageStore = new PageStore(layout, yaml);
        EchoWidget echo = new EchoWidget();
        Posts posts = new Posts();
        ContentSourceRegistry sources = new ContentSourceRegistry(List.of(posts));
        ContentCatalog catalog = new ContentCatalog(sources);

        mx.quest.runway.site.SiteContactService contact = new mx.quest.runway.site.SiteContactService(layout, yaml);
        LoadedSiteInputs loaded = new LoadedSiteInputs(messages, null, null, null, null, null, null,
                params, widgetConfig, pageStore, catalog, contact);
        PageRenderer renderer = new PageRenderer(engine);
        PageController controller = new PageController(siteResolver, new PageRouting(pageStore),
                new ContentRouter(sources), catalog, new RenderModelBuilder(null, null, null, params, contact),
                new WidgetModelBuilder(new ContentWidgetRegistry(List.of(echo)), layout, widgetConfig),
                new PageValidatorService(layout, null, null, null, null, loaded), renderer,
                new ErrorPages(layout, renderer), new RunwayHttpCacheProperties());

        for (String siteId : siteIds) {
            siteStore.save(new Site(siteId, "Name-" + siteId, List.of(siteId + ".test"), EN, List.of(EN),
                    "https://" + siteId + ".test", SiteStatus.ACTIVE));
            Path t = layout.siteDir(siteId).resolve("templates");
            String chrome = "<header id=\"site-header\"><nav id=\"nav\">seed</nav>"
                    + "<p id=\"phone\">p</p><p id=\"cmail\">c</p><a id=\"about-link\" href=\"#\">a</a><a id=\"post-link\" href=\"#\">p</a>"
                    + "</header>";
            String chromeLogic = "<attr sel=\"#nav\" th:replace=\"~{${#strings.replace(master,'_master','_nav')} :: #nav}\"/>"
                    + "<attr sel=\"#phone\" th:text=\"${params['phone']}\"/>"
                    + "<attr sel=\"#cmail\" th:text=\"'mail=' + ${contact.email}\"/>"
                    + "<attr sel=\"#about-link\" th:attr=\"href=${urls['about']}\"/>"
                    + "<attr sel=\"#post-link\" th:attr=\"href=${urls['posts:p1']}\"/>";
            write(t.resolve("_master.html"), "<!DOCTYPE html><html><head><title>M</title></head><body>" + chrome
                    + "<main id=\"content\">m</main></body></html>");
            write(t.resolve("_master.th.xml"), "<?xml version=\"1.0\"?><thlogic>" + chromeLogic + "</thlogic>");
            // The virtual-content master carries a CHROME widget anchor (a header search box).
            write(t.resolve("_vmaster.html"), "<!DOCTYPE html><html><head><title>V</title></head><body>"
                    + "<header id=\"site-header\"><div id=\"vbox\" data-widget=\"echo/vbox\">PH</div></header>"
                    + "<main id=\"content\">m</main></body></html>");
            write(t.resolve("_nav.html"), "<nav id=\"nav\">NAV-1</nav>");
            write(t.resolve("post.html"), "<!DOCTYPE html><html><head><title>P</title></head><body>"
                    + "<header id=\"site-header\"></header><main id=\"content\"><div id=\"pb\">b</div></main></body></html>");
            write(t.resolve("post.th.xml"), "<?xml version=\"1.0\"?><thlogic>"
                    + "<attr sel=\"#site-header\" th:replace=\"~{${master} :: #site-header}\"/>"
                    + "<attr sel=\"#pb\" th:utext=\"${content.bodyHtml}\"/></thlogic>");
            String pageLogic = "<?xml version=\"1.0\"?><thlogic>"
                    + "<attr sel=\"#site-header\" th:replace=\"~{${master} :: #site-header}\"/></thlogic>";
            write(t.resolve("home.html"), "<!DOCTYPE html><html><head><title>H</title></head><body>"
                    + "<header id=\"site-header\"></header><main id=\"content\">"
                    + "<div id=\"box\" data-widget=\"echo/main\">PH</div></main></body></html>");
            write(t.resolve("home.th.xml"), pageLogic);
            write(t.resolve("home.meta.yml"), "path: /\nlocales: [en]\ntitle:\n  en: Home\n");
            write(t.resolve("about.html"), "<!DOCTYPE html><html><head><title>A</title></head><body>"
                    + "<header id=\"site-header\"></header><main id=\"content\">about</main></body></html>");
            write(t.resolve("about.th.xml"), pageLogic);
            write(t.resolve("about.meta.yml"), "path: /about\nlocales: [en]\ntitle:\n  en: About\n");
            settle(t);
            write(layout.siteDir(siteId).resolve("params.yml"), "phone: 555-0001\n");
        }
        return new Rig(controller, layout, siteResolver, siteStore, engine,
                new FragmentTemplates(layout, templateCache), params, widgetConfig, echo, posts, contact);
    }

    // ---- chrome fragments ------------------------------------------------------------------------------------

    @Test
    void aFragmentEditedOnDiskMovesTheEtagOfEveryPageThatPullsIt(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme", "beta");
        ResponseEntity<String> home = rig.ok("acme", "/");
        ResponseEntity<String> about = rig.ok("acme", "/about");
        ResponseEntity<String> beta = rig.ok("beta", "/about");
        assertTrue(home.getBody().contains("NAV-1"), home.getBody());

        write(rig.templates("acme").resolve("_nav.html"), "<nav id=\"nav\">NAV-2</nav>"); // same size, on disk

        rig.revalidatesToNew("acme", "/", home, "NAV-1", "NAV-2");
        rig.revalidatesToNew("acme", "/about", about, "NAV-1", "NAV-2");
        assertEquals(304, rig.serve("beta", "/about", null, beta.getHeaders().getETag(), null).getStatusCode().value(),
                "another site's pages keep their ETag");
    }

    @Test
    void aFragmentSavedThroughTheConsoleMovesTheEtag(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> about = rig.ok("acme", "/about");

        rig.fragments().save("acme", "_nav", "<nav id=\"nav\">NAV-SAVED</nav>", null);

        rig.revalidatesToNew("acme", "/about", about, "NAV-1", "NAV-SAVED");
    }

    @Test
    void aNonPageTemplateAddedOrChangedMovesTheEtagButAnotherPagesFileDoesNot(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> about = rig.ok("acme", "/about");

        // Another PAGE's template is not an input of /about: its edit leaves the ETag (and the body) alone.
        write(rig.templates("acme").resolve("home.html"), "<!DOCTYPE html><html><head><title>H</title></head>"
                + "<body><header id=\"site-header\"></header><main id=\"content\">changed</main></body></html>");
        assertEquals(304, rig.serve("acme", "/about", null, about.getHeaders().getETag(), null)
                .getStatusCode().value());

        // A new non-page template (a fragment a master could start pulling) is an input: the ETag moves.
        write(rig.templates("acme").resolve("_footer.html"), "<footer>f</footer>");
        assertEquals(200, rig.serve("acme", "/about", null, about.getHeaders().getETag(), null)
                .getStatusCode().value());
    }

    // ---- a fragment save evicts one site ---------------------------------------------------------------------

    @Test
    void aFragmentSaveEvictsOnlyThatSitesParsedTemplates(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme", "beta");
        rig.ok("acme", "/about");
        rig.ok("beta", "/about");
        assertTrue(cached(rig, "beta") > 0 && cached(rig, "acme") > 0, "both sites' parses are cached");
        int betaBefore = cached(rig, "beta");

        rig.fragments().save("acme", "_nav", "<nav id=\"nav\">NAV-X</nav>", null);

        assertEquals(0, cached(rig, "acme"), "the saved site's parses are evicted");
        assertEquals(betaBefore, cached(rig, "beta"), "another site's warm cache is untouched");

        rig.ok("beta", "/about");
        rig.fragments().delete("acme", "_nav");
        assertEquals(betaBefore, cached(rig, "beta"), "a delete is per site too");
        rig.fragments().reload("acme", "_nav");
        assertEquals(betaBefore, cached(rig, "beta"), "and a reload");
    }

    private static int cached(Rig rig, String siteId) {
        String prefix = SiteTemplateResolver.PREFIX + siteId + "/";
        int n = 0;
        for (TemplateCacheKey key : rig.engine().getCacheManager().getTemplateCache().keySet()) {
            if (key.getTemplate().startsWith(prefix)
                    || (key.getOwnerTemplate() != null && key.getOwnerTemplate().startsWith(prefix))) {
                n++;
            }
        }
        return n;
    }

    // ---- params, widget-instance config, the URL map ---------------------------------------------------------

    @Test
    void aSiteParamsSaveMovesTheEtagWithTheBody(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme", "beta");
        ResponseEntity<String> about = rig.ok("acme", "/about");
        ResponseEntity<String> beta = rig.ok("beta", "/about");
        assertTrue(about.getBody().contains("555-0001"), about.getBody());

        rig.params().save("acme", Map.of("phone", "555-0100"));

        rig.revalidatesToNew("acme", "/about", about, "555-0001", "555-0100");
        assertEquals(304, rig.serve("beta", "/about", null, beta.getHeaders().getETag(), null).getStatusCode().value());
    }

    @Test
    void theSiteContactReachesTemplatesAndASaveMovesTheEtagWithTheBody(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme", "beta");
        ResponseEntity<String> about = rig.ok("acme", "/about");
        ResponseEntity<String> beta = rig.ok("beta", "/about");
        assertTrue(about.getBody().contains("mail=null"),
                "no contact yet: " + about.getBody());

        rig.contact().save("acme", new mx.quest.runway.site.SiteContact("hello@acme.test", null, null));

        rig.revalidatesToNew("acme", "/about", about, "mail=null", "mail=hello@acme.test");
        assertEquals(304, rig.serve("beta", "/about", null, beta.getHeaders().getETag(), null).getStatusCode().value(),
                "another site's contact never moves this one");
    }

    @Test
    void aParamsFileHandEditAppliesAtTheReloadWithItsEtag(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> about = rig.ok("acme", "/about");

        write(rig.layout().siteDir("acme").resolve("params.yml"), "phone: 555-0999\n");
        assertEquals(304, rig.serve("acme", "/about", null, about.getHeaders().getETag(), null)
                .getStatusCode().value(), "not applied yet: the loaded params are still what is rendered");
        assertTrue(rig.ok("acme", "/about").getBody().contains("555-0001"));

        rig.params().onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.UPDATED)); // the reload
        rig.revalidatesToNew("acme", "/about", about, "555-0001", "555-0999");
    }

    @Test
    void aWidgetInstanceSaveMovesTheEtagWithTheBody(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme", "beta");
        ResponseEntity<String> home = rig.ok("acme", "/");
        ResponseEntity<String> beta = rig.ok("beta", "/");
        assertTrue(home.getBody().contains("cfg=none"), home.getBody());

        rig.widgetConfig().save("acme", "echo", "main", ECHO_SCHEMA, Map.of("label", "Campaign"));

        rig.revalidatesToNew("acme", "/", home, "cfg=none", "cfg=Campaign");
        assertEquals(304, rig.serve("beta", "/", null, beta.getHeaders().getETag(), null).getStatusCode().value());
    }

    @Test
    void aWidgetInstancesHandEditAppliesAtTheReloadWithItsEtag(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> home = rig.ok("acme", "/");

        write(rig.layout().widgetInstancesFile("acme"), "echo/main:\n  label: ByHand\n");
        assertEquals(304, rig.serve("acme", "/", null, home.getHeaders().getETag(), null).getStatusCode().value(),
                "not applied yet");

        rig.widgetConfig().onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.UPDATED)); // the reload
        rig.revalidatesToNew("acme", "/", home, "cfg=none", "cfg=ByHand");
    }

    @Test
    void anotherPagesPathChangeMovesTheEtagOfAPageLinkingToIt(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> home = rig.ok("acme", "/");
        assertTrue(home.getBody().contains("href=\"/about\""), home.getBody());

        write(rig.templates("acme").resolve("about.meta.yml"), "path: /about-us\nlocales: [en]\ntitle:\n  en: About\n");

        rig.revalidatesToNew("acme", "/", home, "href=\"/about\"", "href=\"/about-us\"");
    }

    @Test
    void aContentSourcePathChangeMovesTheEtagOfAPageLinkingToIt(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> about = rig.ok("acme", "/about");
        assertTrue(about.getBody().contains("href=\"/posts/one\""), about.getBody());

        rig.posts().refs = List.of(Posts.post("p1", "/posts/first"));

        rig.revalidatesToNew("acme", "/about", about, "href=\"/posts/one\"", "href=\"/posts/first\"");
    }

    // ---- query + widget renders carry no validator -----------------------------------------------------------

    @Test
    void aQueryRenderOfAWidgetPageIsNeverRevalidatedAgainstDataTheKernelCannotSee(@TempDir Path tmp)
            throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> first = rig.serve("acme", "/", "q=zebra", null, null);
        assertEquals(200, first.getStatusCode().value());
        assertTrue(first.getBody().contains("q=zebra data=hits-1"), first.getBody());

        rig.echo().corpus.set("hits-2"); // the index moved (another page edited, a reindex) — nothing the kernel folds

        String held = first.getHeaders().getETag();
        ResponseEntity<String> again = rig.serve("acme", "/", "q=zebra", held == null ? "*" : held, null);
        assertEquals(200, again.getStatusCode().value(), "a query+widget render must never answer 304");
        assertTrue(again.getBody().contains("data=hits-2"), again.getBody());
        assertNull(again.getHeaders().getETag(), "no validator a cache could revalidate against");
        assertEquals("private, no-cache", again.getHeaders().getCacheControl(), "and no shared caching");
        assertEquals(-1, again.getHeaders().getLastModified());
    }

    @Test
    void aQueryOnAPageWithoutWidgetsKeepsItsValidators(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> withQuery = rig.serve("acme", "/about", "utm_source=x", null, null);
        assertEquals(200, withQuery.getStatusCode().value());
        assertNotNull(withQuery.getHeaders().getETag(), "no widget can read the query: cacheable as before");
        assertTrue(withQuery.getHeaders().getCacheControl().contains("max-age=300"));
        assertEquals(304, rig.serve("acme", "/about", "utm_source=x", withQuery.getHeaders().getETag(), null)
                .getStatusCode().value());

        ResponseEntity<String> plain = rig.ok("acme", "/");
        assertNotNull(plain.getHeaders().getETag(), "the widget page without a query stays cacheable");
        assertTrue(plain.getHeaders().getCacheControl().contains("max-age=300"));
    }

    @Test
    void aQueryRenderOfVirtualContentWithAChromeWidgetCarriesNoValidator(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> plain = rig.ok("acme", "/posts/one");
        assertTrue(plain.getBody().contains("post body p1"), plain.getBody());
        assertNotNull(plain.getHeaders().getETag());

        ResponseEntity<String> first = rig.serve("acme", "/posts/one", "q=x", null, null);
        rig.echo().corpus.set("hits-3");
        String held = first.getHeaders().getETag();
        ResponseEntity<String> again = rig.serve("acme", "/posts/one", "q=x", held == null ? "*" : held, null);
        assertEquals(200, again.getStatusCode().value());
        assertTrue(again.getBody().contains("data=hits-3"), again.getBody());
        assertNull(again.getHeaders().getETag());
        assertEquals("private, no-cache", again.getHeaders().getCacheControl());
    }

    // ---- no Last-Modified, so If-Modified-Since never vouches for a changed page -----------------------------

    @Test
    void anIfModifiedSinceOnlyClientIsNeverToldUnchangedAcrossAChange(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> about = rig.ok("acme", "/about");

        rig.params().save("acme", Map.of("phone", "555-0200")); // moves the ETag, touches no template file

        long farFuture = Instant.now().plusSeconds(86_400).toEpochMilli();
        ResponseEntity<String> dated = rig.serve("acme", "/about", null, null, farFuture);
        assertEquals(200, dated.getStatusCode().value(), "If-Modified-Since alone never answers 304 for a page");
        assertTrue(dated.getBody().contains("555-0200"), dated.getBody());
        assertEquals(-1, about.getHeaders().getLastModified(), "a page carries no Last-Modified (the ETag decides)");
        assertEquals(-1, dated.getHeaders().getLastModified());
        ResponseEntity<String> notModified = rig.serve("acme", "/about", null, dated.getHeaders().getETag(), null);
        assertEquals(304, notModified.getStatusCode().value());
        assertEquals(-1, notModified.getHeaders().getLastModified(), "nor does a 304");
    }
}
