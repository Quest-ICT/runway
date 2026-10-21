package mx.quest.runway.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The site template-parameters store: an absent file is empty, a save round-trips (and lands
 * under the site's own folder), a hand-edited file with scalar values is coerced, tenant scoping holds, and
 * the cache is evicted on save and on a site change.
 */
class SiteParamsServiceTest {

    @TempDir
    Path external;
    private StorageLayout layout;
    private SiteParamsService svc;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        layout = StorageLayout.from(props);
        svc = new SiteParamsService(layout, new YamlStore());
    }

    private void writeParamsFile(String siteId, String yaml) throws IOException {
        Path file = layout.siteDir(siteId).resolve("params.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
    }

    @Test
    void anAbsentFileIsEmpty() {
        assertTrue(svc.get("acme").isEmpty());
    }

    @Test
    void saveThenGetRoundTripsAndPersistsUnderTheSiteFolder() throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("analytics", "UA-99");
        params.put("region", "eu");
        svc.save("acme", params);

        assertTrue(Files.isRegularFile(layout.siteDir("acme").resolve("params.yml")), "written inside sites/acme/");
        Map<String, String> read = svc.get("acme");
        assertEquals("UA-99", read.get("analytics"));
        assertEquals("eu", read.get("region"));
    }

    @Test
    void aSaveEvictsSoTheNextGetSeesIt() throws IOException {
        assertTrue(svc.get("acme").isEmpty()); // caches empty
        svc.save("acme", Map.of("k", "v"));
        assertEquals("v", svc.get("acme").get("k"), "the save invalidated the cached empty");
    }

    @Test
    void savingEmptyDeletesTheFile() throws IOException {
        svc.save("acme", Map.of("k", "v"));
        assertTrue(Files.isRegularFile(layout.siteDir("acme").resolve("params.yml")));
        svc.save("acme", Map.of()); // clear
        assertFalse(Files.exists(layout.siteDir("acme").resolve("params.yml")), "an empty save removes the file");
        assertTrue(svc.get("acme").isEmpty());
    }

    @Test
    void handEditedScalarValuesAreCoercedToStrings() throws IOException {
        writeParamsFile("acme", "count: 3\nenabled: true\nname: Acme\n");
        Map<String, String> read = svc.get("acme");
        assertEquals("3", read.get("count"), "a YAML number is coerced to its string form");
        assertEquals("true", read.get("enabled"), "a YAML boolean is coerced to a string");
        assertEquals("Acme", read.get("name"));
    }

    @Test
    void aNestedValueDegradesToEmptyRatherThanFailing() throws IOException {
        // A nested map cannot be addressed as ${params.key}; a malformed file degrades to empty (fail-soft),
        // never throwing into a page render.
        writeParamsFile("acme", "ok: yes\nbad:\n  nested: 1\n");
        assertTrue(svc.get("acme").isEmpty(), "a params.yml with a nested value degrades to no params");
    }

    @Test
    void paramsAreTenantScoped() throws IOException {
        svc.save("acme", Map.of("who", "acme"));
        svc.save("globex", Map.of("who", "globex"));
        assertEquals("acme", svc.get("acme").get("who"));
        assertEquals("globex", svc.get("globex").get("who"), "one site never reads another's params");
        assertFalse(svc.get("acme").containsValue("globex"));
    }

    @Test
    void aSiteChangeEventEvictsTheCache() throws IOException {
        svc.save("acme", Map.of("k", "v1"));
        assertEquals("v1", svc.get("acme").get("k")); // cache it
        // A restore/replacement rewrites the file underneath the cache; the event must drop the stale entry.
        writeParamsFile("acme", "k: v2\n");
        svc.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.RESTORED));
        assertEquals("v2", svc.get("acme").get("k"), "the site-change event evicted the cached value");
    }
}
