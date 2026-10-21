package mx.quest.runway.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.Test;

/** {@link ContentChangesImpl}: a plugin announces changes only for the sources it contributes. */
class ContentChangesImplTest {

    private record Source(String key) implements ContentSource {
        @Override
        public List<ContentRef> enumerate(SiteView site) {
            return List.of();
        }

        @Override
        public ContentBody body(SiteView site, String id, Locale locale) {
            return ContentBody.empty();
        }
    }

    /** A second source class, so ownership (by class) can differ between the two. */
    private record OtherSource(String key) implements ContentSource {
        @Override
        public List<ContentRef> enumerate(SiteView site) {
            return List.of();
        }

        @Override
        public ContentBody body(SiteView site, String id, Locale locale) {
            return ContentBody.empty();
        }
    }

    private final List<Object> published = new ArrayList<>();
    private final ContentSourceRegistry registry = new ContentSourceRegistry(List.of(new Source("blog"),
            new OtherSource("shop"), new KernelSource()));
    private final Map<Class<?>, String> owners = Map.of(Source.class, "blog-plugin", OtherSource.class, "shop-plugin");

    /** A source of no plugin (kernel code). */
    private static final class KernelSource implements ContentSource {
        @Override
        public String key() {
            return "pages-like";
        }

        @Override
        public List<ContentRef> enumerate(SiteView site) {
            return List.of();
        }

        @Override
        public ContentBody body(SiteView site, String id, Locale locale) {
            return ContentBody.empty();
        }
    }

    private ContentChangesImpl changes(String pluginId) {
        return new ContentChangesImpl(pluginId, () -> registry, type -> Optional.ofNullable(owners.get(type)),
                published::add);
    }

    @Test
    void aPluginAnnouncesItsOwnSourceAsTheHostEvent() {
        changes("blog-plugin").changed("acme", "blog", "hello");
        changes("blog-plugin").changed("acme", "blog", null);
        assertEquals(List.of(new ContentChangedEvent("acme", "blog", "hello"),
                new ContentChangedEvent("acme", "blog", null)), published, "null id = the whole corpus");
    }

    @Test
    void anotherPluginsSourceIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> changes("blog-plugin").changed("acme", "shop", "x"));
        assertTrue(refused.getMessage().contains("shop-plugin"), refused.getMessage());
        assertTrue(published.isEmpty(), "nothing is published on a refusal");
    }

    @Test
    void aKernelSourceAndAnUnknownKeyAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> changes("blog-plugin").changed("acme", "pages-like", "x"));
        assertThrows(IllegalArgumentException.class, () -> changes("blog-plugin").changed("acme", "nope", "x"));
        assertThrows(IllegalArgumentException.class, () -> changes("blog-plugin").changed("acme", null, "x"));
        assertTrue(published.isEmpty());
    }

    @Test
    void unsafeSiteIdsAreRefusedBeforeAnything() {
        for (String bad : new String[] {null, "", "../x", "a/b", "a b"}) {
            assertThrows(IllegalArgumentException.class, () -> changes("blog-plugin").changed(bad, "blog", "x"),
                    String.valueOf(bad));
        }
        assertTrue(published.isEmpty());
    }
}
