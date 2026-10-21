package mx.quest.runway.spi.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What SPI 1.0.0 adds or fixes on the boundary records: {@code HeadContext.origin} and {@code CatalogEntry.pathFor}. */
class K4RecordsTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");
    private static final Locale FR = Locale.FRENCH;

    private static SiteView site() {
        return new SiteView("s1", "Site", EN, List.of(EN, ES), null);
    }

    private static PageMeta page() {
        return new PageMeta("home", "Home", "A page", List.of(EN), EN, true, false);
    }

    // --- HeadContext.origin -----------------------------------------------------------------------------------

    @Test
    void theOriginIsCarriedWithoutATrailingSlashAndNeverNull() {
        assertEquals("https://acme.test", new HeadContext(site(), page(), EN, "/", null, List.of(), false, false,
                null, null, "https://acme.test/").origin());
        assertEquals("http://localhost:8080", new HeadContext(site(), page(), EN, "/", null, List.of(), false, false,
                null, null, "http://localhost:8080").origin());
        assertEquals("", new HeadContext(site(), page(), EN, "/", null, List.of(), false, false, null, null, null)
                .origin());
    }

    @Test
    void everyOlderConstructorLeavesTheOriginUnknown() {
        assertEquals("", new HeadContext(site(), page(), EN, "/", null, List.of(), false, false, SeoTags.empty(),
                IconHints.NONE).origin(), "the 0.6.0 form");
        assertEquals("", new HeadContext(site(), page(), EN, "/", null, List.of(), false, false, SeoTags.empty())
                .origin());
        assertEquals("", new HeadContext(site(), page(), EN, "/", null, List.of(), false, false).origin());
    }

    // --- CatalogEntry.pathFor ---------------------------------------------------------------------------------

    private static ContentRef ref(String id) {
        return ContentRef.of(id, "/" + id, Map.of(), List.of(), Map.of(), Map.of(), "__plugin__/x/t", null);
    }

    @Test
    void pathForNeverLinksAnXDefaultOutsideTheItemsOwnUrls() {
        // A hand-built entry (or one from a pre-1.0 kernel) whose x-default is the site-default path the item does
        // not exist at: a context in a locale the item lacks links the item's first real address instead.
        CatalogEntry entry = new CatalogEntry("blog", ref("salut"),
                List.of(new LocalizedUrl(ES, "/es/salut", null)), "/salut");
        assertEquals("/es/salut", entry.pathFor(EN), "never the unprefixed path the site does not serve");
        assertEquals("/es/salut", entry.pathFor(FR));
        assertEquals("/salut", entry.xDefaultPath(), "the component itself is reported as given");
    }

    @Test
    void pathForPrefersTheXDefaultWhenItIsOneOfTheUrls() {
        CatalogEntry entry = new CatalogEntry("blog", ref("hi"), List.of(new LocalizedUrl(ES, "/es/hola", null),
                new LocalizedUrl(EN, "/hi", null)), "/hi");
        assertEquals("/hi", entry.pathFor(FR), "x-default (the primary's address) wins over the first URL");
        assertEquals("/es/hola", entry.pathFor(ES));
    }

    @Test
    void pathForWithNoUrlsAnswersTheXDefault() {
        assertEquals("/hi", new CatalogEntry("blog", ref("hi"), List.of(), "/hi").pathFor(EN));
    }
}
