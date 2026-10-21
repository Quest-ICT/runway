package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.PageMeta;
import org.junit.jupiter.api.Test;

class PageViewsTest {

    private static final Locale EN = Locale.ENGLISH;

    @Test
    void materializesTheSpiPageMeta() {
        Page page = new Page("about", "/about", Map.of(), List.of(EN),
                Map.of("en", "About Us"), Map.of("en", "Our story"), "about", "_master", false, false, null, null);
        PageMeta view = PageViews.toView(page, EN, EN);
        assertEquals("about", view.slug());
        assertEquals("About Us", view.title());
        assertEquals("Our story", view.description());
        assertEquals(List.of(EN), view.availableLocales());
        assertEquals(EN, view.primaryLocale());
        assertTrue(view.published());
        assertFalse(view.noindex(), "an indexable page materializes noindex=false");
    }

    @Test
    void propagatesTheNoindexFlag() {
        Page indexable = new Page("a", "/a", Map.of(), List.of(EN), Map.of(), Map.of(), "", "_master", false, false, null, null);
        Page hidden = new Page("b", "/b", Map.of(), List.of(EN), Map.of(), Map.of(), "", "_master", false, true, null, null);
        assertFalse(PageViews.toView(indexable, EN, EN).noindex());
        assertTrue(PageViews.toView(hidden, EN, EN).noindex(), "the page's noindex reaches the SPI view");
    }
}
