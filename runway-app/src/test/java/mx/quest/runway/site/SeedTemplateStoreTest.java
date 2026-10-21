package mx.quest.runway.site;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import mx.quest.runway.site.SeedTemplateStore.SiteTemplate;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The named seed-template catalog: default classpath fallback, per-file overrides, template CRUD (copy/delete),
 * zip export/import round-trip, the closed allowlist, and zip-slip safety.
 */
class SeedTemplateStoreTest {

    @TempDir
    Path external;

    private SeedTemplateStore store() {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return new SeedTemplateStore(StorageLayout.from(props), new YamlStore());
    }

    // ---- default template + per-file overrides ----------------------------

    @Test
    void defaultReadsTheClasspathWhenNoOverrideExists() {
        SeedTemplateStore store = store();
        assertTrue(store.templateExists("default"), "default always exists");
        assertFalse(store.isOverridden("default", "_master.html"));
        assertTrue(store.readText("default", "_master.html").contains("<style>"), "self-styled classpath master");
        assertArrayEquals(store.readDefault("_master.html"), store.read("default", "_master.html"));
    }

    @Test
    void overrideOnDefaultShadowsThenResetRestores() {
        SeedTemplateStore store = store();
        byte[] platformDefault = store.readDefault("index.html");
        store.save("default", "index.html", "MY HOME");
        assertTrue(store.isOverridden("default", "index.html"));
        assertEquals("MY HOME", store.readText("default", "index.html"));
        assertArrayEquals(platformDefault, store.readDefault("index.html"), "the classpath default is unchanged");

        assertTrue(store.reset("default", "index.html"));
        assertFalse(store.isOverridden("default", "index.html"));
        assertArrayEquals(platformDefault, store.read("default", "index.html"), "reset falls back to the default");
        assertFalse(store.reset("default", "index.html"), "a second reset removes nothing");
    }

