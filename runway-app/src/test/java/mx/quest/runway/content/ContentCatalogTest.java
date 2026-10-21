package mx.quest.runway.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link ContentCatalog} — the fail-closed enumeration authority: unions all sources (pages included by way of
 * whatever sources are registered), drops unpublished/unsafe/reserved refs, and contains a throwing source so
 * it never breaks the others' listing. Pure unit tests (the contributor registry arrives empty via a stub
 * provider).
 */
class ContentCatalogTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");
    private static final SiteView SITE = new SiteView("acme", "Acme", EN, List.of(EN, ES), null);

    /** An always-empty ObjectProvider (no contributor registry in these unit tests). */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> none() {
        // MockEnvironment is irrelevant; we just need a provider that yields null.
        new MockEnvironment();
        return (ObjectProvider<T>) new ObjectProvider<Object>() {
            @Override public Object getObject() { throw new IllegalStateException("none"); }
            @Override public Object getIfAvailable() { return null; }
        };
    }

    private static ContentSource fixed(String key, List<ContentRef> refs) {
        return new ContentSource() {
            @Override public String key() { return key; }
            @Override public List<ContentRef> enumerate(SiteView site) { return refs; }
            @Override public ContentBody body(SiteView site, String id, Locale locale) { return ContentBody.empty(); }
        };
    }

    private static ContentRef ref(String id, String path) {
        return ContentRef.of(id, path, Map.of(), List.of(EN), Map.of("en", id), Map.of(), "__site__/acme/e", null);
    }

    @Test
    void unionsAllSourcesInRegistrationOrder() {
        ContentCatalog catalog = new ContentCatalog(new ContentSourceRegistry(List.of(
                fixed("pages", List.of(ref("home", "/"))),
                fixed("blog", List.of(ref("post", "/blog/post"))))));

        List<RoutableContent> all = catalog.enumerate(SITE);
        assertEquals(2, all.size());
        assertEquals("pages", all.get(0).sourceKey());
        assertEquals("blog", all.get(1).sourceKey());
    }

    @Test
    void dropsUnpublishedUnsafeAndReservedRefsFailClosed() {
        ContentRef unpublished = new ContentRef("draft", "/draft", Map.of(), List.of(EN), Map.of(), Map.of(),
                null, "__site__/acme/e", false, null, false, null, null, Map.of());
        ContentRef reserved = ref("login-page", "/login");
        ContentRef recovery = ref("forgot", "/forgot-password");
        ContentRef reset = ref("reset-page", "/reset");
        ContentRef reservedLocalized = ContentRef.of("sneaky", "/fine", Map.of("es", "/__admin/x"), List.of(EN, ES),
                Map.of(), Map.of(), "__site__/acme/e", null);
        ContentRef good = ref("ok", "/ok");

        ContentCatalog catalog = new ContentCatalog(new ContentSourceRegistry(List.of(
                fixed("blog", List.of(unpublished, reserved, recovery, reset, reservedLocalized, good)))));

        List<RoutableContent> all = catalog.enumerate(SITE);
        assertEquals(1, all.size(), "only the clean ref survives (recovery routes are reserved too)");
        assertEquals("ok", all.get(0).ref().id());
    }

    @Test
    void aThrowingSourceContributesNothingAndNeverBreaksTheOthers() {
        ContentSource broken = new ContentSource() {
            @Override public String key() { return "broken"; }
            @Override public List<ContentRef> enumerate(SiteView site) { throw new IllegalStateException("boom"); }
            @Override public ContentBody body(SiteView site, String id, Locale locale) { return ContentBody.empty(); }
        };
        ContentCatalog catalog = new ContentCatalog(new ContentSourceRegistry(List.of(
                broken, fixed("blog", List.of(ref("post", "/blog/post"))))));

        List<RoutableContent> all = catalog.enumerate(SITE);
        assertEquals(1, all.size(), "the healthy source still lists");
        assertEquals("post", all.get(0).ref().id());
    }

    @Test
    void urlMapKeysSourceContentByCompositeIdWithLocaleCorrectUrls() {
        ContentRef entry = ContentRef.of("my-post", "/blog/my-post", Map.of("es", "/blog/mi-entrada"),
                List.of(EN, ES), Map.of("en", "My post"), Map.of(), "__site__/acme/e", null);
        ContentRef enOnly = ContentRef.of("en-only", "/blog/en-only", Map.of(), List.of(EN),
                Map.of("en", "EN"), Map.of(), "__site__/acme/e", null);
        ContentCatalog catalog = new ContentCatalog(new ContentSourceRegistry(List.of(
                fixed("pages", List.of(ref("home", "/"))), fixed("blog", List.of(entry, enOnly)))));

        Map<String, String> en = catalog.urlMap(SITE, EN);
        Map<String, String> es = catalog.urlMap(SITE, ES);
        assertEquals("/blog/my-post", en.get("blog:my-post"), "composite key, locale-correct URL");
        assertEquals("/es/blog/mi-entrada", es.get("blog:my-post"), "localized slug + prefix in es");
        assertEquals("/blog/en-only", es.get("blog:en-only"),
                "an untranslated entry falls back to its primary URL — a link never 404s");
        assertTrue(!en.containsKey("pages:home") && !en.containsKey("home"),
                "the pages projection is skipped — pages keep their bare-slug keys in PageRouting.urlMap");
    }

    @Test
    void anEmptyRegistryEnumeratesEmpty() {
        ContentCatalog catalog = new ContentCatalog(new ContentSourceRegistry(List.of()));
        assertTrue(catalog.enumerate(SITE).isEmpty());
    }

    /** A source that declares {@code CAP_INDEXABLE_TEXT} and serves fixed material (or throws). */
    private static ContentSource indexable(String key, ContentRef ref, String material, boolean throwing) {
        return new ContentSource() {
            @Override public String key() { return key; }
            @Override public List<ContentRef> enumerate(SiteView site) { return List.of(ref); }
            @Override public ContentBody body(SiteView site, String id, Locale locale) { return ContentBody.empty(); }
            @Override public mx.quest.runway.spi.model.Capabilities capabilities() {
                return mx.quest.runway.spi.model.Capabilities.of(ContentSource.CAP_INDEXABLE_TEXT);
            }
            @Override public String indexableText(SiteView site, String id, Locale locale) {
                if (throwing) {
                    throw new IllegalStateException("boom");
                }
                return material;
            }
        };
    }

    @Test
    void indexableTextIsCapabilityGatedAndFailureContained() {
        ContentRef optedIn = ref("post", "/blog/post");
        ContentRef optedOut = ref("item", "/shop/item");
        ContentRef broken = ref("bad", "/bad");
        ContentCatalog catalog = new ContentCatalog(new ContentSourceRegistry(List.of(
                indexable("blog", optedIn, "raw markdown material", false),
                fixed("shop", List.of(optedOut)),   // no CAP_INDEXABLE_TEXT — the default must never be consulted
                indexable("bad", broken, "", true))));

        assertEquals("raw markdown material",
                catalog.indexableText(SITE, new RoutableContent("blog", optedIn), EN),
                "a source that declares the capability serves its raw material through the catalog");
        assertEquals("", catalog.indexableText(SITE, new RoutableContent("shop", optedOut), EN),
                "no declared capability → empty, without calling the source's default");
        assertEquals("", catalog.indexableText(SITE, new RoutableContent("bad", broken), EN),
                "a throwing source is contained to empty — indexing never breaks on one bad source");
        assertEquals("", catalog.indexableText(SITE, new RoutableContent("ghost", optedIn), EN),
                "an unknown source key resolves to empty, never a lookup failure");
    }
}
