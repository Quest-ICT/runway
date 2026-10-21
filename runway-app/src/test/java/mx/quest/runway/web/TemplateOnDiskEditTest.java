package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import mx.quest.runway.page.PageRouting;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageValidatorService;
import mx.quest.runway.page.RenderModelBuilder;
import mx.quest.runway.platform.TestPlatformSettings;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.widget.ContentWidgetRegistry;
import mx.quest.runway.widget.WidgetModelBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.thymeleaf.cache.TemplateCacheKey;
import org.thymeleaf.engine.TemplateModel;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * A template file edited <b>on disk</b> (no console, no event, no reload) must never be served from the previous
 * parse under the new ETag. The ETag folds the files' bytes on every request, so it moves the instant the file
 * changes; the parsed-template cache must move with it, or a client stores the old body under the new validator
 * and keeps revalidating it to {@code 304} long after the cache is finally cleared.
 *
 * <p>Every test runs the real render path ({@link PageController#serve} over a {@link SpringTemplateEngine} with
 * its standard cache ON) and first proves the entry IS cached — so a pass can never come from the cache being off.
 */
class TemplateOnDiskEditTest {

    private static final Locale EN = Locale.ENGLISH;
    /** Far enough in the past that the file is not "racily new" (see {@code FileStamp}). */
    private static final FileTime AN_HOUR_AGO = FileTime.from(Instant.now().minusSeconds(3600));

    private record Rig(PageController controller, SpringTemplateEngine engine, StorageLayout layout,
                       SiteResolver sites, SiteStore siteStore) {
        Path templates(String siteId) {
            return layout.siteDir(siteId).resolve("templates");
        }

        /** Serves {@code /about} as the request of {@code siteId}'s host (the site is bound per request). */
        ResponseEntity<String> serve(String siteId, String ifNoneMatch) {
            sites.bind(siteStore.find(siteId).orElseThrow());
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/about");
            request.addPreferredLocale(EN);
            if (ifNoneMatch != null) {
                request.addHeader(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
            }
            return controller.serve(request);
        }

        ResponseEntity<String> serve(String siteId) {
            return serve(siteId, null);
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** Back-dates every file of a site's templates so the first parse is cached as settled, not racily new. */
    private static void settle(Path templates) throws IOException {
        try (Stream<Path> files = Files.list(templates)) {
            for (Path f : files.toList()) {
                Files.setLastModifiedTime(f, AN_HOUR_AGO);
            }
        }
    }

    private static Rig rig(Path tmp, String... siteIds) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore siteStore = new SiteStore(layout, yaml);
        SiteResolver siteResolver = new SiteResolver(siteStore, TestPlatformSettings.empty());

        GenericApplicationContext appContext = new GenericApplicationContext();
        appContext.refresh();
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new SiteTemplateResolver(layout));

        PageController controller = new PageController(siteResolver,
                new PageRouting(new PageStore(layout, yaml)),
                new mx.quest.runway.content.ContentRouter(
                        new mx.quest.runway.content.ContentSourceRegistry(List.of())),
                new mx.quest.runway.content.ContentCatalog(
                        new mx.quest.runway.content.ContentSourceRegistry(List.of())),
                new RenderModelBuilder(),
                new WidgetModelBuilder(new ContentWidgetRegistry(List.of()), layout,
                        new mx.quest.runway.widget.WidgetInstanceConfigService(layout,
                                new mx.quest.runway.crypto.AesGcmSecretCipher(
                                        new mx.quest.runway.security.RunwaySecurityProperties()))),
                new PageValidatorService(layout), new PageRenderer(engine),
                new ErrorPages(layout, new PageRenderer(engine)), new RunwayHttpCacheProperties());

        for (String siteId : siteIds) {
            Site site = new Site(siteId, siteId, List.of(siteId + ".test"), EN, List.of(EN),
                    "https://" + siteId + ".test", SiteStatus.ACTIVE);
            siteStore.save(site);
            Path t = layout.siteDir(siteId).resolve("templates");
            write(t.resolve("_master.html"), "<!DOCTYPE html><html><head><title>MASTER-" + siteId
                    + "-1</title></head><body><main id=\"content\">m</main></body></html>");
            write(t.resolve("_master.th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>");
            write(t.resolve("about.html"), "<!DOCTYPE html><html><head><title>A</title></head><body>"
                    + "<main id=\"content\"><h1 id=\"t\">PH</h1><p id=\"v\">BODY-" + siteId + "-1</p></main></body></html>");
            write(t.resolve("about.th.xml"), "<?xml version=\"1.0\"?><thlogic><attr sel=\"head\" "
                    + "th:replace=\"~{${master} :: head}\"/><attr sel=\"#t\" th:text=\"${pageTitle}\"/></thlogic>");
            write(t.resolve("about.meta.yml"), "path: /about\nlocales: [en]\ntitle:\n  en: About\n");
            settle(t);
        }
        return new Rig(controller, engine, layout, siteResolver, siteStore);
    }

    private static Set<String> cachedNames(SpringTemplateEngine engine) {
        return engine.getCacheManager().getTemplateCache().keySet().stream()
                .map(TemplateCacheKey::getTemplate).collect(Collectors.toSet());
    }

    private static TemplateModel cached(SpringTemplateEngine engine, String name) {
        return engine.getCacheManager().getTemplateCache().keySet().stream()
                .filter(k -> name.equals(k.getTemplate()) && k.getTemplateSelectors() == null)
                .findFirst().map(k -> engine.getCacheManager().getTemplateCache().get(k)).orElse(null);
    }

    /** Serves once and asserts the page (and its master's head, when pulled) are now in the parse cache. */
    private static ResponseEntity<String> prime(Rig rig, String siteId) {
        ResponseEntity<String> first = rig.serve(siteId);
        assertEquals(200, first.getStatusCode().value());
        Set<String> expected = Files.exists(rig.templates(siteId).resolve("about.th.xml"))
                ? Set.of(SiteTemplateResolver.PREFIX + siteId + "/about", SiteTemplateResolver.PREFIX + siteId + "/_master")
                : Set.of(SiteTemplateResolver.PREFIX + siteId + "/about");
        assertTrue(cachedNames(rig.engine()).containsAll(expected),
                "precondition: the cache is ON and holds " + expected + " — got " + cachedNames(rig.engine()));
        return first;
    }

    private static void assertSameVersion(ResponseEntity<String> before, ResponseEntity<String> after,
                                          String oldText, String newText) {
        assertNotEquals(before.getHeaders().getETag(), after.getHeaders().getETag(), "the edit moved the ETag");
        assertTrue(after.getBody().contains(newText),
                "the new ETag must come with the new body, not the previous parse: " + after.getBody());
        assertFalse(after.getBody().contains(oldText), after.getBody());
    }

    @Test
    void aPageHtmlEditedOnDiskRendersAtOnceUnderTheNewEtag(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> before = prime(rig, "acme");
        assertTrue(before.getBody().contains("BODY-acme-1"));

        String html = Files.readString(rig.templates("acme").resolve("about.html"));
        write(rig.templates("acme").resolve("about.html"), html.replace("BODY-acme-1", "BODY-acme-2"));

        assertSameVersion(before, rig.serve("acme"), "BODY-acme-1", "BODY-acme-2");
    }

    @Test
    void theMasterEditedOnDiskRendersAtOnce(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> before = prime(rig, "acme");
        assertTrue(before.getBody().contains("MASTER-acme-1"));

        String master = Files.readString(rig.templates("acme").resolve("_master.html"));
        write(rig.templates("acme").resolve("_master.html"), master.replace("MASTER-acme-1", "MASTER-acme-2"));

        assertSameVersion(before, rig.serve("acme"), "MASTER-acme-1", "MASTER-acme-2");
    }

    @Test
    void theDecoupledLogicEditedOnDiskRendersAtOnce(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> before = prime(rig, "acme");

        write(rig.templates("acme").resolve("about.th.xml"), "<?xml version=\"1.0\"?><thlogic><attr sel=\"head\" "
                + "th:replace=\"~{${master} :: head}\"/><attr sel=\"#t\" th:text=\"${pageTitle}\"/>"
                + "<attr sel=\"#v\" th:text=\"'LOGIC-2'\"/></thlogic>");

        assertSameVersion(before, rig.serve("acme"), "BODY-acme-1", "LOGIC-2");
    }

    /** The page's metadata is folded into the ETag too, so its cached record must follow the file as well. */
    @Test
    void thePageMetadataEditedOnDiskRendersAtOnce(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> before = prime(rig, "acme");
        assertTrue(before.getBody().contains(">About<"));

        write(rig.templates("acme").resolve("about.meta.yml"), "path: /about\nlocales: [en]\ntitle:\n  en: Renamed\n");

        assertSameVersion(before, rig.serve("acme"), ">About<", ">Renamed<");
    }

    @Test
    void aDecoupledLogicFileAddedOnDiskIsPickedUp(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        Path logic = rig.templates("acme").resolve("about.th.xml");
        Files.delete(logic);
        ResponseEntity<String> before = prime(rig, "acme");
        assertTrue(before.getBody().contains("PH"), "no logic file: the static heading");

        write(logic, "<?xml version=\"1.0\"?><thlogic><attr sel=\"#t\" th:text=\"${pageTitle}\"/></thlogic>");

        assertSameVersion(before, rig.serve("acme"), ">PH<", ">About<");
    }

    /**
     * Two same-size edits inside one file-system timestamp tick: the size and the modification time cannot tell
     * them apart. A file modified this recently is "racily new", so its parse is re-checked by content.
     */
    @Test
    void aSameSizeEditWithinOneTimestampTickIsStillCaught(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        Path page = rig.templates("acme").resolve("about.html");
        String html = Files.readString(page);
        write(page, html);                       // freshly written: modified "now"
        FileTime tick = Files.getLastModifiedTime(page);
        ResponseEntity<String> before = prime(rig, "acme");

        write(page, html.replace("BODY-acme-1", "BODY-acme-9")); // same length
        Files.setLastModifiedTime(page, tick);                   // ... and the same timestamp
        assertEquals(Files.size(page), html.getBytes(StandardCharsets.UTF_8).length);

        assertSameVersion(before, rig.serve("acme"), "BODY-acme-1", "BODY-acme-9");
    }

    /** A deployment that swaps the file in by rename (a new inode) with the old size and timestamp. */
    @Test
    void aFileReplacedByRenameIsPickedUpEvenWithTheSameSizeAndTimestamp(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        Path page = rig.templates("acme").resolve("about.html");
        ResponseEntity<String> before = prime(rig, "acme");

        Path staged = page.resolveSibling("about.html.new");
        write(staged, Files.readString(page).replace("BODY-acme-1", "BODY-acme-7"));
        Files.setLastModifiedTime(staged, Files.getLastModifiedTime(page));
        Files.move(staged, page, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        assertSameVersion(before, rig.serve("acme"), "BODY-acme-1", "BODY-acme-7");
    }

    @Test
    void anUnchangedPageKeepsItsCachedParseAndItsEtag(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> before = prime(rig, "acme");
        TemplateModel parsed = cached(rig.engine(), SiteTemplateResolver.PREFIX + "acme/about");

        ResponseEntity<String> again = rig.serve("acme");

        assertEquals(before.getHeaders().getETag(), again.getHeaders().getETag());
        assertEquals(before.getBody(), again.getBody());
        assertSame(parsed, cached(rig.engine(), SiteTemplateResolver.PREFIX + "acme/about"),
                "an unchanged file is served from the same cached parse — the cache is not defeated");
    }

    @Test
    void anEditToOneSiteLeavesAnotherSitesCachedParseAlone(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme", "beta");
        ResponseEntity<String> acmeBefore = prime(rig, "acme");
        ResponseEntity<String> betaBefore = prime(rig, "beta");
        TemplateModel betaParse = cached(rig.engine(), SiteTemplateResolver.PREFIX + "beta/about");

        String html = Files.readString(rig.templates("acme").resolve("about.html"));
        write(rig.templates("acme").resolve("about.html"), html.replace("BODY-acme-1", "BODY-acme-2"));

        assertSameVersion(acmeBefore, rig.serve("acme"), "BODY-acme-1", "BODY-acme-2");
        ResponseEntity<String> betaAfter = rig.serve("beta");
        assertEquals(betaBefore.getHeaders().getETag(), betaAfter.getHeaders().getETag());
        assertTrue(betaAfter.getBody().contains("BODY-beta-1") && !betaAfter.getBody().contains("acme"),
                "no cross-site bleed: " + betaAfter.getBody());
        assertSame(betaParse, cached(rig.engine(), SiteTemplateResolver.PREFIX + "beta/about"),
                "another site's warm parse is untouched by this site's edit");
    }

    @Test
    void aRevalidationWithTheOldEtagAfterAnEditGetsTheNewBody(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> before = prime(rig, "acme");
        String html = Files.readString(rig.templates("acme").resolve("about.html"));
        write(rig.templates("acme").resolve("about.html"), html.replace("BODY-acme-1", "BODY-acme-2"));

        ResponseEntity<String> after = rig.serve("acme", before.getHeaders().getETag());

        assertEquals(200, after.getStatusCode().value());
        assertSameVersion(before, after, "BODY-acme-1", "BODY-acme-2");
        // ... and the client that now holds the new pair revalidates it to 304.
        assertEquals(304, rig.serve("acme", after.getHeaders().getETag()).getStatusCode().value());
    }
}
