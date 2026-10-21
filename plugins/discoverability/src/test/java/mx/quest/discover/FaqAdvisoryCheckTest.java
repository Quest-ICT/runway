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
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.Test;

/**
 * FaqAdvisoryCheck: flags an unmarked on-page FAQ ({@code <details>/<summary>} clusters), stays silent below the
 * threshold, when there's no FAQ, on a noindex page, and when the head renders the page as an {@code FAQPage}.
 */
class FaqAdvisoryCheckTest {

    private final FakePluginSettings config = TestSettings.declared();
    private final FaqAdvisoryCheck check = new FaqAdvisoryCheck(config);

    private void cfg(Map<String, Object> map) {
        config.site("acme", map);
    }

    private static final String TWO_FAQ =
            "<main><details><summary>Why ranges?</summary>Scope drives cost.</details>"
            + "<details><summary>Fixed fee?</summary>Yes, usually.</details></main>";

    private static PageView page(String path, String html) {
        return page(path, html, false);
    }

    private static PageView page(String path, String html, boolean noindex) {
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
        PageMeta meta = new PageMeta(path.substring(1), "T", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true,
                noindex);
        return new PageView(site, meta, Locale.ENGLISH, "https://acme.test" + path, html);
    }

    @Test
    void flagsAnUnmarkedOnPageFaq() {
        cfg(Map.of()); // no content.schema → the FAQ is not typed
        List<Finding> found = check.inspect(page("/pricing", TWO_FAQ));
        assertEquals(1, found.size(), found.toString());
        assertEquals("discoverability.faq.unmarked", found.get(0).code());
        assertEquals("INFO", found.get(0).severity().name());
    }

    @Test
    void silentWhenTheFaqIsAlreadyTyped() {
        cfg(Map.of("content.schema", "/pricing:\n  type: FAQPage\n  faq:\n    - { q: Q, a: A }\n"));
        assertTrue(check.inspect(page("/pricing", TWO_FAQ)).isEmpty(), "already FAQPage-typed → no nag");
    }

    /** "Already typed" means the head renders the page as an FAQPage, the same answer PageTypes gives it. */
    @Test
    void aTypeTheHeadDoesNotApplyDoesNotCountAsTyped() {
        cfg(Map.of("content.schema", "/pricing:\n  type: faqpage\n  faq:\n    - { q: Q, a: A }\n"));
        List<Finding> misspelt = check.inspect(page("/pricing", TWO_FAQ));
        assertEquals(1, misspelt.size(), "faqpage is an unknown type, the page is a WebPage: " + misspelt);
        assertEquals("discoverability.faq.unmarked", misspelt.get(0).code());
        cfg(Map.of("content.schema",
                "/pricing:\n  - type: AboutPage\n  - type: FAQPage\n    faq:\n      - { q: Q, a: A }\n"));
        assertEquals(1, check.inspect(page("/pricing", TWO_FAQ)).size(),
                "a second page type is ignored, so the page is an AboutPage");
    }

    @Test
    void silentWhenStructuredDataIsOff() {
        cfg(Map.of("jsonld.enabled", false));
        assertTrue(check.inspect(page("/pricing", TWO_FAQ)).isEmpty(), "the head publishes no JSON-LD at all");
    }

    @Test
    void silentOnANoindexPage() {
        cfg(Map.of());
        assertTrue(check.inspect(page("/pricing", TWO_FAQ, true)).isEmpty(), "noindex pages carry no structured data");
    }

    @Test
    void silentBelowTheDisclosureThreshold() {
        cfg(Map.of());
        assertTrue(check.inspect(page("/x", "<details><summary>Q</summary>A</details>")).isEmpty(),
                "a lone <details> is a 'read more', not an FAQ");
        assertTrue(check.inspect(page("/y", "<p>no faq here</p>")).isEmpty(), "no disclosures at all");
    }

    @Test
    void silentOnEmptyHtml() {
        cfg(Map.of());
        assertTrue(check.inspect(page("/z", "")).isEmpty());
    }
}
