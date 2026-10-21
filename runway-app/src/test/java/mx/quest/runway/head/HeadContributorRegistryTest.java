package mx.quest.runway.head;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

/** The registry's contract: deterministic ordering, blank-skipping, and fail-fast key collisions. */
class HeadContributorRegistryTest {

    private static HeadContext context() {
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
        PageMeta page = new PageMeta("about", "About", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        return new HeadContext(site, page, Locale.ENGLISH, "https://acme.test/about", "https://acme.test/about",
                List.of(), false, false);
    }

    /** A contributor with a fixed key, output, and order. */
    private static final class Fixed implements HeadContributor, Ordered {
        private final String key;
        private final String markup;
        private final int order;

        Fixed(String key, String markup, int order) {
            this.key = key;
            this.markup = markup;
            this.order = order;
        }

        @Override public String key() { return key; }
        @Override public String render(HeadContext context) { return markup; }
        @Override public int getOrder() { return order; }
    }

    @Test
    void emptyRegistryIsEmptyAndRendersNothing() {
        HeadContributorRegistry registry = new HeadContributorRegistry(List.of());
        assertTrue(registry.isEmpty());
        assertEquals("", registry.render(context()));
    }

    @Test
    void singleContributorRendersItsMarkup() {
        HeadContributorRegistry registry =
                new HeadContributorRegistry(List.of(new Fixed("a", "<meta name=\"a\">", 0)));
        assertEquals("<meta name=\"a\">", registry.render(context()));
    }

    @Test
    void rendersInAscendingOrderRegardlessOfInputOrder() {
        // Supplied high-order-first; the registry must sort to low-order-first.
        HeadContributorRegistry registry = new HeadContributorRegistry(List.of(
                new Fixed("b", "<b>", 20),
                new Fixed("a", "<a>", 10)));
        assertEquals("<a>\n<b>", registry.render(context()));
    }

    @Test
    void blankContributorsContributeNothingAndAddNoSeparators() {
        HeadContributorRegistry registry = new HeadContributorRegistry(List.of(
                new Fixed("a", "   ", 10),
                new Fixed("b", "<b>", 20),
                new Fixed("c", "", 30)));
        assertEquals("<b>", registry.render(context()));
    }

    @Test
    void duplicateKeysFailFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new HeadContributorRegistry(List.of(
                        new Fixed("dup", "<x>", 10),
                        new Fixed("dup", "<y>", 20))));
        assertTrue(ex.getMessage().contains("dup"), ex::getMessage);
    }

    @Test
    void blankKeyFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new HeadContributorRegistry(List.of(new Fixed("  ", "<x>", 0))));
    }
}
