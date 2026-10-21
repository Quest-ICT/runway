package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import mx.quest.runway.i18n.I18nProperties;
import mx.quest.runway.i18n.SiteAwareMessageSource;
import mx.quest.runway.page.LoadedSiteInputs;
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
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * The same invariant as for templates: whatever an ETag is served with must be the body it describes.
 * Two inputs read through caches broke it after an on-disk edit —
 * <ul>
 *   <li>the <b>i18n bundles</b>: the ETag folded the files while the message source re-read them only every
 *       {@code runway.i18n.cache-seconds} (5 s by default; never, for a same-size edit inside one timestamp tick), so
 *       the old text went out under the new ETag. Now the bundles are re-checked on every page request and the ETag
 *       folds what the source has loaded: an edit is live at once, in the ETag and the body together;</li>
 *   <li>{@code site.yml}: the ETag folded the file while the render kept the cached site record until a site reload.
 *       Now the ETag folds the record: a hand edit changes neither until the reload applies it, then both.</li>
 * </ul>
 * Every test runs the real render path over a cache-ON engine, with the production default {@code cache-seconds}.
 */
class ConfigOnDiskEditTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final FileTime AN_HOUR_AGO = FileTime.from(Instant.now().minusSeconds(3600));

    private record Rig(PageController controller, StorageLayout layout, SiteResolver sites, SiteStore siteStore) {
        Path i18n(String siteId) {
            return layout.siteDir(siteId).resolve("i18n");
        }

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

    private static void settle(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                Files.setLastModifiedTime(f, AN_HOUR_AGO);
            }
        }
        Files.setLastModifiedTime(dir, AN_HOUR_AGO);
    }

    private static Rig rig(Path tmp, String... siteIds) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore siteStore = new SiteStore(layout, yaml);
        SiteResolver siteResolver = new SiteResolver(siteStore, TestPlatformSettings.empty());

        // The production message source, with the production default re-check interval (5 s).
        SiteAwareMessageSource messages = new SiteAwareMessageSource(siteResolver, layout, new I18nProperties());
        GenericApplicationContext appContext = new GenericApplicationContext();
        appContext.refresh();
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new SiteTemplateResolver(layout));
        engine.setTemplateEngineMessageSource(messages);

        LoadedSiteInputs loaded = new LoadedSiteInputs(messages, null, null, null, null, null, null);
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
                new PageValidatorService(layout, null, null, null, null, loaded),
                new PageRenderer(engine),
                new ErrorPages(layout, new PageRenderer(engine)), new RunwayHttpCacheProperties());

        for (String siteId : siteIds) {
            siteStore.save(new Site(siteId, "Name-" + siteId + "-1", List.of(siteId + ".test"), EN, List.of(EN),
                    "https://" + siteId + ".test", SiteStatus.ACTIVE));
            Path t = layout.siteDir(siteId).resolve("templates");
            write(t.resolve("_master.html"), "<!DOCTYPE html><html><head><title>M</title></head><body>"
                    + "<main id=\"content\">m</main></body></html>");
            write(t.resolve("about.html"), "<!DOCTYPE html><html><head><title>A</title></head><body>"
                    + "<main id=\"content\"><p id=\"g\">G</p><p id=\"n\">N</p></main></body></html>");
            write(t.resolve("about.th.xml"), "<?xml version=\"1.0\"?><thlogic>"
                    + "<attr sel=\"#g\" th:text=\"#{greeting}\"/><attr sel=\"#n\" th:text=\"${site.displayName}\"/>"
                    + "</thlogic>");
            write(t.resolve("about.meta.yml"), "path: /about\nlocales: [en]\ntitle:\n  en: About\n");
            settle(t);
            write(layout.siteDir(siteId).resolve("i18n").resolve("messages.properties"),
                    "greeting=Hello-" + siteId + "-1\n");
            settle(layout.siteDir(siteId).resolve("i18n"));
        }
        return new Rig(controller, layout, siteResolver, siteStore);
    }

    private static ResponseEntity<String> ok(Rig rig, String siteId) {
        ResponseEntity<String> response = rig.serve(siteId);
        assertEquals(200, response.getStatusCode().value());
        return response;
    }

    private static void assertNewPair(ResponseEntity<String> before, ResponseEntity<String> after,
                                      String oldText, String newText) {
        assertNotEquals(before.getHeaders().getETag(), after.getHeaders().getETag(), "the change moved the ETag");
        assertTrue(after.getBody().contains(newText), "and came with the new body: " + after.getBody());
        assertFalse(after.getBody().contains(oldText), after.getBody());
    }

    // ---- i18n bundles: live, in the ETag and the body together -----------------------------------------

    @Test
    void aBundleEditedOnDiskIsServedAtOnceUnderTheEtagThatDescribesIt(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        ResponseEntity<String> before = ok(rig, "acme");
        assertTrue(before.getBody().contains("Hello-acme-1"), before.getBody());
        assertEquals(before.getHeaders().getETag(), ok(rig, "acme").getHeaders().getETag(), "stable while unchanged");

        write(rig.i18n("acme").resolve("messages.properties"), "greeting=Hello-acme-2\n"); // same size

        ResponseEntity<String> after = rig.serve("acme", before.getHeaders().getETag());
        assertEquals(200, after.getStatusCode().value(), "the old ETag no longer matches");
        assertNewPair(before, after, "Hello-acme-1", "Hello-acme-2");
        assertEquals(304, rig.serve("acme", after.getHeaders().getETag()).getStatusCode().value(),
                "the new pair revalidates");
    }

    /** Two same-size edits inside one timestamp tick: the old source never re-read such an edit at all. */
    @Test
    void aSameSizeBundleEditWithinOneTimestampTickIsStillSeen(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme");
        Path bundle = rig.i18n("acme").resolve("messages.properties");
        write(bundle, "greeting=Hello-acme-1\n"); // freshly written: modified "now" (racily new)
        FileTime tick = Files.getLastModifiedTime(bundle);
        ResponseEntity<String> before = ok(rig, "acme");

        write(bundle, "greeting=Hello-acme-9\n");
        Files.setLastModifiedTime(bundle, tick);

        assertNewPair(before, ok(rig, "acme"), "Hello-acme-1", "Hello-acme-9");
    }

    @Test
    void aBundleEditInOneSiteLeavesAnotherSitesEtagAlone(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme", "beta");
        ResponseEntity<String> acme = ok(rig, "acme");
        ResponseEntity<String> beta = ok(rig, "beta");

        write(rig.i18n("acme").resolve("messages.properties"), "greeting=Hello-acme-2\n");

        assertNewPair(acme, ok(rig, "acme"), "Hello-acme-1", "Hello-acme-2");
        ResponseEntity<String> betaAfter = ok(rig, "beta");
        assertEquals(beta.getHeaders().getETag(), betaAfter.getHeaders().getETag());
        assertEquals(beta.getBody(), betaAfter.getBody());
    }

    // ---- site.yml: applied by the reload, and the ETag moves with it ----------------------------------

    @Test
    void aSiteYmlHandEditMovesNeitherTheEtagNorTheBodyUntilTheReload(@TempDir Path tmp) throws IOException {
        Rig rig = rig(tmp, "acme", "beta");
        ResponseEntity<String> before = ok(rig, "acme");
        ResponseEntity<String> beta = ok(rig, "beta");
        assertTrue(before.getBody().contains("Name-acme-1"), before.getBody());

        Path siteYml = rig.layout().siteDir("acme").resolve("site.yml");
        write(siteYml, Files.readString(siteYml).replace("Name-acme-1", "Name-acme-2"));

        ResponseEntity<String> unapplied = rig.serve("acme", before.getHeaders().getETag());
        assertEquals(304, unapplied.getStatusCode().value(),
                "not applied yet: the render still reads the loaded record, so the old ETag is still true");
        assertTrue(ok(rig, "acme").getBody().contains("Name-acme-1"));

        rig.siteStore().evict("acme"); // what the site reload does to the record
        ResponseEntity<String> applied = rig.serve("acme", before.getHeaders().getETag());
        assertEquals(200, applied.getStatusCode().value());
        assertNewPair(before, applied, "Name-acme-1", "Name-acme-2");
        assertEquals(beta.getHeaders().getETag(), ok(rig, "beta").getHeaders().getETag(), "the other site is untouched");
    }
}
