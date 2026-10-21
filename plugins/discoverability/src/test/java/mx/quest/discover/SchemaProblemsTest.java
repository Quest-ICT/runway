package mx.quest.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import org.junit.jupiter.api.Test;

/** Mistakes in Per-page types never break a page, so the settings screen lists them instead. */
class SchemaProblemsTest {

    private final FakePluginSettings config = TestSettings.declared();
    private final DiscoverAdminModel model = new DiscoverAdminModel(config, null);

    private List<String> problems(String schema) {
        config.site("acme", Map.of("content.schema", schema));
        return model.schemaProblems("acme");
    }

    @Test
    void aCleanOrBlankSchemaHasNone() {
        assertEquals(List.of(), problems(""));
        assertEquals(List.of(), problems("/faq:\n  type: FAQPage\n  faq:\n    - { q: Q, a: A }\n"
                + "/shop/*:\n  - type: Product\n    name: P\n/p:\n  type: Product\n"));
    }

    /** A block that applies but publishes nothing of its own is listed; a product's name defaults to the title. */
    @Test
    void aBlockThatPublishesNothingOfItsOwnIsListed() {
        List<String> p = problems("/faq:\n  type: FAQPage\n  faq: []\n/help:\n  type: FAQPage\n  faq:\n"
                + "    - { question: Q, answer: A }\n/c:\n  type: CollectionPage\n  items:\n    - { name: A }\n"
                + "/l:\n  - type: AboutPage\n  - type: ItemList\n/two:\n  - type: AboutPage\n  - type: FAQPage\n");
        assertEquals(List.of(
                "/faq: FAQPage has no question with both q: and a:, so the page is an FAQPage with no questions.",
                "/help: FAQPage has no question with both q: and a:, so the page is an FAQPage with no questions.",
                "/c: CollectionPage has no item with a url:, so no list is published.",
                "/l: ItemList has no item with a url:, so no list is published.",
                "/two: more than one page type; only the first applies."), p);
    }

    /** Google requires a mainEntity on both, which a type with no keys cannot give; they are not offered. */
    @Test
    void qaPageAndProfilePageAreNotTypes() {
        assertEquals(List.of("/qa: QAPage is not a type this plugin knows; ignored.",
                "/me: ProfilePage is not a type this plugin knows; ignored."),
                problems("/qa:\n  type: QAPage\n/me:\n  type: ProfilePage\n"));
    }

    @Test
    void unreadableYamlIsOneProblemNamingTheError() {
        List<String> p = problems("/faq: [unclosed\n");
        assertEquals(1, p.size(), p.toString());
        assertTrue(p.get(0).startsWith("Per-page types cannot be read"), p.toString());
        assertEquals(1, problems("- just\n- a list\n").size(), "a list root is not a pattern map");
    }

    @Test
    void eachMistakeIsNamedWithItsPattern() {
        List<String> p = problems("about:\n  type: AboutPage\n/a*b:\n  type: WebPage\n/x:\n  type: Prodcut\n"
                + "/y:\n  - type: AboutPage\n  - type: ContactPage\n/z: just text\n");
        assertTrue(p.contains("about: a pattern starts with / and matches page paths."), p.toString());
        assertTrue(p.contains("/a*b: * works only at the end of a pattern; here it is matched as a character."),
                p.toString());
        assertTrue(p.contains("/x: Prodcut is not a type this plugin knows; ignored."), p.toString());
        assertTrue(p.contains("/y: more than one page type; only the first applies."), p.toString());
        assertTrue(p.contains("/z: the value must be a type block (type: ...) or a list of them; ignored."),
                p.toString());
        assertEquals(5, p.size(), p.toString());
    }
}
