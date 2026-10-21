package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.site.SiteParamsService;
import mx.quest.runway.spi.model.Alternate;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenderModelBuilderTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");
    private final RenderModelBuilder builder = new RenderModelBuilder();

    @SuppressWarnings("unchecked")
    @Test
    void buildsCoreModelForTheDefaultLocale() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        Page page = new Page("about", "/about", Map.of(), List.of(EN),
                Map.of("en", "About Us"), Map.of("en", "desc"), "about", "_master", false, false, null, null);

        Map<String, Object> model = builder.build(site, page, EN, "__site__/acme/_master", "/about", "https://acme.test");

        assertEquals(site, model.get("site"));
        assertInstanceOf(PageMeta.class, model.get("page"));
        assertEquals(EN, model.get("locale"));
        assertEquals("__site__/acme/_master", model.get("master"));
        assertEquals("About Us", model.get("pageTitle"));
        assertEquals("https://acme.test/about", model.get("canonical")); // default locale is unprefixed
        assertEquals("https://acme.test/about", model.get("xDefault"));

        List<Alternate> alternates = (List<Alternate>) model.get("alternates");
        assertEquals(1, alternates.size());
        assertEquals("https://acme.test/about", alternates.get(0).url());

        List<LocaleOption> siteLocales = (List<LocaleOption>) model.get("siteLocales");
        assertEquals(1, siteLocales.size());
        assertEquals("en", siteLocales.get(0).code());
        assertTrue(siteLocales.get(0).current());
        assertEquals("/about?lang=en", siteLocales.get(0).url());
    }

    @SuppressWarnings("unchecked")
    @Test
    void buildsMultilingualModelForANonDefaultLocale() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN, ES), "https://acme.test");
        Page page = new Page("about", "/about", Map.of(), List.of(EN, ES),
                Map.of("en", "About", "es", "Acerca"), Map.of(), "about", "_master", false, false, null, null);

        Map<String, Object> model = builder.build(site, page, ES, "__site__/acme/_master", "/es/about", "https://acme.test");

        assertEquals("https://acme.test/es/about", model.get("canonical")); // non-default locale is prefixed
        assertEquals("https://acme.test/about", model.get("xDefault")); // x-default = primary (en)
        assertEquals("Acerca", model.get("pageTitle"));

        List<Alternate> alternates = (List<Alternate>) model.get("alternates");
        assertEquals(2, alternates.size());

        List<LocaleOption> siteLocales = (List<LocaleOption>) model.get("siteLocales");
        assertEquals(2, siteLocales.size());
        LocaleOption es = siteLocales.stream().filter(o -> o.code().equals("es")).findFirst().orElseThrow();
        assertTrue(es.current());
        assertEquals("/es/about?lang=es", es.url());
        assertEquals("/es/about?lang=en", siteLocales.stream().filter(o -> o.code().equals("en")).findFirst().orElseThrow().url());
    }

    @Test
    void anAbsoluteCanonicalOverrideWinsOverTheAutoValue() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        Page page = new Page("about", "/about", Map.of(), List.of(EN), Map.of("en", "About"), Map.of(),
                "", "_master", false, false, "https://canonical.example/here", null);

        Map<String, Object> model = builder.build(site, page, EN, "__site__/acme/_master", "/about", "https://acme.test");
        assertEquals("https://canonical.example/here", model.get("canonical"), "an absolute override is verbatim");
        assertEquals(Boolean.FALSE, model.get("noindex"));

        HeadContext head = builder.headContext(site, page, EN, "https://acme.test", false);
        assertEquals("https://canonical.example/here", head.canonical(), "the head uses the same override");
    }

    @Test
    void aRootRelativeCanonicalOverrideIsResolvedAgainstTheBaseUrl() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        Page page = new Page("about", "/about", Map.of(), List.of(EN), Map.of("en", "About"), Map.of(),
                "", "_master", false, false, "/preferred", null);

        Map<String, Object> model = builder.build(site, page, EN, "__site__/acme/_master", "/about", "https://acme.test");
        assertEquals("https://acme.test/preferred", model.get("canonical"), "a '/'-value is prefixed with the base URL");
    }

    @Test
    void paramsAreEmptyWhenNoParamsServiceAndNoPageParams() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        Page page = new Page("about", "/about", Map.of(), List.of(EN), Map.of("en", "About"), Map.of(),
                "", "_master", false, false, null, null);
        Map<String, Object> model = builder.build(site, page, EN, "__site__/acme/_master", "/about", "https://acme.test");
        assertTrue(((Map<?, ?>) model.get("params")).isEmpty(), "no params service + no page params ⇒ empty map");
    }

    @Test
    void pageParamsSurfaceEvenWithoutASiteParamsService() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        Page page = new Page("about", "/about", Map.of(), List.of(EN), Map.of("en", "About"), Map.of(),
                "", "_master", false, false, null, null, Map.of("pageOnly", "p"));
        Map<String, Object> model = builder.build(site, page, EN, "__site__/acme/_master", "/about", "https://acme.test");
        assertEquals("p", ((Map<?, ?>) model.get("params")).get("pageOnly"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void mergesSiteAndPageParamsWithPageWinningOnCollision(@TempDir Path external) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        SiteParamsService siteParams = new SiteParamsService(layout, new YamlStore());
        siteParams.save("acme", Map.of("shared", "site", "siteOnly", "s"));
        RenderModelBuilder merging = new RenderModelBuilder(siteParams);

        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        Page page = new Page("about", "/about", Map.of(), List.of(EN), Map.of("en", "About"), Map.of(),
                "", "_master", false, false, null, null, Map.of("shared", "page", "pageOnly", "p"));

        Map<String, Object> model = merging.build(site, page, EN, "__site__/acme/_master", "/about", "https://acme.test");
        Map<String, String> params = (Map<String, String>) model.get("params");
        assertEquals("page", params.get("shared"), "the page overrides the site on a shared key");
        assertEquals("s", params.get("siteOnly"), "a site-only param is present");
        assertEquals("p", params.get("pageOnly"), "a page-only param is present");
        assertEquals(3, params.size());
    }

    @Test
    void noindexSurfacesInModelAndHeadContext() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        Page page = new Page("secret", "/secret", Map.of(), List.of(EN), Map.of("en", "Secret"), Map.of(),
                "", "_master", false, true, null, null);

        Map<String, Object> model = builder.build(site, page, EN, "__site__/acme/_master", "/secret", "https://acme.test");
        assertEquals(Boolean.TRUE, model.get("noindex"));
        // No override ⇒ the auto-computed canonical still applies.
        assertEquals("https://acme.test/secret", model.get("canonical"));

        HeadContext head = builder.headContext(site, page, EN, "https://acme.test", false);
        assertTrue(head.noindex(), "the page's noindex reaches the head context");

        // A preview forces noindex even for an indexable page.
        Page indexable = new Page("open", "/open", Map.of(), List.of(EN), Map.of("en", "Open"), Map.of(),
                "", "_master", false, false, null, null);
        assertTrue(builder.headContext(site, indexable, EN, "https://acme.test", true).noindex(),
                "a preview render is never indexable");
    }
}
