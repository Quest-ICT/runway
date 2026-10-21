package mx.quest.runway.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import mx.quest.runway.page.PageChangedEvent;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.web.ReservedPaths;
import org.junit.jupiter.api.Test;

/**
 * The deferred-minors batch: the {@link ReservedPaths} single authority (both views), the catalog's per-site
 * enumeration cache (event-evicted, so a sitemap hit doesn't re-read every source per request), and
 * {@link PathOwnership#claimsByPath} (one pass, many probes).
 */
class ContentCatalogCacheAndReservedPathsTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final SiteView SITE = new SiteView("acme", "Acme", EN, List.of(EN), null);

    @Test
    void reservedPathsTwoViewsAgreeWithTheDesign() {
        // Request-time: the blanket surface only (exact kernel routes win at MVC dispatch by themselves).
        assertTrue(ReservedPaths.atRequestTime("/__admin/x"));
        assertTrue(ReservedPaths.atRequestTime("/actuator/health"));
        assertTrue(ReservedPaths.atRequestTime("/error"));
        assertFalse(ReservedPaths.atRequestTime("/login"), "exact routes are MVC-dispatched, not blanket-blocked");
        // Enumeration-time: the FULL union — never advertise what the router serves as something else.
        for (String reserved : new String[] {"/login", "/logout", "/forgot-password", "/reset",
                "/app/anything", "/__plugin/x", "/error"}) {
            assertTrue(ReservedPaths.atEnumerationTime(reserved), reserved + " must never be listed");
        }
        assertFalse(ReservedPaths.atEnumerationTime("/blog/my-post"));
    }

    @Test
    void enumerateIsCachedPerSiteAndEvictedByContentEvents() {
        AtomicInteger enumerations = new AtomicInteger();
        ContentSource counting = new ContentSource() {
            @Override public String key() { return "blog"; }
            @Override public List<ContentRef> enumerate(SiteView site) {
                enumerations.incrementAndGet();
                return List.of(ContentRef.of("a", "/blog/a", Map.of(), List.of(EN), Map.of("en", "A"),
                        Map.of(), "__site__/acme/e", null));
            }
            @Override public ContentBody body(SiteView site, String id, Locale locale) { return ContentBody.empty(); }
        };
        ContentCatalog catalog = new ContentCatalog(new ContentSourceRegistry(List.of(counting)));

        List<RoutableContent> first = catalog.enumerate(SITE);
        List<RoutableContent> second = catalog.enumerate(SITE);
        assertEquals(1, enumerations.get(), "the second call served from the cache");
        assertSame(first, second, "the identical cached list");

        catalog.onPageChanged(new PageChangedEvent("acme", "about"));
        List<RoutableContent> third = catalog.enumerate(SITE);
        assertEquals(2, enumerations.get(), "a page change evicted the cache");
        assertNotSame(first, third);

        catalog.onContentChanged(new ContentChangedEvent("acme", "blog", "a"));
        catalog.enumerate(SITE);
        assertEquals(3, enumerations.get(), "a content change evicted the cache");
    }

    @Test
    void anEvictionLandingDuringAnInFlightEnumerationIsNotClobberedByItsPut() {
        // The get→load→put race: a save's change event fires WHILE a sitemap request is mid-enumeration.
        // Without the eviction epoch, the in-flight load would put its pre-change list back over the eviction
        // (fresh timestamp), and every consumer — including a search engine rebuilding on that very event —
        // would serve pre-save content for the full TTL. The epoch makes the in-flight put decline.
        java.util.concurrent.atomic.AtomicReference<ContentCatalog> catalogRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger enumerations = new AtomicInteger();
        ContentSource racing = new ContentSource() {
            @Override public String key() { return "blog"; }
            @Override public List<ContentRef> enumerate(SiteView site) {
                int call = enumerations.incrementAndGet();
                if (call == 1) {
                    // The save completes and publishes its event exactly while this enumeration is loading.
                    catalogRef.get().onContentChanged(new ContentChangedEvent("acme", "blog", "new-entry"));
                    return List.of(ContentRef.of("old", "/blog/old", Map.of(), List.of(EN), Map.of("en", "Old"),
                            Map.of(), "__site__/acme/e", null));
                }
                return List.of(ContentRef.of("new-entry", "/blog/new-entry", Map.of(), List.of(EN),
                        Map.of("en", "New"), Map.of(), "__site__/acme/e", null));
            }
            @Override public ContentBody body(SiteView site, String id, Locale locale) { return ContentBody.empty(); }
        };
        ContentCatalog catalog = new ContentCatalog(new ContentSourceRegistry(List.of(racing)));
        catalogRef.set(catalog);

        assertEquals("old", catalog.enumerate(SITE).get(0).ref().id(), "the in-flight load returns what it read");
        // The next consumer (the engine's event-triggered rebuild) MUST see post-save content, not a cached
        // copy of the pre-save list the racing put tried to install.
        assertEquals("new-entry", catalog.enumerate(SITE).get(0).ref().id(),
                "the eviction survived the in-flight put — no stale re-install");
        assertEquals(2, enumerations.get(), "the second call re-enumerated instead of serving the racy cache");
    }

    @Test
    void claimsByPathIsOnePassManyProbes() {
        ContentRef entry = ContentRef.of("my-post", "/blog/my-post", Map.of("es", "/blog/mi-entrada"),
                List.of(EN, Locale.of("es")), Map.of("en", "P"), Map.of(), "__site__/acme/e", null);
        AtomicInteger enumerations = new AtomicInteger();
        ContentSource counting = new ContentSource() {
            @Override public String key() { return "blog"; }
            @Override public List<ContentRef> enumerate(SiteView site) {
                enumerations.incrementAndGet();
                return List.of(entry);
            }
            @Override public ContentBody body(SiteView site, String id, Locale locale) { return ContentBody.empty(); }
        };
        SiteView bilingual = new SiteView("acme", "Acme", EN, List.of(EN, Locale.of("es")), null);
        PathOwnership ownership = new PathOwnership(null, // pages store unused by claimsByPath
                new ContentCatalog(new ContentSourceRegistry(List.of(counting))));

        Map<String, List<String>> claims = ownership.claimsByPath(bilingual);
        assertEquals(1, enumerations.get(), "one enumeration pass for every locale probe");
        assertEquals(List.of("blog:my-post"), claims.get("en|/blog/my-post"));
        assertEquals(List.of("blog:my-post"), claims.get("es|/blog/mi-entrada"), "localized slug in its locale");
    }
}
