package mx.quest.runway.icon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The manifest store: absent=empty, save round-trip, and cache eviction on change. */
class SiteManifestServiceTest {

    @TempDir
    Path external;
    private StorageLayout layout;
    private SiteManifestService svc;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        layout = StorageLayout.from(props);
        svc = new SiteManifestService(layout, new YamlStore());
    }

    @Test
    void anAbsentFileIsEmpty() {
        assertEquals(SiteManifest.EMPTY, svc.get("acme"));
        assertTrue(SiteManifest.EMPTY.hasAny() == false);
    }

    @Test
    void saveThenGetRoundTrips() throws IOException {
        svc.save("acme", new SiteManifest("Acme", "Acme", "A site", "#0d6efd", "#ffffff", "standalone", "en"));
        SiteManifest read = svc.get("acme");
        assertEquals("Acme", read.name());
        assertEquals("standalone", read.display());
        assertEquals("#0d6efd", read.themeColor());
        assertTrue(read.hasAny());
        assertTrue(Path.of(layout.siteDir("acme").toString(), "manifest.yml").toFile().isFile());
    }

    @Test
    void aSaveEvictsSoTheNextGetSeesIt() throws IOException {
        assertEquals(SiteManifest.EMPTY, svc.get("acme"));
        svc.save("acme", new SiteManifest("New", null, null, null, null, null, null));
        assertEquals("New", svc.get("acme").name());
    }

    @Test
    void aSiteChangeEventEvictsTheCache() throws IOException {
        svc.get("acme");
        svc.save("acme", new SiteManifest("X", null, null, null, null, null, null));
        svc.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.RESTORED));
        assertEquals("X", svc.get("acme").name());
    }
}
