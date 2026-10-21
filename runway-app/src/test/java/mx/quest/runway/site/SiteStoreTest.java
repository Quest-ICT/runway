package mx.quest.runway.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiteStoreTest {

    private static final Locale EN = Locale.ENGLISH;

    private static SiteStore storeIn(Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return new SiteStore(StorageLayout.from(props), new YamlStore());
    }

    private static Site site(String id, String... hosts) {
        return new Site(id, id + " name", List.of(hosts), EN, List.of(EN), null, SiteStatus.ACTIVE);
    }

    @Test
    void savesAndReadsBack(@TempDir Path tmp) {
        SiteStore store = storeIn(tmp);
        Site s = site("acme", "acme.test");
        store.save(s);
        assertTrue(store.exists("acme"));
        assertEquals(s, store.find("acme").orElseThrow());
    }

    /**
     * Pins how {@code site.yml} writes languages and status, which the operator manual documents for hand edits: a
     * region language is a hyphenated tag ({@code pt-BR}), status is {@code ACTIVE} or {@code SUSPENDED}, and a hand
     * edit that writes the underscore form ({@code pt_BR}) still reads as the same language.
     */
    @Test
    void regionLanguagesAndStatusAsTheFileStoresThem(@TempDir Path tmp) throws IOException {
        SiteStore store = storeIn(tmp);
        Locale ptBr = Locale.forLanguageTag("pt-BR");
        store.save(new Site("acme", "Acme", List.of("acme.test"), ptBr, List.of(ptBr, EN), null, SiteStatus.SUSPENDED));
        Path file = tmp.resolve("sites").resolve("acme").resolve("site.yml");
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("defaultLocale: \"pt-BR\""), yaml);
        assertTrue(yaml.contains("- \"pt-BR\""), yaml);
        assertTrue(yaml.contains("status: \"SUSPENDED\""), yaml);

        Files.writeString(file, yaml.replace("pt-BR", "pt_BR"));
        store.evict("acme");
        Site read = store.find("acme").orElseThrow();
        assertEquals(ptBr, read.defaultLocale());
        assertEquals(List.of(ptBr, EN), read.locales());
        assertEquals(SiteStatus.SUSPENDED, read.status());
    }

    @Test
    void findAbsentIsEmpty(@TempDir Path tmp) {
        assertTrue(storeIn(tmp).find("nope").isEmpty());
    }

    @Test
    void unsafeIdIsEmptyNotResolved(@TempDir Path tmp) {
        assertTrue(storeIn(tmp).find("..").isEmpty());
        assertFalse(storeIn(tmp).exists("../x"));
    }

    @Test
    void findAllIsOrderedById(@TempDir Path tmp) {
        SiteStore store = storeIn(tmp);
        store.save(site("beta", "beta.test"));
        store.save(site("alpha", "alpha.test"));
        assertEquals(List.of("alpha", "beta"), store.findAll().stream().map(Site::id).toList());
    }

    @Test
    void findAllSkipsFoldersWithoutSiteYml(@TempDir Path tmp) throws IOException {
        SiteStore store = storeIn(tmp);
        store.save(site("real", "real.test"));
        Files.createDirectories(tmp.resolve("sites").resolve("emptydir"));
        assertEquals(List.of("real"), store.findAll().stream().map(Site::id).toList());
    }

    @Test
    void evictDropsCacheSoOnDiskChangesAreSeen(@TempDir Path tmp) throws IOException {
        SiteStore store = storeIn(tmp);
        store.save(site("acme", "acme.test"));
        assertTrue(store.find("acme").isPresent()); // populate cache
        // Remove the file out of band, then evict — find must now reflect the absence.
        Files.delete(tmp.resolve("sites").resolve("acme").resolve("site.yml"));
        store.evict("acme");
        assertTrue(store.find("acme").isEmpty());
    }

    @Test
    void anOverlongMaintenanceMessageIsCappedAndClearedWhenOff() {
        Site withHuge = new Site("acme", "Acme", List.of(), EN, List.of(EN), null, SiteStatus.ACTIVE,
                true, "x".repeat(5000));
        assertEquals(2000, withHuge.maintenanceMessage().length(), "message is capped to avoid bloating the 503 body");
        // Turning maintenance off drops the message entirely.
        assertEquals(null, withHuge.withMaintenance(false, "still here").maintenanceMessage());
    }

    /**
     * Upgrade path: a site.yml written by a pre-maintenance build (no maintenance keys) must still load,
     * defaulting maintenance off — otherwise upgrading would break every existing site.
     */
    @Test
    void legacySiteYmlWithoutMaintenanceKeysLoadsWithMaintenanceOff(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("sites").resolve("legacy");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("site.yml"),
                "id: legacy\nname: Legacy\nhosts:\n  - legacy.test\ndefaultLocale: en\nlocales:\n  - en\n"
                        + "status: ACTIVE\n");
        SiteStore store = storeIn(tmp);
        Site loaded = store.find("legacy").orElseThrow();
        assertFalse(loaded.maintenance(), "missing maintenance key defaults to off");
        assertEquals(null, loaded.maintenanceMessage());
    }
}
