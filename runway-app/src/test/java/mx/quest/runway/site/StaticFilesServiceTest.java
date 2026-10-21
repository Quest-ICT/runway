package mx.quest.runway.site;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mx.quest.runway.storage.SiteFileStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The rules of the site's static files: confined, web asset types only, doc/ left to Documents. */
class StaticFilesServiceTest {

    @TempDir
    Path external;
    private StaticFilesService service;
    private Path staticDir;

    @BeforeEach
    void setUp() throws Exception {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        Files.createDirectories(layout.siteDir("acme"));
        staticDir = layout.siteDir("acme").resolve("static");
        service = new StaticFilesService(new SiteFileStore(layout), new SiteStorageCeilings(
                new mx.quest.runway.platform.PlatformSettingsStore(layout, new mx.quest.runway.storage.YamlStore()), layout, 0));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void uploadsIntoFoldersWithSafeNamesAndListsFoldersFirst() throws Exception {
        service.createFolder("acme", "", "css");
        assertEquals("My_Site.css", service.upload("acme", "css", "My Site.css", bytes("a{}"), false));
        service.upload("acme", "", "logo.svg", bytes("<svg/>"), false);
        assertArrayEquals(bytes("a{}"), Files.readAllBytes(staticDir.resolve("css/My_Site.css")));
        List<String> names = service.list("acme", "").stream().map(e -> e.name()).toList();
        assertEquals(List.of("css", "logo.svg"), names, "folders first, then files");
    }

    @Test
    void replacingNeedsConsent() throws Exception {
        service.upload("acme", "", "app.js", bytes("v1"), false);
        StaticFilesService.Refused refused = assertThrows(StaticFilesService.Refused.class,
                () -> service.upload("acme", "", "app.js", bytes("v2"), false));
        assertEquals("exists", refused.code());
        service.upload("acme", "", "app.js", bytes("v2"), true);
        assertArrayEquals(bytes("v2"), Files.readAllBytes(staticDir.resolve("app.js")));
    }

    @Test
    void onlyWebAssetTypesAndSizesAreAccepted() {
        for (String name : new String[] {"page.html", "index.htm", "run.sh", "x.php", "noext", ".htaccess"}) {
            assertThrows(StaticFilesService.Refused.class,
                    () -> service.upload("acme", "", name, bytes("x"), false), name);
        }
        byte[] big = new byte[(int) StaticFilesService.MAX_FILE_BYTES + 1];
        assertEquals("tooLarge", assertThrows(StaticFilesService.Refused.class,
                () -> service.upload("acme", "", "big.png", big, false)).code());
    }

    @Test
    void theDocumentsFolderIsNeverChangedHere() throws Exception {
        Files.createDirectories(staticDir.resolve("doc"));
        Files.writeString(staticDir.resolve("doc/report.pdf"), "pdf");
        assertEquals("documents", assertThrows(StaticFilesService.Refused.class,
                () -> service.upload("acme", "doc", "x.pdf", bytes("x"), true)).code());
        assertEquals("documents", assertThrows(StaticFilesService.Refused.class,
                () -> service.delete("acme", "doc/report.pdf")).code());
        assertEquals("documents", assertThrows(StaticFilesService.Refused.class,
                () -> service.createFolder("acme", "", "doc")).code());
        assertTrue(Files.exists(staticDir.resolve("doc/report.pdf")));
    }

    /** On a case-insensitive filesystem "DOC/" is Documents' folder: any spelling of it belongs to that screen. */
    @Test
    void theDocumentsFolderInAnyCaseIsNeverChangedHere() {
        for (String folder : new String[] {"DOC", "Doc", "dOc"}) {
            assertEquals("documents", assertThrows(StaticFilesService.Refused.class,
                    () -> service.upload("acme", folder, "x.pdf", bytes("x"), true)).code(), folder);
            assertEquals("documents", assertThrows(StaticFilesService.Refused.class,
                    () -> service.createFolder("acme", "", folder)).code(), folder);
        }
    }

    @Test
    void pathsCannotLeaveTheStaticFolder() {
        for (String bad : new String[] {"..", "../templates", "css/../../users.yml", "css/..", "a\\b"}) {
            assertThrows(StaticFilesService.Refused.class, () -> service.list("acme", bad), bad);
            assertThrows(StaticFilesService.Refused.class, () -> service.delete("acme", bad), bad);
        }
        assertThrows(StaticFilesService.Refused.class, () -> service.createFolder("acme", "", "../x"));
        assertThrows(StaticFilesService.Refused.class, () -> service.createFolder("acme", "", ".git"));
    }

    @Test
    void aLongUploadedNameCanBeDeleted() throws Exception {
        String name = "app.chunk-vendors.4f3a9c1e7b2d8a6f0e5c3b1a9d7f2e4c6b8a0d1f3e5c7b9a.12345678.min.js";
        assertTrue(name.length() > 64, "longer than a folder name may be");
        assertEquals(name, service.upload("acme", "", name, bytes("x"), false));
        assertTrue(service.delete("acme", name));
        assertFalse(Files.exists(staticDir.resolve(name)));
    }

    @Test
    void handPlacedNamesCanBeListedOpenedAndDeleted() throws Exception {
        String longFolder = "f".repeat(70);
        Files.createDirectories(staticDir.resolve(longFolder));
        Files.writeString(staticDir.resolve(longFolder + "/señal.svg"), "<svg/>");
        Files.writeString(staticDir.resolve("my file.css"), "a{}");
        Files.writeString(staticDir.resolve("logo@2x.png"), "png");

        assertTrue(service.folderExists("acme", longFolder));
        assertEquals(List.of("señal.svg"), service.list("acme", longFolder).stream().map(e -> e.name()).toList());
        assertEquals("ok.css", service.upload("acme", longFolder, "ok.css", bytes("b{}"), false),
                "an existing folder of any name takes uploads");
        assertTrue(service.delete("acme", longFolder + "/señal.svg"));
        assertTrue(service.delete("acme", longFolder + "/ok.css"));
        assertTrue(service.delete("acme", longFolder), "the folder, once empty");
        assertTrue(service.delete("acme", "my file.css"));
        assertTrue(service.delete("acme", "logo@2x.png"));
        assertEquals(List.of(), service.list("acme", ""));
    }

    @Test
    void anUploadDoesNotCreateAFolderOutsideTheFolderNameRule() {
        assertEquals("badPath", assertThrows(StaticFilesService.Refused.class,
                () -> service.upload("acme", "new folder", "a.css", bytes("x"), false)).code());
        assertFalse(Files.exists(staticDir.resolve("new folder")));
        assertEquals("badPath", assertThrows(StaticFilesService.Refused.class,
                () -> service.createFolder("acme", "no such dir", "css")).code());
        assertEquals("badFolder", assertThrows(StaticFilesService.Refused.class,
                () -> service.createFolder("acme", "", "f".repeat(70))).code(), "a new folder name stays strict");
    }

    @Test
    void existingPathsStayInsideTheStaticFolder() throws Exception {
        Files.writeString(external.resolve("outside.css"), "secret");
        Files.createDirectories(staticDir);
        for (String bad : new String[] {"../site.yml", "css/../../site.yml", "a//b", "./x.css", "..", "a/./b",
                "a\\b.css", "a\0b.css", "line\nbreak.css"}) {
            assertEquals("badPath", assertThrows(StaticFilesService.Refused.class,
                    () -> service.delete("acme", bad), bad).code(), bad);
            assertThrows(StaticFilesService.Refused.class, () -> service.list("acme", bad), bad);
        }
        // A folder linked to somewhere outside the site is neither listed through nor deleted through.
        Path outside = Files.createDirectories(external.resolve("elsewhere"));
        Files.writeString(outside.resolve("victim.css"), "keep");
        Files.createSymbolicLink(staticDir.resolve("linked"), outside);
        assertEquals(List.of(), service.list("acme", "linked"));
        assertFalse(service.delete("acme", "linked/victim.css"));
        assertTrue(Files.exists(outside.resolve("victim.css")));
    }

    /**
     * A folder link planted inside {@code static/} pointed at the site's own {@code pages/} and
     * {@code doc/}. Real-path confinement kept it inside the site, so delete and upload went through it: a page was
     * deleted, a file landed in {@code pages/}, and a document was deleted past the Documents rule. No link inside
     * the site is followed now.
     */
    @Test
    void aFolderLinkInsideStaticReachesNeitherPagesNorDocuments() throws Exception {
        Path site = staticDir.getParent();
        Files.createDirectories(site.resolve("pages"));
        Files.writeString(site.resolve("pages/index.html"), "<p>home</p>");
        Files.createDirectories(staticDir.resolve("doc"));
        Files.writeString(staticDir.resolve("doc/report.pdf"), "pdf");
        Files.createSymbolicLink(staticDir.resolve("pg"), Path.of("../pages"));
        Files.createSymbolicLink(staticDir.resolve("d2"), Path.of("doc"));

        assertFalse(service.delete("acme", "pg/index.html"), "a page is not deleted through the link");
        assertTrue(Files.exists(site.resolve("pages/index.html")));
        assertFalse(service.delete("acme", "d2/report.pdf"), "nor a document past the Documents rule");
        assertTrue(Files.exists(staticDir.resolve("doc/report.pdf")));
        assertThrows(IllegalArgumentException.class,
                () -> service.upload("acme", "pg", "x.css", bytes("a{}"), true), "no upload through the link");
        assertFalse(Files.exists(site.resolve("pages/x.css")));
        assertThrows(IllegalArgumentException.class, () -> service.createFolder("acme", "d2", "sub"));
        assertFalse(Files.exists(staticDir.resolve("doc/sub")));
        assertEquals(List.of(), service.list("acme", "pg"), "nothing is listed through it");
        assertFalse(service.list("acme", "").stream().anyMatch(e -> e.name().equals("pg")), "and it is not listed");
        assertFalse(service.delete("acme", "pg"), "the link itself is not followed or removed from here");
        assertTrue(Files.isSymbolicLink(staticDir.resolve("pg")));
    }

    /** Every listed name can be deleted: a leading dot or a leading space is part of a real name. */
    @Test
    void dotNamesAndNamesWithSpacesAroundThemAreDeletable() throws Exception {
        Files.createDirectories(staticDir.resolve(".well-known"));
        Files.writeString(staticDir.resolve(".htaccess"), "x");
        Files.writeString(staticDir.resolve(".well-known/security.txt"), "Contact: x");
        Files.writeString(staticDir.resolve(" lead.css"), "a{}");
        Files.writeString(staticDir.resolve("trail.css "), "a{}");
        assertEquals(List.of(".well-known", " lead.css", ".htaccess", "trail.css "),
                service.list("acme", "").stream().map(e -> e.name()).toList());
        assertEquals(List.of("security.txt"),
                service.list("acme", ".well-known").stream().map(e -> e.name()).toList(), "a dot folder opens");
        for (String name : new String[] {".htaccess", ".well-known/security.txt", ".well-known", " lead.css",
                "trail.css "}) {
            assertTrue(service.delete("acme", name), name);
        }
        assertEquals(List.of(), service.list("acme", ""));
    }

    @Test
    void aFolderIsDeletedOnlyWhenEmpty() throws Exception {
        service.createFolder("acme", "", "img");
        service.upload("acme", "img", "a.png", bytes("png"), false);
        assertFalse(service.delete("acme", "img"), "not while it holds a file");
        assertTrue(service.delete("acme", "img/a.png"));
        assertTrue(service.delete("acme", "img"));
        assertFalse(Files.exists(staticDir.resolve("img")));
    }

    @Test
    void foldersNestOnlySoDeep() throws Exception {
        String path = "";
        for (int i = 1; i < StaticFilesService.MAX_DEPTH; i++) {
            service.createFolder("acme", path, "d" + i);
            path = path.isEmpty() ? "d" + i : path + "/d" + i;
        }
        service.createFolder("acme", path, "last");
        String full = path + "/last";
        assertEquals("tooDeep", assertThrows(StaticFilesService.Refused.class,
                () -> service.createFolder("acme", full, "more")).code());
    }
}
