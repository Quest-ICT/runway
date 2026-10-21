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
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.WidgetView;
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

/**
 * End-to-end content widgets: a designer declares a widget with a single {@code data-widget}
 * attribute (no Thymeleaf), and the kernel fills the anchor at render time via the
 * {@link WidgetAwareDecoupledLogicResolver} + {@link WidgetSupport} bean. Covers page widgets, chrome
 * widgets pulled from the master (proving {@code isChrome} and that the resolver runs on the master
 * too), soft-degrade for an unknown type and for an empty registry, preservation of the author's own
 * decoupled logic, and per-tenant isolation.
 */
class PageWidgetTest {

    private static final Locale EN = Locale.ENGLISH;

    /** Echoes its context so assertions can pin instance, site, and chrome flag exactly. */
    private static final class EchoWidget implements ContentWidget {
        @Override public String key() { return "hello"; }
        @Override public RenderFragment render(WidgetView v) {
            return RenderFragment.of("<p class=\"w\">hello " + v.instanceId()
                    + " @" + v.site().id() + " chrome=" + v.isChrome() + "</p>");
        }
    }

    private record Fixture(PageController controller, SiteResolver siteResolver) {}

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void seedSite(SiteStore siteStore, StorageLayout layout, String id, String host) throws IOException {
        siteStore.save(new Site(id, id, List.of(host), EN, List.of(EN), "https://" + host, SiteStatus.ACTIVE));
        Path t = layout.siteDir(id).resolve("templates");
        // Master carries a CHROME widget anchor inside the site-header seam.
        write(t.resolve("_master.html"), "<!DOCTYPE html><html><head><title>M</title></head><body>"
                + "<header id=\"site-header\"><div id=\"topbar\" data-widget=\"hello/topbar\">PHc</div></header>"
                + "<main id=\"content\">m</main></body></html>");
        write(t.resolve("_master.th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>");
        // Page carries a PAGE widget anchor and an anchor whose widget type is not installed.
        write(t.resolve("home.html"), "<!DOCTYPE html><html><head><title>H</title></head><body>"
                + "<header id=\"site-header\">x</header><main id=\"content\">"
                + "<div id=\"slot\" data-widget=\"hello/main\">PH</div>"
                + "<div id=\"missingslot\" data-widget=\"missing/x\">PH2</div></main></body></html>");
        // The author's own logic: pull the master's head + site-header. Must survive alongside generated rules.
        write(t.resolve("home.th.xml"), "<?xml version=\"1.0\"?><thlogic>"
                + "<attr sel=\"head\" th:replace=\"~{${master} :: head}\"/>"
                + "<attr sel=\"#site-header\" th:replace=\"~{${master} :: #site-header}\"/></thlogic>");
        write(t.resolve("home.meta.yml"), "path: /home\nlocales: [en]\n");
    }

    private static Fixture wire(Path tmp, ContentWidgetRegistry registry, String... hostsBySite) throws IOException {
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
        engine.setDecoupledTemplateLogicResolver(new WidgetAwareDecoupledLogicResolver());

        PageController controller = new PageController(siteResolver,
                new PageRouting(new PageStore(layout, yaml)), new mx.quest.runway.content.ContentRouter(new mx.quest.runway.content.ContentSourceRegistry(java.util.List.of())), new mx.quest.runway.content.ContentCatalog(new mx.quest.runway.content.ContentSourceRegistry(java.util.List.of())), new RenderModelBuilder(),
                new WidgetModelBuilder(registry, layout, new mx.quest.runway.widget.WidgetInstanceConfigService(layout, new mx.quest.runway.crypto.AesGcmSecretCipher(new mx.quest.runway.security.RunwaySecurityProperties()))),
                new PageValidatorService(layout),
                new PageRenderer(engine),
                new ErrorPages(layout, new PageRenderer(engine)), new mx.quest.runway.web.RunwayHttpCacheProperties());

        for (int i = 0; i + 1 < hostsBySite.length; i += 2) {
            seedSite(siteStore, layout, hostsBySite[i], hostsBySite[i + 1]);
        }
        return new Fixture(controller, siteResolver);
    }

    private static ResponseEntity<String> get(Fixture fixture, String host) {
        fixture.siteResolver().clear();
        fixture.siteResolver().bind(fixture.siteResolver().resolve(host).orElseThrow());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/home");
        request.addPreferredLocale(EN);
        return fixture.controller().serve(request);
    }

    @Test
    void fillsPageAndChromeWidgetsAndPreservesAuthoredLogic(@TempDir Path tmp) throws Exception {
        Fixture fixture = wire(tmp, new ContentWidgetRegistry(List.of(new EchoWidget())), "acme", "acme.test");

        String html = get(fixture, "acme.test").getBody();

        assertTrue(html.contains("hello main @acme chrome=false"), () -> "page widget filled; got:\n" + html);
        assertTrue(html.contains("hello topbar @acme chrome=true"), () -> "chrome widget (from master) filled; got:\n" + html);
        assertTrue(html.contains("<title>M</title>"), () -> "author's head-replace preserved; got:\n" + html);
        assertFalse(html.contains("PH<"), () -> "page placeholder replaced; got:\n" + html);
        assertFalse(html.contains("PH2"), () -> "unknown-widget placeholder cleared (soft-degrade); got:\n" + html);
        assertFalse(html.contains("PHc"), () -> "chrome placeholder replaced; got:\n" + html);
    }

    @Test
    void emptyRegistrySoftDegradesAllAnchors(@TempDir Path tmp) throws Exception {
        Fixture fixture = wire(tmp, new ContentWidgetRegistry(List.of()), "acme", "acme.test");

        ResponseEntity<String> response = get(fixture, "acme.test");
        String html = response.getBody();

        assertEquals(200, response.getStatusCode().value());
        // The literal data-widget="hello/…" attributes remain; what must be absent is rendered widget markup.
        assertFalse(html.contains("<p class=\"w\">"), () -> "no widget installed → nothing rendered; got:\n" + html);
        assertFalse(html.contains("PH2"), () -> "placeholders cleared even with no widgets; got:\n" + html);
        assertFalse(html.contains("PHc"), () -> "chrome placeholder cleared; got:\n" + html);
    }

    @Test
    void eachTenantRendersItsOwnWidgetContext(@TempDir Path tmp) throws Exception {
        Fixture fixture = wire(tmp, new ContentWidgetRegistry(List.of(new EchoWidget())),
                "acme", "acme.test", "other", "other.test");

        String acme = get(fixture, "acme.test").getBody();
        String other = get(fixture, "other.test").getBody();

        assertTrue(acme.contains("hello main @acme"), () -> acme);
        assertTrue(other.contains("hello main @other"), () -> other);
        assertFalse(other.contains("@acme"), "no cross-tenant bleed");
    }
}
