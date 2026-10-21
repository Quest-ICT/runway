package mx.quest.runway.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.Test;

/**
 * The {@link ContentSource} default methods: {@code resolve()} must be a correct-by-construction scan of
 * {@code enumerate()} — matching the locale-specific path ({@code pathFor}), skipping unpublished refs — and
 * {@code capabilities()} defaults to none. Pure unit tests over a tiny in-memory source.
 */
class ContentSourceDefaultsTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");
    private static final SiteView SITE = new SiteView("demo", "Demo", EN, List.of(EN, ES), null);

    /** A minimal source over a fixed list — exercises only the interface defaults. */
    private record FixedSource(List<ContentRef> refs) implements ContentSource {
        @Override public String key() { return "fixed"; }
        @Override public List<ContentRef> enumerate(SiteView site) { return refs; }
        @Override public ContentBody body(SiteView site, String id, Locale locale) { return ContentBody.empty(); }
    }

    private static ContentRef entry(String id, String path, Map<String, String> localized, boolean published) {
        return new ContentRef(id, path, localized, List.of(EN, ES), Map.of("en", id), Map.of(), null,
                "__plugin__/fixed/entry", published, null, false, null, null, Map.of());
    }

    @Test
    void defaultResolveMatchesTheLocaleSpecificPath() {
        ContentSource source = new FixedSource(List.of(
                entry("a", "/blog/a", Map.of("es", "/blog/a-es"), true),
                entry("b", "/blog/b", Map.of(), true)));

        assertEquals("a", source.resolve(SITE, "/blog/a", EN).orElseThrow().id(), "default path, default locale");
        assertEquals("a", source.resolve(SITE, "/blog/a-es", ES).orElseThrow().id(), "localized slug, its locale");
        assertTrue(source.resolve(SITE, "/blog/a-es", EN).isEmpty(),
                "a localized slug never doubles as another locale's URL (the page rule)");
        assertTrue(source.resolve(SITE, "/blog/none", EN).isEmpty(), "unknown path → miss");
    }

    @Test
    void defaultResolveSkipsUnpublishedContent() {
        ContentSource source = new FixedSource(List.of(entry("draft", "/blog/draft", Map.of(), false)));
        assertTrue(source.resolve(SITE, "/blog/draft", EN).isEmpty(),
                "an unpublished ref must never resolve (and the kernel re-filters as the backstop)");
    }

    @Test
    void capabilitiesDefaultToNone() {
        ContentSource source = new FixedSource(List.of());
        assertEquals(Capabilities.none(), source.capabilities());
        assertTrue(!source.capabilities().has(ContentSource.CAP_ARBITRARY_PATHS));
    }
}
