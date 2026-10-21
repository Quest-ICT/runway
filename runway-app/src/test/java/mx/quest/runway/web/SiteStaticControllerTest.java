package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.platform.TestPlatformSettings;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.seo.SeoConfigService;
import mx.quest.runway.seo.SiteSeoConfig;
import mx.quest.runway.storage.SiteFileStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The /static/** public resolver: serves the current host's site assets, withholds via guards. */
class SiteStaticControllerTest {

    @TempDir
    Path external;
    private StorageLayout layout;
    private SiteResolver siteResolver;
    private SiteFileStore files;
    private SeoConfigService seo;

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        layout = StorageLayout.from(props);
        SiteStore siteStore = new SiteStore(layout, new YamlStore());
        siteResolver = new SiteResolver(siteStore, TestPlatformSettings.empty());
        files = new SiteFileStore(layout);
        seo = new SeoConfigService(layout, new YamlStore());

        Site site = new Site("acme", "Acme", List.of("acme.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), "https://acme.test", SiteStatus.ACTIVE);
        siteStore.save(site);
        siteResolver.bind(site); // this request's current tenant
    }

    private SiteStaticController controller(SiteStaticGuard... guards) {
        return new SiteStaticController(siteResolver, files, List.of(guards), new RunwayHttpCacheProperties(), seo);
    }

    private static byte[] bytesOf(ResponseEntity<Resource> response) throws IOException {
        try (var in = response.getBody().getInputStream()) {
            return in.readAllBytes();
        }
    }

    @Test
    void servesAPresentAssetWithAContentTypeFromItsExtension() throws IOException {
        files.writeText("acme", "static/css/site.css", "body{color:red}", 10_000);
        ResponseEntity<Resource> response = controller().serve("/css/site.css");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.parseMediaType("text/css"), response.getHeaders().getContentType());
        assertArrayEquals("body{color:red}".getBytes(StandardCharsets.UTF_8), bytesOf(response));
        assertTrue(response.getHeaders().getCacheControl().contains("max-age=86400"),
                "the configured assets rule is applied");
    }

    @Test
    void aFontIsServedImmutableForAYearWhileMutableAssetsKeepTheDayDefault() throws IOException {
        // A web font is replaced by shipping a new file, never edited in place, so it caches forever.
        files.write("acme", "static/css/fonts/cormorant-500.woff2", new byte[] {0x77, 0x4f, 0x46, 0x32}, 10_000);
        String fontCache = controller().serve("/css/fonts/cormorant-500.woff2").getHeaders().getCacheControl();
        assertTrue(fontCache.contains("max-age=31536000") && fontCache.contains("immutable"),
                "a .woff2 font gets the year-long immutable lifetime; got: " + fontCache);

        // A plain (unfingerprinted) stylesheet stays on the mutable 1-day default — it CAN be edited in place.
        files.writeText("acme", "static/css/site.css", "body{}", 10_000);
        String cssCache = controller().serve("/css/site.css").getHeaders().getCacheControl();
        assertTrue(cssCache.contains("max-age=86400") && !cssCache.contains("immutable"),
                "css stays mutable/1-day; got: " + cssCache);
    }

    @Test
    void aDocumentNeverGetsTheImmutableLifetimeEvenWithAFontExtension() throws IOException {
        // doc/ is operator-uploaded and editable — an uploaded .woff2 there must NOT be cached immutable.
        files.write("acme", "static/doc/upload.woff2", new byte[] {1}, 10_000);
        String cache = controller().serve("/doc/upload.woff2").getHeaders().getCacheControl();
        assertTrue(cache.contains("max-age=86400") && !cache.contains("immutable"),
                "a document keeps the mutable default regardless of extension; got: " + cache);
    }

    @Test
    void anUnknownExtensionFallsBackToOctetStream() throws IOException {
        files.write("acme", "static/data/blob.xyzzy", new byte[] {1, 2, 3}, 10_000);
        ResponseEntity<Resource> response = controller().serve("/data/blob.xyzzy");
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
    }

