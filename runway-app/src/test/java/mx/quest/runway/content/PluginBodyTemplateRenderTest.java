package mx.quest.runway.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.page.PageRouting;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageValidator;
import mx.quest.runway.page.PageValidatorService;
import mx.quest.runway.page.RenderModelBuilder;
import mx.quest.runway.platform.TestPlatformSettings;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.RunningPlugins;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.ErrorPages;
import mx.quest.runway.web.PageController;
import mx.quest.runway.web.PageRenderer;
import mx.quest.runway.web.RunwayHttpCacheProperties;
import mx.quest.runway.web.SiteTemplateResolver;
import mx.quest.runway.console.PluginTemplateResolver;
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
 * The plugin-body-template acceptance gate plus the virtual-ETag fold contracts:
 * one source whose refs render through a <b>plugin-owned</b> ({@code __plugin__/blog/entry}) body template —
 * the tenant-INVARIANT render-root shape (the exact name-cache trap) — served under <b>two tenants</b> with the
 * Thymeleaf cache ON, each response carrying only its own tenant's chrome; and the {@code computeVirtual}
 * folds: a plugin i18n bundle edit, a plugin template edit, and a plugin version bump each change the ETag,
 * while equal refs built from differently-ordered maps produce the SAME ETag (canonical fingerprint — stable
 * across JVMs/nodes).
 */
class PluginBodyTemplateRenderTest {

    private static final Locale EN = Locale.ENGLISH;

    private record Fixture(PageController controller, PageValidatorService validator, SiteResolver siteResolver,
                           Site acme, Site globex, Path pluginDir, StorageLayout layout) {
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** A stub plugin install dir + RunningPlugins exposing it as loaded plugin 'blog' v{@code version}. */
    private static RunningPlugins runningPlugins(Path pluginDir, String version) {
        PluginDescriptor descriptor = new PluginDescriptor("blog", "Blog", version, null, null, null,
                null, null, null, null, null, null, null, null, null);
        LoadedPlugin plugin = new LoadedPlugin(descriptor, pluginDir);
        return () -> List.of(plugin);
    }

    private static ContentRef entry() {
        return ContentRef.of("my-post", "/blog/my-post", Map.of(), List.of(EN),
                Map.of("en", "My post"), Map.of(), "__plugin__/blog/entry", null);
    }

    private static Fixture wire(Path tmp) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp.resolve("external"));
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore siteStore = new SiteStore(layout, yaml);
        SiteResolver siteResolver = new SiteResolver(siteStore, TestPlatformSettings.empty());

        Path pluginDir = tmp.resolve("plugins").resolve("blog");
        write(pluginDir.resolve("templates").resolve("entry.html"),
                "<!DOCTYPE html><html><head><title>E</title></head><body><header id=\"site-header\">h</header>"
                        + "<main id=\"content\"><h1 id=\"t\">T</h1><span id=\"rm\">RM</span>"
                        + "<div id=\"b\">B</div></main></body></html>");
        write(pluginDir.resolve("templates").resolve("entry.th.xml"),
                "<?xml version=\"1.0\"?><thlogic>"
                        + "<attr sel=\"head\" th:replace=\"~{${master} :: head}\"/>"
                        + "<attr sel=\"#t\" th:text=\"${content.title}\"/>"
                        + "<attr sel=\"#rm\" th:text=\"#{blog.readmore}\"/>"
                        + "<attr sel=\"#b\" th:utext=\"${content.bodyHtml}\"/></thlogic>");
        write(pluginDir.resolve("i18n").resolve("messages.properties"), "blog.readmore=Read more\n");
        RunningPlugins running = runningPlugins(pluginDir, "1.0.0");

