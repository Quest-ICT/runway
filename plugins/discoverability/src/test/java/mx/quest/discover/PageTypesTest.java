package mx.quest.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.Test;

/**
 * A page type declared in Per-page types replaces the site's Default page type on the page's own node;
 * every other type adds an entity. The head and the editor ask {@link PageTypes} the same question.
 */
class PageTypesTest {

    private static final Locale ES = Locale.forLanguageTag("es");
    private static final SiteView SITE = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH, ES),
            "https://acme.test");
    private static final Map<String, SchemaType> TYPES = DiscoverabilityConfig.schemaTypes();

    private final FakePluginSettings config = TestSettings.declared();
    private final StructuredDataHead head = new StructuredDataHead(config, TYPES);

    private String render(String path, String title, Map<String, Object> cfg) {
        config.site("acme", cfg);
        PageMeta page = new PageMeta(path.substring(1), title, "d", List.of(Locale.ENGLISH), Locale.ENGLISH, true,
                false);
        String canonical = "https://acme.test" + path;
        return head.render(new HeadContext(SITE, page, Locale.ENGLISH, canonical, canonical, List.of(), false, false));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    void withoutAPageTypeThePageHasTheSiteDefault() {
        PageTypes.Description d = PageTypes.describe(Map.of(), "/about", TYPES);
        assertEquals("WebPage", d.type());
        assertEquals(PageTypes.Source.DEFAULT, d.source());
        assertEquals("Article", PageTypes.describe(Map.of("jsonld.type", "Article"), "/x", TYPES).type());
    }

    @Test
    void anArticleEntryIsThePagesOwnTypeNotASecondArticle() {
        String out = render("/news/one", "One", Map.of("jsonld.type", "Article",
                "content.schema", "/news/*:\n  type: BlogPosting\n  author: Ed\n"));
        assertEquals(1, count(out, "\"@type\":\"BlogPosting\""), out);
        assertEquals(0, count(out, "\"@type\":\"Article\""), "no second article beside the page: " + out);
        assertTrue(out.contains("\"@id\":\"https://acme.test/news/one#webpage\""), out);
        assertTrue(out.contains("\"headline\":\"One\"") && out.contains("\"name\":\"Ed\""), out);
        assertFalse(out.contains("mainEntityOfPage"), "nothing points back at itself: " + out);
    }

    @Test
    void anFaqEntryMakesThePageAnFaqPageWithItsQuestions() {
        String out = render("/faq", "FAQ", Map.of("content.schema",
                "/faq:\n  type: FAQPage\n  faq:\n    - { q: \"Why?\", a: \"Because.\" }\n"));
        assertEquals(1, count(out, "\"@type\":\"FAQPage\""), out);
        assertEquals(0, count(out, "\"@type\":\"WebPage\""), out);
        assertTrue(out.contains("\"mainEntity\":[{\"@type\":\"Question\""), out);
    }

    @Test
    void aCollectionPageIsThePageAndItsListIsItsMainEntity() {
        String out = render("/coffees", "Coffees", Map.of("content.schema",
                "/coffees:\n  type: CollectionPage\n  items:\n    - { name: A, url: /a }\n"));
        assertTrue(out.contains("\"@type\":\"CollectionPage\",\"@id\":\"https://acme.test/coffees#webpage\""), out);
        assertTrue(out.contains("\"mainEntity\":{\"@id\":\"https://acme.test/coffees#itemlist\"}"), out);
        assertEquals(1, count(out, "\"@type\":\"ItemList\""), out);
        // ItemList alone still only adds the list.
        String list = render("/coffees", "Coffees", Map.of("content.schema",
                "/coffees:\n  type: ItemList\n  items:\n    - { name: A, url: /a }\n"));
        assertTrue(list.contains("\"@type\":\"WebPage\""), list);
    }

    @Test
    void aSimplePageTypeOnlyNamesThePage() {
        String out = render("/contact", "Contact", Map.of("jsonld.type", "Article",
                "content.schema", "/contact:\n  type: ContactPage\n"));
        assertTrue(out.contains("\"@type\":\"ContactPage\""), out);
        assertTrue(out.contains("\"name\":\"Contact\""), "a non-article page keeps its name: " + out);
        assertEquals(0, count(out, "\"@type\":\"Article\""), out);
    }

    @Test
    void otherTypesStillAddEntitiesBesideThePageType() {
        PageTypes.Description d = PageTypes.describe(Map.of("content.schema",
                "/pricing:\n  - type: FAQPage\n    faq: []\n  - type: Service\n    name: S\n  - type: Nope\n"
                        + "  - type: AboutPage\n"), "/pricing", TYPES);
        assertEquals("FAQPage", d.type());
        assertEquals(PageTypes.Source.SCHEMA, d.source());
        assertEquals("/pricing", d.pattern());
        assertEquals(List.of("Service"), d.addedTypes());
        assertEquals(List.of("Nope"), d.unknown());
        assertEquals(List.of("AboutPage"), d.extraPageTypes(), "a page has one type; the second is reported");
    }

    /** An entry's text is in one language, so a translated page is matched only by its own path. */
    @Test
    void aPageBehindALanguagePrefixIsNotMatchedByTheDefaultLanguagesKey() {
        Map<String, Object> cfg = Map.of("content.schema", "/faq:\n  type: FAQPage\n  faq:\n"
                + "    - { q: \"Do you ship abroad?\", a: \"Yes.\" }\n/blog/*:\n  type: BlogPosting\n"
                + "/coffees/*:\n  type: Product\n  name: Ethiopia Guji\n  breadcrumb: { name: Coffees, url: /coffees }\n");
        PageTypes.Description d = PageTypes.describe(cfg, "/es/faq", TYPES);
        assertEquals("WebPage", d.type());
        assertEquals(PageTypes.Source.DEFAULT, d.source());
        assertNull(d.pattern(), "/faq does not reach /es/faq");
        assertEquals("WebPage", PageTypes.describe(cfg, "/es/blog/post", TYPES).type());
        assertNull(PageTypes.entry(cfg, "/es/coffees/guji"));

        String faq = render("/es/faq", "Preguntas frecuentes", cfg);
        assertFalse(faq.contains("FAQPage") || faq.contains("Do you ship abroad?"), "no English questions: " + faq);
        String product = render("/es/coffees/guji", "Guji", cfg);
        assertFalse(product.contains("Ethiopia Guji") || product.contains("\"Product\""), product);
        assertFalse(product.contains("\"name\":\"Coffees\""), "no English breadcrumb parent: " + product);
        // The default language's pages are unchanged.
        assertTrue(render("/faq", "FAQ", cfg).contains("Do you ship abroad?"));
    }

    @Test
    void aKeyWrittenForTheTranslatedPathApplies() {
        Map<String, Object> cfg = Map.of("content.schema", "/faq:\n  type: FAQPage\n  faq:\n"
                + "    - { q: \"Do you ship abroad?\", a: \"Yes.\" }\n/es/faq:\n  type: FAQPage\n  faq:\n"
                + "    - { q: \"¿Envían al extranjero?\", a: \"Sí.\" }\n/es/blog/*:\n  type: BlogPosting\n");
        PageTypes.Description d = PageTypes.describe(cfg, "/es/faq", TYPES);
        assertEquals("FAQPage", d.type());
        assertEquals("/es/faq", d.pattern());
        String out = render("/es/faq", "Preguntas frecuentes", cfg);
        assertTrue(out.contains("¿Envían al extranjero?"), out);
        assertFalse(out.contains("Do you ship abroad?"), out);
        assertEquals("BlogPosting", PageTypes.describe(cfg, "/es/blog/hola", TYPES).type());
        // A catch-all reaches every language's page, as it always did.
        assertEquals("AboutPage", PageTypes.describe(Map.of("content.schema", "/*:\n  type: AboutPage\n"),
                "/es/x", TYPES).type());
    }

    @Test
    void theEditorIsToldWhichDefaultLanguageEntryDoesNotReachATranslatedPage() {
        Map<String, Object> cfg = Map.of("content.schema", "/faq:\n  type: FAQPage\n/blog/*:\n  type: BlogPosting\n"
                + "/:\n  type: WebPage\n/team:\n  type: AboutPage\n/es/team:\n  type: AboutPage\n");
        assertEquals(new PageTypes.OtherLanguageEntry("/faq", "/es/faq"), PageTypes.otherLanguageEntry(cfg, "/es/faq", SITE));
        assertEquals(new PageTypes.OtherLanguageEntry("/blog/*", "/es/blog/*"),
                PageTypes.otherLanguageEntry(cfg, "/es/blog/x", SITE));
        assertEquals(new PageTypes.OtherLanguageEntry("/", "/es"), PageTypes.otherLanguageEntry(cfg, "/es", SITE));
        assertNull(PageTypes.otherLanguageEntry(cfg, "/es/team", SITE), "it has its own key");
        assertNull(PageTypes.otherLanguageEntry(cfg, "/faq", SITE), "a default-language page has no prefix");
        assertNull(PageTypes.otherLanguageEntry(cfg, "/en/faq", SITE), "the default language is never a prefix");
        assertNull(PageTypes.otherLanguageEntry(cfg, "/fr/faq", SITE), "fr is not a language of this site");
        assertNull(PageTypes.otherLanguageEntry(cfg, "/es/acerca", SITE), "nothing matches /acerca either");
        Map<String, Object> catchAll = Map.of("content.schema", "/*:\n  type: WebPage\n/es/*:\n  type: WebPage\n"
                + "/about:\n  type: AboutPage\n");
        assertNull(PageTypes.otherLanguageEntry(catchAll, "/es/x", SITE), "/es/* is at least as specific as /*");
        assertEquals(new PageTypes.OtherLanguageEntry("/about", "/es/about"),
                PageTypes.otherLanguageEntry(catchAll, "/es/about", SITE), "/es/* is less specific than /about");
    }

    @Test
    void aBlockWithoutATypeIsNotReportedAsUnknown() {
        PageTypes.Description d = PageTypes.describe(Map.of("content.schema",
                "/p:\n  - breadcrumb: { name: Shop, url: /shop }\n  - type: Product\n    name: P\n"), "/p", TYPES);
        assertTrue(d.unknown().isEmpty(), d.unknown().toString());
        assertEquals(List.of("Product"), d.addedTypes());
    }
}
