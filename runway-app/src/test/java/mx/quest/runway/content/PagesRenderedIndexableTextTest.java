package mx.quest.runway.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.i18n.I18nProperties;
import mx.quest.runway.i18n.SiteAwareMessageSource;
import mx.quest.runway.page.PageRouting;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.RenderModelBuilder;
import mx.quest.runway.platform.TestPlatformSettings;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.site.SiteViews;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.DraftRenderer;
import mx.quest.runway.web.PageRenderer;
import mx.quest.runway.web.SiteTemplateResolver;
import mx.quest.runway.web.TemplateCacheService;
import mx.quest.runway.widget.ContentWidgetRegistry;
import mx.quest.runway.widget.WidgetModelBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * A page's indexable text is the page <b>as rendered</b> in the requested locale: copy that the site's bundles
 * supply through decoupled logic ({@code th:text="#{key}"}) is in it, in that locale's language — the template
 * file alone holds only an empty element there. No request is bound: a reindex may run without one.
 */
class PagesRenderedIndexableTextTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");
    private static final Locale FR = Locale.FRENCH;

    @TempDir
    Path tmp;

    private StorageLayout layout;
    private SiteResolver siteResolver;
    private PagesContentSource source;
    private SiteView acme;
    private Path templates;
    private final java.util.concurrent.atomic.AtomicInteger renders = new java.util.concurrent.atomic.AtomicInteger();

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @BeforeEach
    void wire() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore siteStore = new SiteStore(layout, yaml);
        siteResolver = new SiteResolver(siteStore, TestPlatformSettings.empty());

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new SiteTemplateResolver(layout));
        I18nProperties i18n = new I18nProperties();
        i18n.setCacheSeconds(0);
        SiteAwareMessageSource bundles = new SiteAwareMessageSource(siteResolver, layout, i18n);
        engine.setTemplateEngineMessageSource(bundles);

        PageStore pageStore = new PageStore(layout, yaml);
        ContentCatalog catalog = new ContentCatalog(new ContentSourceRegistry(List.of()));
        DraftRenderer renderer = new DraftRenderer(new RenderModelBuilder(),
                new WidgetModelBuilder(new ContentWidgetRegistry(List.of()), layout,
                        new mx.quest.runway.widget.WidgetInstanceConfigService(layout,
                                new mx.quest.runway.crypto.AesGcmSecretCipher(
                                        new mx.quest.runway.security.RunwaySecurityProperties()))),
                new PageRouting(pageStore), catalog, new PageRenderer(engine), new TemplateCacheService(engine),
                siteResolver) {
            @Override
            public String renderForIndex(Site site, mx.quest.runway.page.Page page, Locale locale) {
                renders.incrementAndGet();
                return super.renderForIndex(site, page, locale);
            }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory(
                Map.of("draftRenderer", renderer, "siteAwareMessageSource", bundles));
        source = new PagesContentSource(pageStore, layout, siteStore, beans.getBeanProvider(DraftRenderer.class),
                beans.getBeanProvider(SiteAwareMessageSource.class));

        Site site = new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN, ES, FR), "https://acme.test",
                SiteStatus.ACTIVE);
        siteStore.save(site);
        acme = SiteViews.toView(site);

        templates = layout.siteDir("acme").resolve("templates");
        write(templates.resolve("_master.html"), """
                <!DOCTYPE html><html><head><title>M</title></head>
                <body><header id="site-header">Menu</header><main id="content">m</main>
                <footer id="site-footer">Footer copy</footer></body></html>""");
        write(templates.resolve("_master.th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>");
        write(templates.resolve("about.html"), """
                <!DOCTYPE html><html><head><title>A</title></head>
                <body><header id="site-header">h</header>
                <main id="content"><h1>Literal heading</h1><p id="faculty"></p>
                <form><label>Your name</label><input name="n"><button>Send now</button></form>
                <noscript>Enable scripts</noscript></main></body></html>""");
        write(templates.resolve("about.th.xml"), """
                <?xml version="1.0"?>
                <thlogic>
                  <attr sel="head" th:replace="~{${master} :: head}"/>
                  <attr sel="#site-header" th:replace="~{${master} :: #site-header}"/>
                  <attr sel="#faculty" th:text="#{about.faculty}"/>
                </thlogic>""");
        write(templates.resolve("about.meta.yml"), "path: /about\nlocales: [en, es, fr]\n");

        Path bundleDir = layout.siteDir("acme").resolve("i18n");
        write(bundleDir.resolve("messages.properties"), "about.faculty=An outstanding faculty of researchers\n");
        write(bundleDir.resolve("messages_es.properties"), "about.faculty=Una facultad de excelencia\n");
        write(bundleDir.resolve("messages_fr.properties"), "about.faculty=Une faculté d'excellence\n");
    }

    @Test
    void bundleTextIsIndexedInEachLocale() {
        String en = source.indexableText(acme, "about", EN);
        String es = source.indexableText(acme, "about", ES);
        String fr = source.indexableText(acme, "about", FR);

        assertTrue(en.contains("An outstanding faculty of researchers"), () -> "en: " + en);
        assertTrue(es.contains("Una facultad de excelencia"), () -> "es: " + es);
        assertTrue(fr.contains("Une faculté d'excellence") || fr.contains("Une facult&eacute; d'excellence"),
                () -> "fr: " + fr);
        assertFalse(es.contains("outstanding"), () -> "es carries only Spanish bundle text: " + es);
        assertTrue(es.contains("Literal heading"), () -> "literal template text stays indexed: " + es);
    }

    @Test
    void onlyTheContentSlotIsIndexed() {
        String text = source.indexableText(acme, "about", EN);
        assertFalse(text.contains("Menu"), () -> "header is page chrome: " + text);
        assertFalse(text.contains("Footer copy"), () -> "footer is page chrome: " + text);
    }

    @Test
    void formControlsAndNoscriptAreNotProse() {
        String text = source.indexableText(acme, "about", EN);
        assertFalse(text.contains("Your name"), () -> text);
        assertFalse(text.contains("Send now"), () -> text);
        assertFalse(text.contains("Enable scripts"), () -> text);
    }

    @Test
    void theRenderRestoresTheThreadsSiteBinding() {
        assertTrue(siteResolver.current().isEmpty());
        source.indexableText(acme, "about", ES);
        assertTrue(siteResolver.current().isEmpty(), "no site was bound before, none after");

        Site other = new Site("other", "Other", List.of("other.test"), EN, List.of(EN), "https://other.test",
                SiteStatus.ACTIVE);
        siteResolver.bind(other);
        try {
            assertTrue(source.indexableText(acme, "about", ES).contains("Una facultad"),
                    "the page's own site supplies the strings, not the site bound on the thread");
            assertSame(other, siteResolver.current().orElseThrow(), "the prior binding is restored");
        } finally {
            siteResolver.clear();
        }
    }

    @Test
    void aPageThatCannotRenderFallsBackToItsTemplateText() throws IOException {
        write(templates.resolve("about.th.xml"), """
                <?xml version="1.0"?>
                <thlogic>
                  <attr sel="#faculty" th:text="${#no.such.utility}"/>
                </thlogic>""");
        String text = source.indexableText(acme, "about", EN);
        assertTrue(text.contains("Literal heading"), () -> "template text is still indexed: " + text);
    }

    @Test
    void anUnpublishedPageIsNotRendered() throws IOException {
        write(templates.resolve("about.meta.yml"), "path: /about\nlocales: [en, es, fr]\ndraft: true\n");
        String text = source.indexableText(acme, "about", ES);
        assertFalse(text.contains("Una facultad"), () -> "a draft is never rendered for the index: " + text);
    }

    @Test
    void aRenderedTextIsCachedUntilSomethingItReadsChanges() throws IOException {
        source.indexableText(acme, "about", ES);
        source.indexableText(acme, "about", ES);
        assertEquals(1, renders.get(), "an unchanged page is rendered once, however often the index rebuilds");
        source.indexableText(acme, "about", EN);
        assertEquals(2, renders.get(), "each language is its own entry");

        // A bundle edited on disk, with no event: the bundle fingerprint changes, so the next call renders again.
        write(layout.siteDir("acme").resolve("i18n").resolve("messages_es.properties"),
                "about.faculty=Una facultad renovada\n");
        assertTrue(source.indexableText(acme, "about", ES).contains("Una facultad renovada"));
        assertEquals(3, renders.get());

        // A template edited on disk, with no event: its modification time changes.
        Path html = templates.resolve("about.html");
        write(html, Files.readString(html).replace("Literal heading", "Edited heading"));
        Files.setLastModifiedTime(html, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(html).toMillis() + 5_000));
        assertTrue(source.indexableText(acme, "about", ES).contains("Edited heading"));
        assertEquals(4, renders.get());
    }

    @Test
    void aPageEventDropsTheSitesCachedTexts() {
        source.indexableText(acme, "about", ES);
        source.onPageChanged(new mx.quest.runway.page.PageChangedEvent("acme", "about"));
        source.indexableText(acme, "about", ES);
        assertEquals(2, renders.get());
        source.onPageChanged(new mx.quest.runway.page.PageChangedEvent("other", "about"));
        source.indexableText(acme, "about", ES);
        assertEquals(2, renders.get(), "another site's event leaves this site's entries");
    }

    @Test
    void anUnknownPageIndexesNothing() {
        assertEquals("", source.indexableText(acme, "nosuchpage", EN));
    }
}
