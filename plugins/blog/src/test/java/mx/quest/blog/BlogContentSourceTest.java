package mx.quest.blog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.SiteEventListener;
import mx.quest.runway.spi.testkit.EventDispatch;
import mx.quest.runway.spi.testkit.FakeContentCatalog;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link BlogContentSource} against the ContentSource contract: published-only metadata enumeration, the O(1) URL
 * index (prefix + localized slugs + custom paths), Markdown bodies fetched only via {@code body()}, the per-site enable
 * switch and prefix setting (through {@code PluginSettings}, with the kernel's path rule) — and its index invalidation
 * as a {@code SOURCE}-phase {@link SiteEventListener}, driven by the testkit's {@link EventDispatch} in the kernel's
 * delivery order.
 */
class BlogContentSourceTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");
    private static final SiteView SITE = new SiteView("acme", "Acme", EN, List.of(EN, ES), null);

    private record Fixture(BlogStore store, BlogContentSource source, FakePluginSettings settings,
                           EventDispatch events) {
    }

    private static Fixture fixture(Path tmp, Map<String, Object> cfg) throws IOException {
        Files.createDirectories(tmp.resolve("acme"));
        BlogStore store = new BlogStore(new TempDirSiteFiles(tmp, "blog"));
        FakePluginSettings settings = new FakePluginSettings()
                .defaults(Map.of("enabled", true, "prefix", "/blog", "template", "entry-classic"))
                .site("acme", cfg);
        FakeContentCatalog catalog = new FakeContentCatalog().site("acme").route("/sitemap.xml");
        BlogContentSource source = new BlogContentSource(store, settings, catalog);
        EventDispatch events = new EventDispatch("blog", Set.of(BlogContentSource.KEY)).listener(source);
        return new Fixture(store, source, settings, events);
    }

    private static BlogEntry entry(String id, boolean published) {
        return new BlogEntry(id, Map.of("en", "T-" + id, "es", "ES-" + id), Map.of("en", "D"),
                List.of("en", "es"), null, Map.of("es", id + "-es"), null, published, "2026-07-17", null, null);
    }

    private static Map<String, ContentRef> byId(BlogContentSource source) {
        Map<String, ContentRef> out = new HashMap<>();
        for (ContentRef r : source.enumerate(SITE)) {
            out.put(r.id(), r);
        }
        return out;
    }

    @Test
    void enumeratesPublishedEntriesAsRefsUnderThePrefix(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        f.store().save("acme", entry("hello", true), Map.of("en", "# Hi"));
        f.store().save("acme", entry("draft", false), null);

        List<ContentRef> refs = f.source().enumerate(SITE);
        assertEquals(1, refs.size(), "unpublished entries are never enumerated (draft-leak fail-closed)");
        ContentRef ref = refs.get(0);
        assertEquals("hello", ref.id());
        assertEquals("/blog/hello", ref.pathFor(EN), "default prefix + slug");
        assertEquals("/blog/hello-es", ref.pathFor(ES), "localized slug under the same prefix");
        assertEquals("T-hello", ref.titleFor(EN));
        assertEquals("D", ref.descriptionFor(EN));
        assertEquals(List.of(EN, ES), ref.locales());
        assertEquals("__plugin__/blog/entry-classic", ref.bodyTemplate(), "the default template setting");
        assertTrue(ref.published());
        assertNotNull(ref.updatedAt(), "file-time-derived — a body edit moves the ETag automatically");
        assertEquals(BlogContentSource.KEY, f.source().key());
    }

    @Test
    void entriesWrittenBy131EnumerateAndResolveUnchanged(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        BlogStoreTest.install131(tmp, "acme");
        Map<String, ContentRef> refs = byId(f.source());
        assertEquals(Set.of("hello-world", "no-author", "launch"), refs.keySet(), "the draft stays out");
        assertEquals("/blog/hello-world", refs.get("hello-world").pathFor(EN));
        assertEquals("/blog/hola-mundo", refs.get("hello-world").pathFor(ES));
        assertEquals("Jane Doe", refs.get("hello-world").author());
        assertNull(refs.get("no-author").author(), "the entry with no author stays unattributed");
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), refs.get("no-author").publishedAt());
        assertEquals("/announcing-v2", refs.get("launch").pathFor(EN), "the custom path survives");
        assertEquals("launch", f.source().resolve(SITE, "/announcing-v2", EN).orElseThrow().id());
        assertTrue(f.source().body(SITE, "hello-world", EN).bodyHtml().contains("<strong>bold</strong>"));
    }

    @Test
    void resolvesThroughTheIndexIncludingCustomPaths(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        f.store().save("acme", entry("hello", true), null);
        f.store().save("acme", new BlogEntry("launch", Map.of("en", "Launch"), Map.of(), List.of("en"),
                null, Map.of(), "/announcing-v2", true, null, null, null), null);

        assertEquals("hello", f.source().resolve(SITE, "/blog/hello", EN).orElseThrow().id());
        assertEquals("hello", f.source().resolve(SITE, "/blog/hello-es", ES).orElseThrow().id(),
                "the localized slug resolves under its own locale");
        assertEquals(Optional.empty(), f.source().resolve(SITE, "/blog/hello-es", EN),
                "a localized slug never doubles as another locale's URL");
        assertEquals("launch", f.source().resolve(SITE, "/announcing-v2", EN).orElseThrow().id(),
                "a custom path escapes the prefix (the arbitrary-paths capability)");
        assertEquals(Optional.empty(), f.source().resolve(SITE, "/blog/nope", EN));
        assertTrue(f.source().capabilities().has(ContentSource.CAP_ARBITRARY_PATHS));
    }

    @Test
    void itIsASourcePhaseListener(@TempDir Path tmp) throws IOException {
        assertEquals(SiteEventListener.Phase.SOURCE, fixture(tmp, Map.of()).source().phase(),
                "its own index must drop BEFORE sitemap/search consumers re-read the listing");
    }

    @Test
    void itsOwnContentChangeDropsTheIndexAndTheRefs(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        f.store().save("acme", entry("hello", true), null);
        f.source().resolve(SITE, "/blog/hello", EN);
        assertNotNull(f.source().cachedIndex("acme"), "index built lazily on first resolve");
        assertNotNull(f.source().cachedRefs("acme"));

        // What the console calls after a save — delivered back as a CONTENT change for this source's key.
        f.store().save("acme", entry("second", true), null);
        f.events().changed("acme", BlogContentSource.KEY, "second");
        assertNull(f.source().cachedIndex("acme"), "own-content change drops the index");
        assertNull(f.source().cachedRefs("acme"), "…and the enumerated refs");
        assertEquals("second", f.source().resolve(SITE, "/blog/second", EN).orElseThrow().id(),
                "the next probe sees the new entry");
        assertTrue(f.events().failures().isEmpty());
    }

    @Test
    void aReplayedChangeFromAPeerNodeDropsItToo(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        f.store().save("acme", entry("hello", true), null);
        f.source().resolve(SITE, "/blog/hello", EN);
        f.events().catalog(new CatalogChange("acme", CatalogChange.Cause.CONTENT, BlogContentSource.KEY, null,
                "blog", List.of(), List.of(), false));
        assertNull(f.source().cachedIndex("acme"), "local == false still converges this node's index");
    }

    @Test
    void otherChangesLeaveTheIndexAlone(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        f.store().save("acme", entry("hello", true), null);
        f.source().resolve(SITE, "/blog/hello", EN);
        Map<String, String> built = f.source().cachedIndex("acme");

        f.events().page("acme", "about", List.of(), List.of());
        f.events().catalog(new CatalogChange("acme", CatalogChange.Cause.CONTENT, "products", "x", "shop",
                List.of(), List.of(), true));
        f.events().settings("acme", "search");
        f.events().catalog(new CatalogChange("globex", CatalogChange.Cause.CONTENT, BlogContentSource.KEY, "x",
                "blog", List.of(), List.of(), true));
        assertTrue(built == f.source().cachedIndex("acme"),
                "a page write, another source's change, another plugin's settings, another site: kept");
    }

    @Test
    void itsOwnSettingsSaveDropsTheIndexAndRekeysEveryUrl(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        f.store().save("acme", entry("hello", true), null);
        assertEquals("/blog/hello", f.source().enumerate(SITE).get(0).pathFor(EN));
        f.source().resolve(SITE, "/blog/hello", EN);

        f.settings().site("acme", Map.of("prefix", "/news"));
        f.events().settings("acme", "blog");
        assertNull(f.source().cachedIndex("acme"), "a prefix change re-keys every URL — dropped");
        assertEquals("/news/hello", f.source().enumerate(SITE).get(0).pathFor(EN));
        assertEquals("hello", f.source().resolve(SITE, "/news/hello", EN).orElseThrow().id());
        assertEquals(Optional.empty(), f.source().resolve(SITE, "/blog/hello", EN), "the old URL is gone");
    }

    @Test
    void everyLifecycleEventDropsTheIndex(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        f.store().save("acme", entry("hello", true), null);
        for (SiteLifecycleEvent.Kind kind : SiteLifecycleEvent.Kind.values()) {
            f.source().resolve(SITE, "/blog/hello", EN);
            assertNotNull(f.source().cachedIndex("acme"));
            f.events().lifecycle("acme", kind, kind == SiteLifecycleEvent.Kind.RESTORED);
            assertNull(f.source().cachedIndex("acme"), kind + " drops it (a restore replaced the files wholesale)");
        }
    }

    @Test
    void bodyRendersMarkdownAndCarriesThePublicationDate(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        f.store().save("acme", entry("hello", true), Map.of("en", "# Title\n\nSome **bold** text."));

        ContentBody body = f.source().body(SITE, "hello", EN);
        assertTrue(body.bodyHtml().contains("<h1>Title</h1>"), "markdown heading rendered");
        assertTrue(body.bodyHtml().contains("<strong>bold</strong>"), "markdown emphasis rendered");
        assertEquals("2026-07-17", body.attributes().get("publishedOn"));
        assertEquals("", f.source().body(SITE, "hello", ES).bodyHtml(), "no es body → empty (soft)");
    }

    @Test
    void aCorruptEntryNeverBlanksTheRestOfTheBlog(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        f.store().save("acme", entry("good", true), Map.of("en", "fine"));
        Path corrupt = tmp.resolve("acme/plugins/blog/entries/broken");
        Files.createDirectories(corrupt);
        Files.writeString(corrupt.resolve("entry.yml"), "title: [unclosed");

        List<ContentRef> refs = f.source().enumerate(SITE);
        assertEquals(1, refs.size(), "the good entry survives its corrupt sibling");
        assertEquals("good", f.source().resolve(SITE, "/blog/good", EN).orElseThrow().id());
    }

    @Test
    void twoEntriesClaimingOnePathResolveDeterministically(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        f.store().save("acme", new BlogEntry("one", Map.of("en", "One"), Map.of(), List.of("en"),
                "shared", Map.of(), null, true, "2026-01-02", null, null), null);
        f.store().save("acme", new BlogEntry("two", Map.of("en", "Two"), Map.of(), List.of("en"),
                "shared", Map.of(), null, true, "2026-01-01", null, null), null);
        String winner = f.source().resolve(SITE, "/blog/shared", EN).orElseThrow().id();
        f.events().changed("acme", BlogContentSource.KEY, null);
        assertEquals(winner, f.source().resolve(SITE, "/blog/shared", EN).orElseThrow().id(),
                "one deterministic winner, the same after a rebuild");
    }

    @Test
    void aDisabledSiteServesAndListsNothing(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of("enabled", false));
        f.store().save("acme", entry("hello", true), null);
        assertTrue(f.source().enumerate(SITE).isEmpty());
        assertEquals(Optional.empty(), f.source().resolve(SITE, "/blog/hello", EN));
        assertTrue(!f.source().settings("acme").enabled());
    }

    @Test
    void aCustomPrefixRekeysEveryUrl(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of("prefix", "news/"));
        f.store().save("acme", entry("hello", true), null);
        assertEquals("/news/hello", f.source().enumerate(SITE).get(0).pathFor(EN), "normalized: leading /, no trailing");
        assertEquals("hello", f.source().resolve(SITE, "/news/hello", EN).orElseThrow().id());
    }

    @Test
    void theRootPrefixServesEntriesAtTheTopLevel(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of("prefix", "/"));
        f.store().save("acme", entry("hello", true), null);
        assertEquals("/hello", f.source().enumerate(SITE).get(0).pathFor(EN), "never '//hello'");
    }

    @Test
    void aPrefixTheKernelWouldNotServeFallsBackToBlog(@TempDir Path tmp) throws IOException {
        for (String bad : List.of("/__admin", "/app", "/actuator", "/a|b", "/x/../y")) {
            Fixture f = fixture(tmp.resolve("p" + Math.abs(bad.hashCode())), Map.of("prefix", bad));
            assertEquals("/blog", f.source().settings("acme").prefix(),
                    bad + ": entries under it would silently vanish — the default is used instead");
        }
        for (String ok : List.of("/sitemap.xml", "/login", "/news/archive")) {
            assertEquals(ok, fixture(tmp.resolve("ok" + Math.abs(ok.hashCode())), Map.of("prefix", ok))
                    .source().settings("acme").prefix(), ok + ": only the path an entry would get is checked");
        }
    }

    @Test
    void theTemplateSettingFailsClosedToClassic(@TempDir Path tmp) throws IOException {
        assertEquals("__plugin__/blog/entry-minimal",
                fixture(tmp.resolve("a"), Map.of("template", "entry-minimal")).source().settings("acme").bodyTemplate());
        assertEquals("__plugin__/blog/entry-classic",
                fixture(tmp.resolve("b"), Map.of("template", "../../evil")).source().settings("acme").bodyTemplate(),
                "an unknown template name never reaches the template lookup");
    }

    @Test
    void indexableTextIsRenderedHtmlPerLocaleSoEnginesStripCleanly(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        f.store().save("acme", entry("hello", true),
                Map.of("en", "# Hi\n\nRunway makes [hosting](/x) simple.", "es", "# Hola"));

        assertTrue(f.source().capabilities().has(ContentSource.CAP_INDEXABLE_TEXT),
                "the blog opts into the search seam — entries are first-class search citizens");
        String en = f.source().indexableText(SITE, "hello", EN);
        assertTrue(en.contains("<h1>Hi</h1>"), "heading rendered, not left as '# Hi': " + en);
        assertTrue(en.contains("<a href=\"/x\">hosting</a>"), "link rendered, not left as Markdown syntax: " + en);
        assertTrue(f.source().indexableText(SITE, "hello", ES).contains("<h1>Hola</h1>"), "per-locale material");
        assertEquals("", f.source().indexableText(SITE, "hello", Locale.FRENCH),
                "an untranslated locale indexes as metadata only, never an error");
        assertEquals("", f.source().indexableText(SITE, "no-such-entry", EN), "an unknown id degrades to empty");
    }

    /** Each entry carries ITS OWN publication date across the SPI boundary (not one shared schema block). */
    @Test
    void eachEntryCarriesItsOwnPublicationDate(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        f.store().save("acme", new BlogEntry("older", Map.of("en", "Older"), Map.of(), List.of("en"),
                null, Map.of(), null, true, "2026-03-04", null, null), null);
        f.store().save("acme", new BlogEntry("newer", Map.of("en", "Newer"), Map.of(), List.of("en"),
                null, Map.of(), null, true, "2026-09-10", null, null), null);
        Map<String, ContentRef> refs = byId(f.source());
        assertEquals(Instant.parse("2026-03-04T00:00:00Z"), refs.get("older").publishedAt());
        assertEquals(Instant.parse("2026-09-10T00:00:00Z"), refs.get("newer").publishedAt());
        assertNotEquals(refs.get("older").publishedAt(), refs.get("newer").publishedAt());
    }

    /** A free-form publishedOn yields NO date rather than a guess (a wrong datePublished is worse than none). */
    @Test
    void anUnparseablePublishedOnYieldsNoDateRatherThanAGuess(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        f.store().save("acme", new BlogEntry("freeform", Map.of("en", "Freeform"), Map.of(), List.of("en"),
                null, Map.of(), null, true, "last spring", null, null), null);
        f.store().save("acme", new BlogEntry("undated", Map.of("en", "Undated"), Map.of(), List.of("en"),
                null, Map.of(), null, true, null, null, null), null);
        assertNull(byId(f.source()).get("freeform").publishedAt(), "a non-date must not become a fabricated instant");
        assertNull(byId(f.source()).get("undated").publishedAt());
    }

    /** Each entry carries ITS OWN author across the SPI boundary, so structured data attributes the right person. */
    @Test
    void eachEntryCarriesItsOwnAuthor(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        f.store().save("acme", new BlogEntry("ada", Map.of("en", "Ada"), Map.of(), List.of("en"),
                null, Map.of(), null, true, "2026-03-04", "Jane Doe", null), null);
        f.store().save("acme", new BlogEntry("anon", Map.of("en", "Anon"), Map.of(), List.of("en"),
                null, Map.of(), null, true, "2026-03-04", "   ", null), null);
        assertEquals("Jane Doe", byId(f.source()).get("ada").author());
        assertNull(byId(f.source()).get("anon").author(), "a blank byline must normalise to absent");
    }

    /** The byline reaches the RENDER attributes too — and is absent, not empty, when the entry has none. */
    @Test
    void theAuthorReachesTheRenderAttributes(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        f.store().save("acme", new BlogEntry("ada", Map.of("en", "Ada"), Map.of(), List.of("en"),
                null, Map.of(), null, true, "2026-03-04", "Jane Doe", null), Map.of("en", "# Hello"));
        f.store().save("acme", new BlogEntry("anon", Map.of("en", "Anon"), Map.of(), List.of("en"),
                null, Map.of(), null, true, null, null, null), Map.of("en", "# Hi"));
        assertEquals("Jane Doe", f.source().body(SITE, "ada", EN).attributes().get("author"));
        assertEquals(Map.of(), f.source().body(SITE, "anon", EN).attributes(),
                "an unattributed, undated entry puts nothing into the render attributes");
    }

    @Test
    void publicPathsFollowTheRoutingSemantics(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, Map.of());
        BlogEntry custom = new BlogEntry("launch", Map.of("en", "L"), Map.of(), List.of("en", "es"), "v2",
                Map.of("es", "lanzamiento"), "/announcing-v2", true, null, null, null);
        assertEquals("/announcing-v2", f.source().publicPath("acme", custom));
        assertEquals("/blog/lanzamiento", f.source().publicPath("acme", custom, "es"), "the localized slug");
        assertEquals("/announcing-v2", f.source().publicPath("acme", custom, "en"));
        BlogEntry plain = new BlogEntry("plain", Map.of("en", "P"), Map.of(), List.of("en"), "slugged",
                Map.of(), null, true, null, null, null);
        assertEquals("/blog/slugged", f.source().publicPath("acme", plain));
    }
}
