package mx.quest.runway.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import mx.quest.runway.document.DocumentService;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.storage.SiteFileStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A site's storage ceiling for its files: unlimited by default, a per-site value from
 * {@code platform.yml} over the platform default, enforced on static and document uploads, counting replacements
 * correctly, and never taking anything away from a site that is already over it.
 */
class SiteStorageCeilingsTest {

    @TempDir
    Path external;
    private StorageLayout layout;
    private PlatformSettingsStore platform;
    private SiteFileStore files;

    @BeforeEach
    void setUp() throws Exception {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        layout = StorageLayout.from(props);
        Files.createDirectories(layout.siteDir("acme"));
        platform = new PlatformSettingsStore(layout, new YamlStore());
        files = new SiteFileStore(layout);
    }

    private SiteStorageCeilings ceilings(long platformDefault) {
        return new SiteStorageCeilings(platform, layout, platformDefault);
    }

    private StaticFilesService statics(SiteStorageCeilings ceilings) {
        return new StaticFilesService(files, ceilings);
    }

    private static byte[] bytes(int n) {
        return new byte[n];
    }

    @Test
    void unlimitedByDefaultSoAnUpgradeChangesNothing() throws Exception {
        SiteStorageCeilings ceilings = ceilings(0);
        assertFalse(ceilings.of("acme").limited());
        assertEquals(SiteStorageCeilings.Source.DEFAULT, ceilings.of("acme").source());
        StaticFilesService service = statics(ceilings);
        for (int i = 0; i < 5; i++) {
            service.upload("acme", "", "f" + i + ".js", bytes(1000), false);
        }
        assertEquals(5000, ceilings.usedBytes("acme"));
    }

    @Test
    void anUploadThatWouldGoOverTheCeilingIsRefusedAndNothingIsWritten() throws Exception {
        StaticFilesService service = statics(ceilings(100));
        service.upload("acme", "", "a.js", bytes(60), false);
        StaticFilesService.Refused refused = assertThrows(StaticFilesService.Refused.class,
                () -> service.upload("acme", "", "b.js", bytes(41), false));
        assertEquals("overCeiling", refused.code());
        assertFalse(files.exists("acme", "static/b.js"), "a refused upload leaves no file behind");
        service.upload("acme", "", "c.js", bytes(40), false); // exactly at the ceiling is allowed
        assertEquals(100, ceilings(100).usedBytes("acme"));
    }

    @Test
    void aReplacementCountsOnlyWhatItAdds() throws Exception {
        SiteStorageCeilings ceilings = ceilings(100);
        StaticFilesService service = statics(ceilings);
        service.upload("acme", "", "a.js", bytes(100), false);
        service.upload("acme", "", "a.js", bytes(100), true); // at the ceiling, replaced by the same size
        service.upload("acme", "", "a.js", bytes(30), true);
        assertEquals(30, ceilings.usedBytes("acme"));
        assertThrows(StaticFilesService.Refused.class, () -> service.upload("acme", "", "a.js", bytes(101), true),
                "a replacement that grows the folder past the ceiling is still refused");
    }

    @Test
    void aSiteOverItsCeilingKeepsEverythingCanDeleteAndCanShrinkAFile() throws Exception {
        StaticFilesService service = statics(ceilings(0));
        service.upload("acme", "", "big.js", bytes(500), false);
        service.upload("acme", "", "other.js", bytes(300), false);
        platform.update(current -> current.withSiteStorageCeiling("acme", 100L)); // the operator lowers it
        SiteStorageCeilings ceilings = ceilings(0);
        StaticFilesService limited = statics(ceilings);

        assertEquals(800, ceilings.usedBytes("acme"), "nothing is deleted or hidden by a lower ceiling");
        assertTrue(files.exists("acme", "static/big.js"));
        assertThrows(StaticFilesService.Refused.class, () -> limited.upload("acme", "", "new.js", bytes(1), false));
        limited.upload("acme", "", "big.js", bytes(200), true); // shrinking a file is always allowed
        assertTrue(limited.delete("acme", "other.js"), "a site over its ceiling can still delete");
        ceilings.changed("acme");
        assertEquals(200, ceilings.usedBytes("acme"));
    }

    @Test
    void theSitesOwnCeilingWinsOverThePlatformDefaultAndZeroMeansUnlimited() throws Exception {
        platform.update(current -> current.withSiteStorageCeiling("acme", 0L));
        SiteStorageCeilings ceilings = ceilings(10);
        assertEquals(SiteStorageCeilings.Source.SITE, ceilings.of("acme").source());
        assertFalse(ceilings.of("acme").limited());
        statics(ceilings).upload("acme", "", "a.js", bytes(50), false);

        platform.update(current -> current.withSiteStorageCeiling("acme", 60L));
        assertEquals(60, ceilings.of("acme").bytes(), "read live from platform.yml: no restart");
        assertThrows(StaticFilesService.Refused.class,
                () -> statics(ceilings).upload("acme", "", "b.js", bytes(11), false));
        assertEquals(10, ceilings.of("other").bytes(), "a site without its own value has the platform default");
    }

    @Test
    void documentsCountAndAreRefusedLikeStaticFiles() throws Exception {
        SiteStorageCeilings ceilings = ceilings(100);
        DocumentService documents = new DocumentService(files, layout, new YamlStore(), ceilings);
        documents.add("acme", "report.pdf", bytes(70));
        assertThrows(StaticFilesService.Refused.class,
                () -> statics(ceilings).upload("acme", "", "a.js", bytes(31), false),
                "a document takes space in static/ like any file");
        assertThrows(SiteStorageCeilings.OverCeiling.class, () -> documents.add("acme", "more.pdf", bytes(31)));
        assertFalse(files.exists("acme", "static/doc/more.pdf"));
        documents.delete("acme", "report.pdf");
        documents.add("acme", "more.pdf", bytes(31));
        assertEquals(31, ceilings.usedBytes("acme"));
    }

    @Test
    void theScreenFigureFollowsWritesDeletesAndSiteChanges() throws Exception {
        SiteStorageCeilings ceilings = ceilings(0);
        assertEquals(0, ceilings.usedBytes("acme"));
        statics(ceilings).upload("acme", "", "a.js", bytes(10), false);
        assertEquals(10, ceilings.usedBytes("acme"), "a write drops the cached figure");
        Files.write(layout.siteDir("acme").resolve("static/by-hand.js"), bytes(5)); // copied onto the server
        assertEquals(10, ceilings.usedBytes("acme"), "cached until a change is known (or the TTL)");
        ceilings.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.RESTORED));
        assertEquals(15, ceilings.usedBytes("acme"), "a site change (a restore, a delete) drops it");
    }

    @Test
    void onlyRegularFilesUnderStaticAreCountedAndLinksAreNotFollowed() throws Exception {
        Path site = layout.siteDir("acme");
        Files.createDirectories(site.resolve("templates"));
        Files.write(site.resolve("templates/index.html"), bytes(1000)); // pages are not static files
        Files.createDirectories(site.resolve("static/css"));
        Files.write(site.resolve("static/css/a.css"), bytes(7));
        Path outside = Files.write(external.resolve("huge.bin"), bytes(5000));
        Files.createSymbolicLink(site.resolve("static/link.bin"), outside);
        assertEquals(7, ceilings(0).measure("acme"));
    }
}
