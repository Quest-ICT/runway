package mx.quest.runway.seo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The SEO config store: absent=empty, save round-trip, and cache eviction on change. */
class SeoConfigServiceTest {

    @TempDir
    Path external;
    private StorageLayout layout;
    private SeoConfigService svc;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        layout = StorageLayout.from(props);
        svc = new SeoConfigService(layout, new YamlStore());
    }

    @Test
    void anAbsentFileIsEmpty() {
        assertEquals(SiteSeoConfig.EMPTY, svc.get("acme"));
    }

    @Test
    void saveThenGetRoundTripsAndPersistsUnderTheSiteFolder() throws IOException {
        SiteSeoConfig config = new SiteSeoConfig("https://acme.test/og.png", "website",
                "summary_large_image", "@acme", "gtoken", null, "<link rel=\"me\" href=\"x\">", true);
        svc.save("acme", config);

        assertTrue(Files.isRegularFile(layout.siteDir("acme").resolve("seo.yml")), "written inside sites/acme/");
        SiteSeoConfig read = svc.get("acme");
        assertEquals("https://acme.test/og.png", read.ogImage());
        assertEquals("summary_large_image", read.twitterCard());
        assertEquals("gtoken", read.googleVerification());
        assertEquals("<link rel=\"me\" href=\"x\">", read.extraHead());
        assertTrue(read.documentsNoindex(), "the document indexing toggle must survive the YAML round trip");
    }

    /**
     * A {@code seo.yml} written before the field existed reads as OFF, not as an error and not as ON.
     *
     * <p>This is the upgrade path for every site already on disk: the file is theirs, it predates the key, and
     * whichever way it resolved would be invisible until someone noticed their documents had left the index.
     */
    @Test
    void anSeoFileWrittenBeforeTheFieldExistedKeepsDocumentsIndexable() throws IOException {
        Files.createDirectories(layout.siteDir("acme"));
        Files.writeString(layout.siteDir("acme").resolve("seo.yml"), "ogType: article\n");

        assertEquals("article", svc.get("acme").ogType(), "the pre-existing content still reads");
        assertFalse(svc.get("acme").documentsNoindex());
    }

    @Test
    void aSaveEvictsSoTheNextGetSeesIt() throws IOException {
        assertEquals(SiteSeoConfig.EMPTY, svc.get("acme")); // caches EMPTY
        svc.save("acme", new SiteSeoConfig(null, "article", null, null, null, null, null, false));
        assertEquals("article", svc.get("acme").ogType(), "save invalidated the cached empty");
    }

    @Test
    void aSiteChangeEventEvictsTheCache() throws IOException {
        assertEquals(SiteSeoConfig.EMPTY, svc.get("acme")); // caches EMPTY
        // Simulate a restore that swapped the file underneath us.
        Path file = layout.siteDir("acme").resolve("seo.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "ogType: article\n");
        svc.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.RESTORED));
        assertEquals("article", svc.get("acme").ogType(), "the event evicted the stale cached empty");
    }
}