    @Test
    void aDocumentIsServedAsANosniffAttachmentNeverInline() throws IOException {
        // An uploaded .html under doc/ must not render inline on the site origin (stored-XSS guard).
        files.writeText("acme", "static/doc/report.html", "<script>alert(1)</script>", 10_000);
        ResponseEntity<Resource> response = controller().serve("/doc/report.html");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType(), "never text/html");
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"),
                "documents download, never render inline");
    }

    @Test
    void anAbsentAssetIsA404() {
        assertEquals(HttpStatus.NOT_FOUND, controller().serve("/css/missing.css").getStatusCode());
    }

    @Test
    void aDirectoryPathIsA404NotAServe() throws IOException {
        files.writeText("acme", "static/css/site.css", "x", 10_000);
        assertEquals(HttpStatus.NOT_FOUND, controller().serve("/css").getStatusCode(), "a folder is not a file");
    }

    @Test
    void aBlankResourceIsA404() {
        assertEquals(HttpStatus.NOT_FOUND, controller().serve("/").getStatusCode());
    }

    @Test
    void whenNoSiteMatchesTheHostItIsA404() throws IOException {
        files.writeText("acme", "static/css/site.css", "x", 10_000);
        siteResolver.clear(); // e.g. an unbound host — no current tenant
        assertEquals(HttpStatus.NOT_FOUND, controller().serve("/css/site.css").getStatusCode());
    }

    @Test
    void aTraversalResourceNeverEscapesTheSiteFolder() throws IOException {
        Files.writeString(external.resolve("secret.txt"), "PRIVATE", StandardCharsets.UTF_8);
        // Even if the path parser let a ".." through, SiteFileStore confinement 404s it.
        assertEquals(HttpStatus.NOT_FOUND, controller().serve("/../../secret.txt").getStatusCode());
    }

    @Test
    void aPathThatEscapesStaticButStaysInsideTheSiteIsA404() throws IOException {
        // sites/acme/users.yml sits in the site folder but OUTSIDE static/. A "../users.yml" normalizes
        // into the site (so the store's site-level confinement alone would serve it) — the handler's own
        // static/ subtree pin must refuse it. (The edge HTTP firewall also blocks this in the live app;
        // this proves the defence stands without it.)
        Files.writeString(layout.siteDir("acme").resolve("users.yml"), "SECRET", StandardCharsets.UTF_8);
        assertEquals(HttpStatus.NOT_FOUND, controller().serve("/../users.yml").getStatusCode(),
                "must not serve a file outside static/ even though it is inside the site");
    }

    @Test
    void aGuardWithholdsADiskPresentFile() throws IOException {
        files.write("acme", "static/doc/secret.pdf", new byte[] {9}, 10_000);
        SiteStaticGuard withholdDocs = (siteId, path) -> path.startsWith("doc/");
        assertEquals(HttpStatus.NOT_FOUND, controller(withholdDocs).serve("/doc/secret.pdf").getStatusCode(),
                "a guard hides a present file");
        // A non-doc asset is unaffected by that guard.
        files.writeText("acme", "static/css/site.css", "ok", 10_000);
        assertEquals(HttpStatus.OK, controller(withholdDocs).serve("/css/site.css").getStatusCode());
    }

    @Test
    void anAssetIsScopedToTheCurrentSiteOnly() throws IOException {
        // A file under another site is never reachable via the acme host.
        files.writeText("other", "static/css/other.css", "OTHER", 10_000);
        assertEquals(HttpStatus.NOT_FOUND, controller().serve("/css/other.css").getStatusCode());
    }

    /**
     * The {@code noindex} header reaches a document — the only indexing control a PDF can carry.
     *
     * <p>Asserted on the response the route actually builds rather than on the config, because the value has to
     * survive the branch that decides between the document and asset shapes; a toggle that is stored and never
     * emitted looks identical in the console to one that works.
     */
    @Test
    void aDocumentCarriesTheNoindexHeaderWhenTheSiteAsksForIt() throws IOException {
        Files.createDirectories(layout.siteDir("acme").resolve("static/doc"));
        Files.writeString(layout.siteDir("acme").resolve("static/doc/rates.pdf"), "%PDF-1.4");
        seo.save("acme", new SiteSeoConfig(null, null, null, null, null, null, null, true));

        ResponseEntity<Resource> response = controller().serve("/doc/rates.pdf");

        assertEquals("noindex", response.getHeaders().getFirst("X-Robots-Tag"));
    }

    /** Default OFF: a site that never opened the screen must serve exactly what it served before. */
    @Test
    void documentsAreIndexableUntilTheSiteSaysOtherwise() throws IOException {
        Files.createDirectories(layout.siteDir("acme").resolve("static/doc"));
        Files.writeString(layout.siteDir("acme").resolve("static/doc/rates.pdf"), "%PDF-1.4");

        assertNull(controller().serve("/doc/rates.pdf").getHeaders().getFirst("X-Robots-Tag"),
                "turning this on for everyone would silently de-index documents that rank today");
    }

    /**
     * The toggle is about documents, not about the whole site's assets.
     *
     * <p>An image under {@code static/img/} is a page's own illustration and belongs in image search with the
     * page; the setting is worded and scoped to uploads, and this pins that it stays that way.
     */
    @Test
    void theDocumentToggleDoesNotReachOrdinaryAssets() throws IOException {
        Files.createDirectories(layout.siteDir("acme").resolve("static/img"));
        Files.writeString(layout.siteDir("acme").resolve("static/img/hero.svg"), "<svg/>");
        seo.save("acme", new SiteSeoConfig(null, null, null, null, null, null, null, true));

        assertNull(controller().serve("/img/hero.svg").getHeaders().getFirst("X-Robots-Tag"));
    }

    @Test
    void dotFilesAreNotServedButWellKnownIs() throws IOException {
        for (String name : new String[] {".htaccess", ".git/config", "css/.env", "a/.hidden/b.css"}) {
            files.writeText("acme", "static/" + name, "secret", 10_000);
            assertEquals(HttpStatus.NOT_FOUND, controller().serve("/" + name).getStatusCode(), name);
        }
        files.writeText("acme", "static/.well-known/security.txt", "Contact: mailto:x@acme.test", 10_000);
        assertEquals(HttpStatus.OK, controller().serve("/.well-known/security.txt").getStatusCode());
    }

    @Test
    void anSvgOrXmlFileIsSandboxedSoItCannotRunScriptWhenOpenedDirectly() throws IOException {
        for (String name : new String[] {"img/logo.svg", "img/LOGO.SVG", "browserconfig.xml", "img/a.svgz",
                "style.xsl", "t.xslt", "page.xhtml", "page.XHT"}) {
            files.writeText("acme", "static/" + name, "<svg/>", 10_000);
            ResponseEntity<Resource> response = controller().serve("/" + name);
            assertEquals(HttpStatus.OK, response.getStatusCode(), name);
            assertEquals("sandbox", response.getHeaders().getFirst("Content-Security-Policy"), name);
            assertNull(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION),
                    "still inline, so it works as an <img>: " + name);
        }
        for (String name : new String[] {"css/site.css", "img/hero.png", "app.js"}) {
            files.writeText("acme", "static/" + name, "x", 10_000);
            assertNull(controller().serve("/" + name).getHeaders().getFirst("Content-Security-Policy"), name);
        }
    }
}