        GenericApplicationContext appContext = new GenericApplicationContext();
        appContext.refresh();
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.addTemplateResolver(new SiteTemplateResolver(layout));          // cache ON (resolver default)
        engine.addTemplateResolver(new PluginTemplateResolver(running));       // cache ON
        // The tenant-render #{} source with the SERVING plugin's bundles appended LAST: the
        // plugin adds keys; the site's own bundle is consulted first and wins any collision.
        org.springframework.beans.factory.ObjectProvider<mx.quest.runway.plugin.PluginRuntime> runtime =
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override public mx.quest.runway.plugin.PluginRuntime getObject() { return runtimeValue(); }
                    @Override public mx.quest.runway.plugin.PluginRuntime getIfAvailable() { return runtimeValue(); }
                    private mx.quest.runway.plugin.PluginRuntime runtimeValue() {
                        return new mx.quest.runway.plugin.PluginRuntime(running.installed(),
                                getClass().getClassLoader(), Map.of(), false);
                    }
                };
        mx.quest.runway.i18n.I18nProperties i18nProps = new mx.quest.runway.i18n.I18nProperties();
        i18nProps.setCacheSeconds(0); // re-check bundles on every access — the override test writes one mid-flight
        engine.setTemplateEngineMessageSource(new mx.quest.runway.i18n.SiteAwareMessageSource(
                siteResolver, layout, i18nProps, runtime));

        ContentSource blog = new ContentSource() {
            @Override public String key() { return "blog"; }
            @Override public List<ContentRef> enumerate(SiteView site) { return List.of(entry()); }
            @Override public ContentBody body(SiteView site, String id, Locale locale) {
                return ContentBody.of("<p>BODY</p>");
            }
        };

        PageValidatorService validator = new PageValidatorService(layout, running);
        PageController controller = new PageController(siteResolver,
                new PageRouting(new PageStore(layout, yaml)),
                new ContentRouter(new ContentSourceRegistry(List.of(blog))),
                new ContentCatalog(new ContentSourceRegistry(List.of(blog))),
                new RenderModelBuilder(),
                new WidgetModelBuilder(new ContentWidgetRegistry(List.of()), layout,
                        new WidgetInstanceConfigService(layout,
                                new mx.quest.runway.crypto.AesGcmSecretCipher(
                                        new mx.quest.runway.security.RunwaySecurityProperties()))),
                validator,
                new PageRenderer(engine),
                new ErrorPages(layout, new PageRenderer(engine)), new RunwayHttpCacheProperties());

        Site acme = new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN), null, SiteStatus.ACTIVE);
        Site globex = new Site("globex", "Globex", List.of("globex.test"), EN, List.of(EN), null, SiteStatus.ACTIVE);
        siteStore.save(acme);
        siteStore.save(globex);
        // Two tenants, DISTINCT masters — the bleed detector: each response must carry only its own chrome.
        for (Site site : List.of(acme, globex)) {
            write(layout.siteDir(site.id()).resolve("templates").resolve("_master.html"),
                    "<!DOCTYPE html><html><head><title>MASTER-" + site.id().toUpperCase(Locale.ROOT)
                            + "</title></head><body><header id=\"site-header\">h</header>"
                            + "<main id=\"content\">m</main></body></html>");
            write(layout.siteDir(site.id()).resolve("templates").resolve("_master.th.xml"),
                    "<?xml version=\"1.0\"?><thlogic></thlogic>");
        }
        return new Fixture(controller, validator, siteResolver, acme, globex, pluginDir, layout);
    }

    @Test
    void onePluginTemplateServesTwoTenantsWithNoChromeBleed_cacheOn(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);

        f.siteResolver().bind(f.acme());
        ResponseEntity<String> acme = f.controller().serve(new MockHttpServletRequest("GET", "/blog/my-post"));
        f.siteResolver().bind(f.globex());
        ResponseEntity<String> globex = f.controller().serve(new MockHttpServletRequest("GET", "/blog/my-post"));
        // And AGAIN under the first tenant — after the parsed template is warm in the cache.
        f.siteResolver().bind(f.acme());
        ResponseEntity<String> acmeAgain = f.controller().serve(new MockHttpServletRequest("GET", "/blog/my-post"));

        assertEquals(200, acme.getStatusCode().value());
        assertEquals(200, globex.getStatusCode().value());
        assertTrue(acme.getBody().contains("MASTER-ACME"), "acme render wears acme's chrome");
        assertTrue(!acme.getBody().contains("MASTER-GLOBEX"), "no foreign chrome");
        assertTrue(globex.getBody().contains("MASTER-GLOBEX"), "globex render wears globex's chrome");
        assertTrue(!globex.getBody().contains("MASTER-ACME"), "no bleed from the warm cache");
        assertTrue(acmeAgain.getBody().contains("MASTER-ACME"), "cache-warm re-render still tenant-correct");
        assertTrue(acme.getBody().contains("BODY"), "the plugin body payload rendered");
    }

    @Test
    void aPluginBodyTemplateResolvesItsOwnI18nKeysAndTheSiteOverridesThem(@TempDir Path tmp) throws IOException {
        // Decision 5 end-to-end: the virtual render resolves #{blog.readmore} from the SERVING plugin's own
        // bundle (appended last), and a site that defines the same key WINS (add-only — a plugin can never
        // override a tenant's string).
        Fixture f = wire(tmp);
        f.siteResolver().bind(f.acme());
        ResponseEntity<String> plain = f.controller().serve(new MockHttpServletRequest("GET", "/blog/my-post"));
        assertEquals(200, plain.getStatusCode().value());
        assertTrue(plain.getBody().contains("Read more"), "the plugin's own #{} key resolves on a virtual render");

        // The site defines the same key → the site's value wins on the next render.
        write(f.layout().siteDir("acme").resolve("i18n").resolve("messages.properties"),
                "blog.readmore=Keep exploring\n");
        ResponseEntity<String> overridden =
                f.controller().serve(new MockHttpServletRequest("GET", "/blog/my-post"));
        assertTrue(overridden.getBody().contains("Keep exploring"),
                "the tenant's own bundle is consulted first — the site overrides the plugin key");
        assertTrue(!overridden.getBody().contains("Read more"), "the plugin value no longer shows");
    }

    @Test
    void editingThePluginI18nBundleChangesTheVirtualETag(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        RoutableContent content = new RoutableContent("blog", entry());

        PageValidator before = f.validator().computeVirtual(f.acme(), content, EN, null);
        write(f.pluginDir().resolve("i18n").resolve("messages.properties"), "blog.readmore=Keep reading\n");
        PageValidator after = f.validator().computeVirtual(f.acme(), content, EN, null);

        assertNotEquals(before.etag(), after.etag(),
                "the serving plugin's i18n bundles are folded — an edit must move the ETag");
    }

    @Test
    void editingThePluginTemplateChangesTheVirtualETag(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        RoutableContent content = new RoutableContent("blog", entry());

        PageValidator before = f.validator().computeVirtual(f.acme(), content, EN, null);
        write(f.pluginDir().resolve("templates").resolve("entry.html"),
                "<!DOCTYPE html><html><head><title>E2</title></head><body><header id=\"site-header\">h</header>"
                        + "<main id=\"content\"><h1 id=\"t\">T</h1><div id=\"b\">B</div><footer>new</footer>"
                        + "</main></body></html>");
        PageValidator after = f.validator().computeVirtual(f.acme(), content, EN, null);

        assertNotEquals(before.etag(), after.etag(), "the body template's file content is folded");
    }

    @Test
    void aPluginUpgradeChangesTheVirtualETagEvenWithIdenticalFiles(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        RoutableContent content = new RoutableContent("blog", entry());

        PageValidator v1 = f.validator().computeVirtual(f.acme(), content, EN, null);
        PageValidatorService upgraded =
                new PageValidatorService(f.layout(), runningPlugins(f.pluginDir(), "1.1.0"));
        PageValidator v2 = upgraded.computeVirtual(f.acme(), content, EN, null);

        assertNotEquals(v1.etag(), v2.etag(),
                "a plugin upgrade that changes body-rendering CODE (not templates) must still move the ETag");
    }

    @Test
    void equalRefsFromDifferentlyOrderedMapsProduceTheSameETag(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        Map<String, String> ab = new LinkedHashMap<>();
        ab.put("a", "1");
        ab.put("b", "2");
        Map<String, String> ba = new LinkedHashMap<>();
        ba.put("b", "2");
        ba.put("a", "1");
        ContentRef refAb = new ContentRef("x", "/x", Map.of(), List.of(EN), Map.of("en", "X"), Map.of(), null,
                "__plugin__/blog/entry", true, null, false, null, null, ab);
        ContentRef refBa = new ContentRef("x", "/x", Map.of(), List.of(EN), Map.of("en", "X"), Map.of(), null,
                "__plugin__/blog/entry", true, null, false, null, null, ba);

        PageValidator a = f.validator().computeVirtual(f.acme(), new RoutableContent("blog", refAb), EN, null);
        PageValidator b = f.validator().computeVirtual(f.acme(), new RoutableContent("blog", refBa), EN, null);
        assertEquals(a.etag(), b.etag(),
                "the content fingerprint is CANONICAL (sorted map tokens) — stable across JVMs and nodes");
    }
}
