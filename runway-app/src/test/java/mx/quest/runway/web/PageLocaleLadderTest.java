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

/** The default-unprefixed locale ladder: explicit-locale serves, canonical redirects, the preference cookie. */
class PageLocaleLadderTest {

    private static final Locale EN = Locale.ENGLISH;

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static PageController wire(Path tmp) throws IOException {
        return wire(tmp, EN);
    }

    private static PageController wire(Path tmp, Locale defaultLocale) throws IOException {
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
                new PageValidatorService(layout),
                new PageRenderer(engine),
                new ErrorPages(layout, new PageRenderer(engine)), new mx.quest.runway.web.RunwayHttpCacheProperties());

        Site site = new Site("acme", "Acme", List.of("acme.test"), defaultLocale, List.of(EN, Locale.of("es")), "https://acme.test", SiteStatus.ACTIVE);
        siteStore.save(site);
        siteResolver.bind(site);

        Path templates = layout.siteDir("acme").resolve("templates");
        write(templates.resolve("_master.html"), "<!DOCTYPE html><html><head><title>M</title></head><body><header id=\"site-header\">h</header><main id=\"content\">m</main></body></html>");
        write(templates.resolve("_master.th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>");
        write(templates.resolve("about.html"), "<!DOCTYPE html><html><head><title>A</title></head><body><header id=\"site-header\">h</header><main id=\"content\"><h1 id=\"t\">PH</h1></main></body></html>");
        write(templates.resolve("about.th.xml"), "<?xml version=\"1.0\"?><thlogic><attr sel=\"head\" th:replace=\"~{${master} :: head}\"/><attr sel=\"#t\" th:text=\"${pageTitle}\"/></thlogic>");
        write(templates.resolve("about.meta.yml"), "path: /about\nlocales: [en, es]\ntitle:\n  en: About\n  es: Acerca\n");
        // A page available only in English, to exercise the explicit-locale-unavailable 404.
        write(templates.resolve("enonly.meta.yml"), "path: /enonly\nlocales: [en]\n");

        return controller;
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addPreferredLocale(EN); // deterministic Accept-Language
        return request;
    }

    @Test
    void explicitNonDefaultLocaleServes(@TempDir Path tmp) throws IOException {
        ResponseEntity<String> response = wire(tmp).serve(get("/es/about"));
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Acerca"));
    }

    @Test
    void defaultPrefixRedirectsToUnprefixed(@TempDir Path tmp) throws IOException {
        ResponseEntity<String> response = wire(tmp).serve(get("/en/about"));
        assertEquals(301, response.getStatusCode().value());
        assertEquals("/about", response.getHeaders().getFirst("Location"));
    }

    @Test
    void unprefixedDefaultServesInPlace(@TempDir Path tmp) throws IOException {
        assertEquals(200, wire(tmp).serve(get("/about")).getStatusCode().value());
    }

    @Test
    void unprefixedWithNonDefaultPreferenceCookieRedirects(@TempDir Path tmp) throws IOException {
        MockHttpServletRequest request = get("/about");
        request.setCookies(new Cookie("runway_lang_acme", "es"));
        ResponseEntity<String> response = wire(tmp).serve(request);
        assertEquals(302, response.getStatusCode().value());
        assertEquals("/es/about", response.getHeaders().getFirst("Location"));
    }

    @Test
    void switcherSetsCookieAndRedirects(@TempDir Path tmp) throws IOException {
        MockHttpServletRequest request = get("/about");
        request.setParameter("lang", "es");
        ResponseEntity<String> response = wire(tmp).serve(request);
        assertEquals(302, response.getStatusCode().value());
        assertEquals("/es/about", response.getHeaders().getFirst("Location"));
        assertTrue(response.getHeaders().getFirst("Set-Cookie").contains("runway_lang_acme=es"));
    }

    @Test
    void explicitLocaleUnavailableIs404(@TempDir Path tmp) throws IOException {
        assertEquals(404, wire(tmp).serve(get("/es/enonly")).getStatusCode().value());
    }

    @Test
    void missingPageIs404(@TempDir Path tmp) throws IOException {
        assertEquals(404, wire(tmp).serve(get("/es/missing")).getStatusCode().value());
    }

    @Test
    void noAcceptLanguageServesTheSiteDefaultInPlaceEvenWhenNotEnglish(@TempDir Path tmp) throws IOException {
        // es-default site; a client with NO Accept-Language header (a crawler, a bare curl) must get the es page
        // served in place at its unprefixed URL — not be redirected to /en/ by the servlet's fabricated
        // [server JVM default]. A bare MockHttpServletRequest sets no Accept-Language header.
        ResponseEntity<String> response =
                wire(tmp, Locale.of("es")).serve(new MockHttpServletRequest("GET", "/about"));
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void aGenuineEnglishPreferenceStillRedirectsOffTheEsDefault(@TempDir Path tmp) throws IOException {
        // A visitor that actually sends Accept-Language: en is redirected to the English URL (preference honoured).
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/about");
        request.addHeader("Accept-Language", "en");   // the header is genuinely present
        request.addPreferredLocale(EN);               // so getLocales() reflects it
        ResponseEntity<String> response = wire(tmp, Locale.of("es")).serve(request);
        assertEquals(302, response.getStatusCode().value());
        assertEquals("/en/about", response.getHeaders().getFirst("Location"));
    }
}
