package mx.quest.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.PageView;
import mx.quest.runway.spi.model.Severity;
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.Test;

/** The page editor's structured-data line says what the page is and where that comes from. */
class PageTypeCheckTest {

    private final FakePluginSettings config = TestSettings.declared();
    private final PageTypeCheck check = new PageTypeCheck(config, DiscoverabilityConfig.schemaTypes(), null);

    private static final Locale ES = Locale.forLanguageTag("es");

    private List<Finding> inspect(String path, Map<String, Object> cfg) {
        return inspect(path, cfg, false, List.of(Locale.ENGLISH));
    }

    private List<Finding> inspect(String path, Map<String, Object> cfg, boolean noindex, List<Locale> languages) {
        config.site("acme", cfg);
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, languages, "https://acme.test");
        PageMeta page = new PageMeta(path.substring(1), "T", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true,
                noindex);
        return check.inspect(new PageView(site, page, Locale.ENGLISH, "https://acme.test" + path, "<p>x</p>"));
    }

    @Test
    void theDefaultIsNamedAsTheSitesDefaultPageType() {
        List<Finding> f = inspect("/about", Map.of());
        assertEquals(1, f.size());
        assertEquals(Severity.INFO, f.get(0).severity());
        assertEquals("Structured data: this page is a WebPage, the site's Default page type.", f.get(0).message());
    }

    @Test
    void aPerPageTypeNamesItsPatternAndWhatElseItAdds() {
        List<Finding> f = inspect("/pricing", Map.of("content.schema",
                "/pri*:\n  - type: FAQPage\n    faq: []\n  - type: Service\n    name: S\n"));
        assertEquals("Structured data: this page is a FAQPage, set by Per-page types (pattern /pri*). "
                + "Per-page types also add: Service.", f.get(0).message());
    }

    @Test
    void anUnknownTypeAndASecondPageTypeAreWarnings() {
        List<Finding> f = inspect("/x", Map.of("content.schema",
                "/x:\n  - type: AboutPage\n  - type: ContactPage\n  - type: Typo\n"));
        assertEquals(3, f.size(), f.toString());
        assertTrue(f.stream().anyMatch(x -> x.severity() == Severity.WARNING && x.message().contains("Typo")), f.toString());
        assertTrue(f.stream().anyMatch(x -> x.severity() == Severity.WARNING && x.message().contains("ContactPage is ignored")),
                f.toString());
    }

    /** The head emits nothing on a noindex page, before anything else; the line says so, whatever else is set. */
    @Test
    void aNoindexPageIsSaidToCarryNoStructuredData() {
        List<Finding> f = inspect("/faq", Map.of("content.schema", "/faq:\n  - type: FAQPage\n  - type: Typo\n"),
                true, List.of(Locale.ENGLISH));
        assertEquals(1, f.size(), f.toString());
        assertEquals(Severity.INFO, f.get(0).severity());
        assertEquals("Structured data: this page is excluded from search engines (noindex), so it carries no "
                + "structured data.", f.get(0).message());
        assertEquals(1, inspect("/x", Map.of("jsonld.enabled", false), true, List.of(Locale.ENGLISH)).size());
        assertTrue(inspect("/x", Map.of("jsonld.enabled", false), true, List.of(Locale.ENGLISH)).get(0).message()
                .contains("noindex"), "noindex is checked first, as the head does");
    }

    @Test
    void anFaqPageWithNoCompleteQuestionIsAWarning() {
        List<Finding> f = inspect("/faq", Map.of("content.schema",
                "/faq:\n  type: FAQPage\n  faq:\n    - { question: Q, answer: A }\n"));
        assertEquals(2, f.size(), f.toString());
        assertEquals(Severity.WARNING, f.get(1).severity());
        assertEquals(PageTypeCheck.CODE_GAP, f.get(1).code());
        assertEquals("FAQPage has no question with both q: and a:, so the page is an FAQPage with no questions.",
                f.get(1).message());
        assertEquals(1, inspect("/faq", Map.of("content.schema",
                "/faq:\n  type: FAQPage\n  faq:\n    - { q: Q, a: A }\n")).size(), "a complete pair is enough");
        List<Finding> list = inspect("/c", Map.of("content.schema",
                "/c:\n  - type: AboutPage\n  - type: ItemList\n    items:\n      - { name: A }\n"));
        assertTrue(list.stream().anyMatch(x -> x.severity() == Severity.WARNING
                && x.message().equals("ItemList has no item with a url:, so no list is published.")), list.toString());
    }

    @Test
    void aTranslatedPageIsToldTheDefaultLanguagesEntryDoesNotReachIt() {
        Map<String, Object> cfg = Map.of("content.schema", "/faq:\n  type: FAQPage\n  faq:\n    - { q: Q, a: A }\n");
        List<Finding> f = inspect("/es/faq", cfg, false, List.of(Locale.ENGLISH, ES));
        assertEquals(2, f.size(), f.toString());
        assertEquals("Structured data: this page is a WebPage, the site's Default page type.", f.get(0).message());
        assertEquals(Severity.INFO, f.get(1).severity());
        assertEquals("Per-page types: /faq describes the default language's page, not this one; a page in another "
                + "language is matched only by its own path. To describe this page, add /es/faq.", f.get(1).message());
        assertEquals(1, inspect("/faq", cfg, false, List.of(Locale.ENGLISH, ES)).size(), "the default language's page");
    }

    @Test
    void switchedOffSaysSo() {
        List<Finding> f = inspect("/x", Map.of("jsonld.enabled", false));
        assertTrue(f.get(0).message().startsWith("Structured data is switched off"), f.toString());
    }
}
