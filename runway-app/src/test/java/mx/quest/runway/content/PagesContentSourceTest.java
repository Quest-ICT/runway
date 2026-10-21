package mx.quest.runway.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PagesContentSource} — kernel pages projected onto the content noun (no-two-class-system): published
 * pages enumerate with their dispatch facts intact (paths, locales, localized slugs) plus the mtime-derived
 * {@code updatedAt} (the sitemap's {@code <lastmod>} pages never had); drafts stay invisible; {@code resolve}
 * agrees with the page store's own lookup.
 */
class PagesContentSourceTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");
    private static final SiteView SITE = new SiteView("acme", "Acme", EN, List.of(EN, ES), null);

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static PagesContentSource source(Path tmp) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        Path templates = layout.siteDir("acme").resolve("templates");
        write(templates.resolve("about.meta.yml"),
                "path: /about\nlocalizedPaths:\n  es: /acerca\nlocales: [en, es]\ntitle:\n  en: About\n  es: Acerca\n");
        write(templates.resolve("about.html"), "<html><body>about</body></html>");
        write(templates.resolve("draft.meta.yml"), "path: /draft\ndraft: true\n");
        return new PagesContentSource(new PageStore(layout, new YamlStore()), layout);
    }

    @Test
    void enumeratesPublishedPagesWithDispatchFactsAndLastmod(@TempDir Path tmp) throws IOException {
        List<ContentRef> refs = source(tmp).enumerate(SITE);

        assertEquals(1, refs.size(), "the draft page is NOT enumerated");
        ContentRef about = refs.get(0);
        assertEquals("about", about.id());
        assertEquals("/about", about.pathFor(EN));
        assertEquals("/acerca", about.pathFor(ES), "localized slugs survive the projection");
        assertEquals("Acerca", about.titleFor(ES));
        assertTrue(about.published());
        assertEquals("__site__/acme/about", about.bodyTemplate(), "the page's own leaf template");
        assertNotNull(about.updatedAt(), "pages gain <lastmod> through the projection (file mtimes)");
    }

    @Test
    void resolveAgreesWithThePageStoreLookup(@TempDir Path tmp) throws IOException {
        PagesContentSource pages = source(tmp);
        assertEquals("about", pages.resolve(SITE, "/acerca", ES).orElseThrow().id(), "localized slug, its locale");
        assertEquals(Optional.empty(), pages.resolve(SITE, "/acerca", EN),
                "a localized slug never doubles as another locale's URL");
        assertEquals(Optional.empty(), pages.resolve(SITE, "/draft", EN), "drafts never resolve");
    }

    @Test
    void registersAsTheBuiltInSourceAndIsSkippedByTheRouter(@TempDir Path tmp) throws IOException {
        PagesContentSource pages = source(tmp);
        ContentSourceRegistry registry = new ContentSourceRegistry(List.of(pages));
        assertTrue(registry.byKey(PagesContentSource.KEY).isPresent());
        ContentRouter router = new ContentRouter(registry);
        assertTrue(!router.hasVirtualSources(), "only the pages projection registered ⇒ routing skips the seam");
        assertEquals(Optional.empty(), router.resolve(SITE, "/about", EN),
                "the router never routes through the pages projection — PageStore is consulted first, directly");
    }

    @Test
    void aPageWithoutLocalizedPathsProjectsItsDefaultPathEverywhere(@TempDir Path tmp) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        write(layout.siteDir("acme").resolve("templates").resolve("plain.meta.yml"),
                "path: /plain\nlocales: [en, es]\ntitle:\n  en: Plain\n");
        PagesContentSource pages = new PagesContentSource(new PageStore(layout, new YamlStore()), layout);

        ContentRef plain = pages.enumerate(SITE).get(0);
        assertEquals("/plain", plain.pathFor(EN));
        assertEquals("/plain", plain.pathFor(ES), "the Page.pathFor fallback reproduced exactly");
        assertEquals(Map.of(), plain.localizedPaths());
    }

    @Test
    void indexableTextServesTheRawPageBodyAndDegradesToEmpty(@TempDir Path tmp) throws IOException {
        PagesContentSource pages = source(tmp);

        assertTrue(pages.capabilities().has(mx.quest.runway.spi.ContentSource.CAP_INDEXABLE_TEXT),
                "the pages projection opts into the search seam");
        assertEquals("about", pages.indexableText(SITE, "about", EN),
                "a page without a content slot indexes its body's inner HTML — engines strip the markup");
        assertEquals("", pages.indexableText(SITE, "draft", EN),
                "a page with no .html body indexes as metadata only, never an error");
        assertEquals("", pages.indexableText(SITE, "no-such-page", EN), "an unknown id degrades to empty");
    }

    /**
     * A report from a live deployment (2026-10-20): a result snippet read "…Nocturne Coffee Roasters (Demo) N
     * Nocturne Coffees
     * Subscriptions Cafés Journal About Go EN ES FR Shop coffee Home / Coffees /…" because the whole designer file —
     * head, header, navigation, language switcher, footer — was indexed. Only the content slot is the page's own.
     */
    @Test
    void indexableTextIsOnlyTheContentSlotNeverTheChrome(@TempDir Path tmp) throws IOException {
        PagesContentSource pages = source(tmp);
        write(tmp.resolve("sites/acme/templates/ethiopia.meta.yml"), "path: /coffees/ethiopia\n");
        write(tmp.resolve("sites/acme/templates/ethiopia.html"), """
                <!DOCTYPE html>
                <html lang="en"><head><title>Nocturne Coffee Roasters (Demo)</title>
                <style>.hero{color:red}</style><script>var chromeScript = 1;</script></head>
                <body>
                <header id="site-header"><a class="brand">N Nocturne</a>
                  <nav><a href="/coffees">Coffees</a> <a href="/subscriptions">Subscriptions</a> <a>Cafés</a>
                  <form><input name="q"><button>Go</button></form> <a>EN</a> <a>ES</a> <a>FR</a></nav></header>
                <nav class="breadcrumb">Home / Coffees /</nav>
                <main id="content">
                  <h1 id="title">Ethiopia Guji</h1>
                  <p id="notes">Bright, floral, with <em>bergamot</em> and peach.</p>
                  <script>var inlineWidget = "code, not prose";</script>
                  <style>.notes{margin:0}</style>
                </main>
                <footer id="site-footer">© Nocturne Coffee Roasters</footer>
                <div id="site-scripts"><script src="/js/site.js"></script></div>
                </body></html>
                """);

        String text = pages.indexableText(SITE, "ethiopia", EN);

        assertTrue(text.contains("Ethiopia Guji"), text);
        assertTrue(text.contains("Bright, floral, with <em>bergamot</em> and peach."), "the slot's own markup: " + text);
        for (String chrome : List.of("Nocturne", "Subscriptions", "Cafés", "Go", "FR", "Home / Coffees", "©",
                "site.js", "chromeScript", "inlineWidget", ".hero", ".notes", "<main", "<title")) {
            assertTrue(!text.contains(chrome), "chrome or code must not be indexed: '" + chrome + "' in " + text);
        }
    }

    @Test
    void contentSlotFallsBackToTheBodyThenToTheFile() {
        assertEquals("<p>Body only</p>",
                PagesContentSource.contentSlot("<html><head><title>T</title></head><body><p>Body only</p></body></html>"),
                "no #content: the body's inner HTML (the head never)");
        assertEquals("plain words", PagesContentSource.contentSlot("plain words"), "a bare text file is its own body");
        assertEquals("", PagesContentSource.contentSlot("<html><body><main id=\"content\"></main><p>x</p></body></html>"),
                "an empty slot has nothing to index; it does not fall back to the chrome around it");
        assertEquals("<b>slot</b>",
                PagesContentSource.contentSlot("<div><section id=\"content\"><b>slot</b></section>nav</div>"),
                "the slot is found by its id, whatever the element");
    }

    @Test
    void indexableTextRefusesAnUnsafeIdBeforeTouchingTheFilesystem(@TempDir Path tmp) throws IOException {
        PagesContentSource pages = source(tmp);
        // Defense in depth: even though ids reaching this method come from enumerate(), the file-reading seam
        // guards its own identifier (mirroring PageStore) — a traversal id never resolves against the tree.
        assertEquals("", pages.indexableText(SITE, "../../../etc/passwd", EN), "a traversal id is refused");
        assertEquals("", pages.indexableText(SITE, "a/b", EN), "a path-separator id is refused");
    }
}
