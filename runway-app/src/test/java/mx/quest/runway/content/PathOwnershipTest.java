package mx.quest.runway.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PathOwnership} — the engine behind the visibility triple: who serves a URL, which pages shadow which
 * source content (real page wins, but never silently), and which URLs two plugins contest (the loud-500 case),
 * across locales including localized slugs.
 */
class PathOwnershipTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");
    private static final SiteView SITE = new SiteView("acme", "Acme", EN, List.of(EN, ES), null);

    private static ContentSource fixed(String key, ContentRef... refs) {
        return new ContentSource() {
            @Override public String key() { return key; }
            @Override public List<ContentRef> enumerate(SiteView site) { return List.of(refs); }
            @Override public ContentBody body(SiteView site, String id, Locale locale) { return ContentBody.empty(); }
        };
    }

    private static ContentRef entry(String id, String path, Map<String, String> localized) {
        return ContentRef.of(id, path, localized, List.of(EN, ES), Map.of("en", id), Map.of(),
                "__site__/acme/e", null);
    }

    private static PageStore pages(Path tmp) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        Path templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("about.meta.yml"),
                "path: /about\nlocalizedPaths:\n  es: /acerca\nlocales: [en, es]\n", StandardCharsets.UTF_8);
        return new PageStore(layout, new YamlStore());
    }

    @Test
    void aPageShadowingAnEntryIsReportedWithBothIdentities(@TempDir Path tmp) throws IOException {
        // The blog entry claims /acerca in ES — exactly the page's LOCALIZED slug: shadowed there only.
        PathOwnership ownership = new PathOwnership(pages(tmp), new ContentCatalog(new ContentSourceRegistry(
                List.of(fixed("blog", entry("my-post", "/blog/my-post", Map.of("es", "/acerca")))))));

        List<PathOwnership.Conflict> conflicts = ownership.conflicts(SITE);
        assertEquals(1, conflicts.size());
        PathOwnership.Conflict c = conflicts.get(0);
        assertEquals(PathOwnership.Kind.PAGE_SHADOWS_CONTENT, c.kind());
        assertEquals("/acerca", c.path());
        assertEquals("es", c.localeTag());
        assertEquals("about", c.pageSlug(), "the winning page is named");
        assertEquals(List.of("blog:my-post"), c.claims(), "the shadowed content is named");
    }

    @Test
    void twoSourcesContestingOneUrlAreReportedAsAClash(@TempDir Path tmp) throws IOException {
        PathOwnership ownership = new PathOwnership(pages(tmp), new ContentCatalog(new ContentSourceRegistry(
                List.of(fixed("blog", entry("a", "/clash", Map.of())),
                        fixed("shop", entry("b", "/clash", Map.of()))))));

        List<PathOwnership.Conflict> conflicts = ownership.conflicts(SITE);
        // /clash is contested in BOTH locales (each entry declares en+es with no localized override).
        assertEquals(2, conflicts.size());
        for (PathOwnership.Conflict c : conflicts) {
            assertEquals(PathOwnership.Kind.CONTENT_CLASH, c.kind());
            assertEquals("/clash", c.path());
            assertEquals(List.of("blog:a", "shop:b"), c.claims(), "both claimants named, sorted");
        }
    }

    @Test
    void aConflictFreeSiteReportsNothing(@TempDir Path tmp) throws IOException {
        PathOwnership ownership = new PathOwnership(pages(tmp), new ContentCatalog(new ContentSourceRegistry(
                List.of(fixed("blog", entry("my-post", "/blog/my-post", Map.of()))))));
        assertTrue(ownership.conflicts(SITE).isEmpty());
    }

    @Test
    void contentClaimsAnswerThePageEditorsQuestion(@TempDir Path tmp) throws IOException {
        PathOwnership ownership = new PathOwnership(pages(tmp), new ContentCatalog(new ContentSourceRegistry(
                List.of(fixed("blog", entry("my-post", "/about", Map.of()))))));

        assertEquals(List.of("blog:my-post"), ownership.contentClaims(SITE, "/about", EN),
                "the page editor learns whose content its path shadows");
        assertTrue(ownership.contentClaims(SITE, "/elsewhere", EN).isEmpty());
        assertEquals("about", ownership.owningPage(SITE, "/acerca", ES).orElseThrow(),
                "the reverse advisory learns which page owns a path (localized slug, its locale)");
        assertTrue(ownership.owningPage(SITE, "/acerca", EN).isEmpty(),
                "a localized slug never answers for another locale");
    }
}
