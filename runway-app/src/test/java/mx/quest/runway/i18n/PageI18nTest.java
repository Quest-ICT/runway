package mx.quest.runway.i18n;
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
import mx.quest.runway.web.ErrorPages;
import mx.quest.runway.web.PageController;
import mx.quest.runway.web.PageRenderer;
import mx.quest.runway.web.SiteTemplateResolver;
import mx.quest.runway.widget.ContentWidgetRegistry;
import mx.quest.runway.widget.WidgetModelBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.thymeleaf.spring6.SpringTemplateEngine;

/** End-to-end: a page binds {@code #{}} keys resolved from the site's own bundle in the request locale. */
class PageI18nTest {

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
        I18nProperties i18n = new I18nProperties();
        i18n.setCacheSeconds(0);
        engine.setTemplateEngineMessageSource(new SiteAwareMessageSource(siteResolver, layout, i18n));

        PageController controller = new PageController(siteResolver,
                new PageRouting(new PageStore(layout, yaml)), new mx.quest.runway.content.ContentRouter(new mx.quest.runway.content.ContentSourceRegistry(java.util.List.of())), new mx.quest.runway.content.ContentCatalog(new mx.quest.runway.content.ContentSourceRegistry(java.util.List.of())), new RenderModelBuilder(),
                new WidgetModelBuilder(new ContentWidgetRegistry(List.of()), layout, new mx.quest.runway.widget.WidgetInstanceConfigService(layout, new mx.quest.runway.crypto.AesGcmSecretCipher(new mx.quest.runway.security.RunwaySecurityProperties()))),
                new PageValidatorService(layout),
                new PageRenderer(engine),
                new ErrorPages(layout, new PageRenderer(engine)), new mx.quest.runway.web.RunwayHttpCacheProperties());

        Site site = new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN, ES), "https://acme.test", SiteStatus.ACTIVE);
        siteStore.save(site);
        siteResolver.bind(site);

        Path templates = layout.siteDir("acme").resolve("templates");
        write(templates.resolve("_master.html"), """
                <!DOCTYPE html><html><head><title>M</title></head>
                <body><header id="site-header">h</header><main id="content">m</main></body></html>""");
        write(templates.resolve("_master.th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>");
        write(templates.resolve("about.html"), """
                <!DOCTYPE html><html><head><title>A</title></head>
                <body><header id="site-header">h</header><main id="content"><h1 id="t">PH</h1></main></body></html>""");
        write(templates.resolve("about.th.xml"), """
                <?xml version="1.0"?>
                <thlogic>
                  <attr sel="head" th:replace="~{${master} :: head}"/>
                  <attr sel="#site-header" th:replace="~{${master} :: #site-header}"/>
                  <attr sel="#t" th:text="#{about.title}"/>
                </thlogic>""");
        write(templates.resolve("about.meta.yml"), "path: /about\nlocales: [en, es]\n");

        // The site's own message bundle, per locale.
        write(layout.siteDir("acme").resolve("i18n").resolve("messages.properties"), "about.title=About Page\n");
        write(layout.siteDir("acme").resolve("i18n").resolve("messages_es.properties"), "about.title=Pagina Acerca\n");

        return controller;
    }

    @Test
    void resolvesMessageKeysFromTheSiteBundle(@TempDir Path tmp) throws IOException {
        PageController controller = wire(tmp);

        MockHttpServletRequest enRequest = new MockHttpServletRequest("GET", "/about");
        enRequest.addPreferredLocale(EN);
        ResponseEntity<String> english = controller.serve(enRequest);
        assertEquals(200, english.getStatusCode().value());
        assertTrue(english.getBody().contains("About Page"), () -> "en bundle key; got:\n" + english.getBody());

        ResponseEntity<String> spanish = controller.serve(new MockHttpServletRequest("GET", "/es/about"));
        assertEquals(200, spanish.getStatusCode().value());
        assertTrue(spanish.getBody().contains("Pagina Acerca"), () -> "es bundle key; got:\n" + spanish.getBody());
    }

    @Test
    void missingKeyRendersTheSignalNotAnAppString(@TempDir Path tmp) throws IOException {
        PageController controller = wire(tmp);
        // Overwrite the page logic to reference a key the bundle does not define.
        Path thxml = StorageLayout.from(propsFor(tmp)).siteDir("acme").resolve("templates").resolve("about.th.xml");
        write(thxml, """
                <?xml version="1.0"?>
                <thlogic>
                  <attr sel="head" th:replace="~{${master} :: head}"/>
                  <attr sel="#t" th:text="#{no.such.key}"/>
                </thlogic>""");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/about");
        request.addPreferredLocale(EN);
        String html = controller.serve(request).getBody();
        assertTrue(html.contains("??no.such.key"), () -> "missing key renders the ??key?? signal; got:\n" + html);
    }

    private static StorageProperties propsFor(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        return props;
    }
}