    @Test
    void everyMethodRejectsANameOutsideTheAllowlist() {
        SeedTemplateStore store = store();
        assertFalse(store.isSeedFile("../../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> store.read("default", "../../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> store.save("default", "evil.sh", "#!/bin/sh"));
        assertThrows(IllegalArgumentException.class, () -> store.reset("default", "nope.txt"));
    }

    // ---- template CRUD -----------------------------------------------------

    @Test
    void createCopiesEveryEffectiveFileFromTheSource() {
        SeedTemplateStore store = store();
        store.save("default", "index.html", "SOURCE HOME");            // override the source first
        store.createTemplate("corp", "Corporate", "the corporate look", "default");

        assertTrue(store.templateExists("corp"));
        assertEquals("Corporate", store.template("corp").name());
        assertEquals("the corporate look", store.template("corp").description());
        assertTrue(store.isOverridden("corp", "_master.html"), "created template owns concrete files");
        assertEquals("SOURCE HOME", store.readText("corp", "index.html"), "copied the source's effective file");
    }

    @Test
    void createRejectsReservedDuplicateAndUnsafeIds() {
        SeedTemplateStore store = store();
        assertThrows(IllegalArgumentException.class, () -> store.createTemplate("default", "x", "", null));
        assertThrows(IllegalArgumentException.class, () -> store.createTemplate("new", "x", "", null));
        assertThrows(IllegalArgumentException.class, () -> store.createTemplate("../escape", "x", "", null));
        store.createTemplate("once", "Once", "", null);
        assertThrows(IllegalArgumentException.class, () -> store.createTemplate("once", "again", "", null));
    }

    @Test
    void deleteRemovesATemplateButNeverTheBuiltInDefault() {
        SeedTemplateStore store = store();
        store.createTemplate("temp", "Temp", "", null);
        store.deleteTemplate("temp");
        assertFalse(store.templateExists("temp"));
        assertThrows(IllegalArgumentException.class, () -> store.deleteTemplate("default"));
        assertThrows(IllegalArgumentException.class, () -> store.deleteTemplate("ghost"));
    }

    @Test
    void catalogListsDefaultFirstThenOthersById() {
        SeedTemplateStore store = store();
        store.createTemplate("beta", "Beta", "", null);
        store.createTemplate("alpha", "Alpha", "", null);
        List<SiteTemplate> all = store.templates();
        assertEquals(List.of("default", "alpha", "beta"), all.stream().map(SiteTemplate::id).toList());
        assertTrue(all.get(0).builtIn());
        assertFalse(all.get(1).builtIn());
    }

    @Test
    void aTemplateFileFallsBackToTheDefaultWhenNotOverridden() {
        SeedTemplateStore store = store();
        store.createTemplate("partial", "Partial", "", null);
        store.reset("partial", "_master.html");                       // drop its own copy of one file
        assertFalse(store.isOverridden("partial", "_master.html"));
        assertArrayEquals(store.readDefault("_master.html"), store.read("partial", "_master.html"),
                "a missing file inherits the platform default");
    }

    // ---- zip export / import ----------------------------------------------

    @Test
    void exportImportRoundTripsEveryFile() {
        SeedTemplateStore store = store();
        store.save("default", "_master.html", "SRC MASTER");
        store.createTemplate("src", "Source", "d", "default");
        byte[] zip = store.exportZip("src");

        SiteTemplate imported = store.importZip("dst", "Destination", "copied", zip);
        assertEquals("dst", imported.id());
        assertEquals("Destination", imported.name());
        for (var f : SeedTemplateStore.FILES) {
            assertArrayEquals(store.read("src", f.name()), store.read("dst", f.name()), f.name() + " round-trips");
        }
    }

    @Test
    void importRejectsAnArchiveMissingRequiredFiles() {
        SeedTemplateStore store = store();
        byte[] partial = zipOf(Map.of("_master.html", "x".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> store.importZip("bad", "Bad", "", partial));
        assertFalse(store.templateExists("bad"), "a rejected import leaves no folder");
    }

    @Test
    void importIgnoresStrayEntriesAndCannotEscapeTheCatalog() {
        SeedTemplateStore store = store();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (var f : SeedTemplateStore.FILES) {
            entries.put("wrapdir/" + f.name(), ("body-" + f.name()).getBytes(StandardCharsets.UTF_8)); // wrapping dir
        }
        entries.put("../../evil.html", "PWNED".getBytes(StandardCharsets.UTF_8));                       // zip-slip attempt
        entries.put("notes.txt", "ignored".getBytes(StandardCharsets.UTF_8));                           // stray file

        SiteTemplate t = store.importZip("safe", "Safe", "", zipOf(entries));
        assertEquals("safe", t.id());
        assertEquals("body-_master.html", store.readText("safe", "_master.html"), "basenames matched, dir stripped");
        assertFalse(Files.exists(external.resolve("evil.html")), "no traversal outside the catalog root");
        assertFalse(Files.exists(external.resolve("seed").resolve("evil.html")));
    }

    @Test
    void importRejectsReservedOrDuplicateIds() {
        SeedTemplateStore store = store();
        byte[] good = fullZip();
        assertThrows(IllegalArgumentException.class, () -> store.importZip("default", "x", "", good));
        store.importZip("taken", "Taken", "", good);
        assertThrows(IllegalArgumentException.class, () -> store.importZip("taken", "again", "", fullZip()));
    }

    // ---- robustness against unsafe ids / zip bombs -------------------------

    @Test
    void templateExistsIsFalseForAnUnsafeIdAndNeverThrows() {
        SeedTemplateStore store = store();
        assertFalse(store.templateExists("../../etc"), "an unsafe id is absent, not an exception");
        assertFalse(store.templateExists("a/b"));
        assertFalse(store.templateExists("_leading"));
        assertTrue(store.templateExists("default"), "the built-in still resolves");
    }

    @Test
    void importRejectsAnOversizeSeedFileEntry() {
        SeedTemplateStore store = store();
        // A single seed-named entry that inflates past the 8 MiB per-file cap (the shared Zips guard) — the rest
        // of the archive never matters, the bomb is refused mid-read and nothing is written.
        byte[] bomb = zipOf(Map.of("_master.html", new byte[9 * 1024 * 1024]));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> store.importZip("bomb", "Bomb", "", bomb));
        assertTrue(ex.getMessage().contains("per-entry limit"), "rejected by the shared per-entry cap");
        assertFalse(store.templateExists("bomb"), "a rejected import leaves no folder");
    }

    // ---- legacy migration --------------------------------------------------

    @Test
    void migratesTheLegacySingleSeedOverrideIntoTheDefaultTemplate() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        // Pre-place a pre-catalog override at external/seed/site/.
        Files.createDirectories(layout.seedSiteDir());
        Files.writeString(layout.seedSiteDir().resolve("_master.html"), "LEGACY MASTER");

        SeedTemplateStore store = new SeedTemplateStore(layout, new YamlStore());
        store.migrateLegacySeedDir();

        assertEquals("LEGACY MASTER", store.readText("default", "_master.html"), "legacy override became the default");
        assertTrue(store.isOverridden("default", "_master.html"));
    }

    // ---- helpers -----------------------------------------------------------

    private static byte[] fullZip() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (var f : SeedTemplateStore.FILES) {
            entries.put(f.name(), ("x-" + f.name()).getBytes(StandardCharsets.UTF_8));
        }
        return zipOf(entries);
    }

    private static byte[] zipOf(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (IOException io) {
            throw new RuntimeException(io);
        }
        return out.toByteArray();
    }
}
