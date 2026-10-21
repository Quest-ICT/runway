package mx.quest.runway.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.Test;

/**
 * {@link ContentSourceRegistry}: collects sources by stable key, preserves registration order, and — the
 * load-bearing rule — <b>fails fast at construction</b> on a blank or duplicate key, so a misconfigured source
 * can never silently shadow another's content namespace.
 */
class ContentSourceRegistryTest {

    private static ContentSource source(String key) {
        return new ContentSource() {
            @Override public String key() { return key; }
            @Override public List<ContentRef> enumerate(SiteView site) { return List.of(); }
            @Override public ContentBody body(SiteView site, String id, Locale locale) { return ContentBody.empty(); }
        };
    }

    @Test
    void indexesByKeyInRegistrationOrder() {
        ContentSource pages = source("pages");
        ContentSource blog = source("blog");
        ContentSourceRegistry registry = new ContentSourceRegistry(List.of(pages, blog));

        assertEquals(2, registry.sources().size());
        assertEquals("pages", registry.sources().get(0).key(), "registration order preserved");
        assertEquals("blog", registry.sources().get(1).key());
        assertTrue(registry.byKey("blog").isPresent());
        assertTrue(registry.byKey("shop").isEmpty());
        assertTrue(!registry.isEmpty());
    }

    @Test
    void emptyRegistryIsLegalAndMeansTodaysBehaviour() {
        ContentSourceRegistry registry = new ContentSourceRegistry(List.of());
        assertTrue(registry.isEmpty());
        assertEquals(List.of(), registry.sources());
    }

    @Test
    void duplicateKeyFailsFastNamingBothImplementations() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ContentSourceRegistry(List.of(source("blog"), source("blog"))));
        assertTrue(ex.getMessage().contains("blog"), "the offending key is named");
    }

    @Test
    void blankKeyFailsFast() {
        assertThrows(IllegalStateException.class, () -> new ContentSourceRegistry(List.of(source(" "))));
        assertThrows(IllegalStateException.class, () -> new ContentSourceRegistry(List.of(source(null))));
    }
}
