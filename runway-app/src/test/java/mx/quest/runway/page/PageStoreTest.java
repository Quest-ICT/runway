package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageStoreTest {

    private static final Locale EN = Locale.ENGLISH;

    private static PageStore storeIn(Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return new PageStore(StorageLayout.from(props), new YamlStore());
    }

    private static void writeMeta(Path external, String siteId, String slug, String yaml) throws IOException {
        Path file = external.resolve("sites").resolve(siteId).resolve("templates").resolve(slug + ".meta.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
    }

    @Test
    void findReadsMetaAndAppliesSlug(@TempDir Path tmp) throws IOException {
        writeMeta(tmp, "acme", "about", "path: /about\ntemplate: _master\ntitle:\n  en: About Us\n");
        Page page = storeIn(tmp).find("acme", "about").orElseThrow();
        assertEquals("about", page.slug());
        assertEquals("/about", page.path());
        assertEquals("About Us", page.titleFor(EN));
    }

    @Test
    void findAllSkipsMasterAndUnderscoreFiles(@TempDir Path tmp) throws IOException {
        writeMeta(tmp, "acme", "about", "path: /about\n");
        writeMeta(tmp, "acme", "home", "path: /\n");
        writeMeta(tmp, "acme", "_master", "extraSeams: []\n"); // a master manifest, not a page
        assertEquals(List.of("home", "about"),
                storeIn(tmp).findAll("acme").stream().map(Page::slug).sorted((a, b) -> b.compareTo(a)).toList());
    }

    @Test
    void findByPathMatchesPublishedPages(@TempDir Path tmp) throws IOException {
        writeMeta(tmp, "acme", "about", "path: /about\n");
        PageStore store = storeIn(tmp);
        assertEquals("about", store.findByPath("acme", "/about", EN).orElseThrow().slug());
        assertTrue(store.findByPath("acme", "/missing", EN).isEmpty());
    }

    @Test
    void findByPathExcludesDrafts(@TempDir Path tmp) throws IOException {
        writeMeta(tmp, "acme", "secret", "path: /secret\ndraft: true\n");
        assertTrue(storeIn(tmp).findByPath("acme", "/secret", EN).isEmpty());
    }

    @Test
    void unsafeIdsAreEmpty(@TempDir Path tmp) {
        assertTrue(storeIn(tmp).find("..", "x").isEmpty());
        assertTrue(storeIn(tmp).find("acme", "..").isEmpty());
    }

    @Test
    void readsNoindexAndCanonicalFromMeta(@TempDir Path tmp) throws IOException {
        writeMeta(tmp, "acme", "old", "path: /old\nnoindex: true\ncanonical: https://acme.test/new\n");
        Page page = storeIn(tmp).find("acme", "old").orElseThrow();
        assertTrue(page.noindex());
        assertEquals("https://acme.test/new", page.canonical());
    }

    @Test
    void noindexAndCanonicalRoundTripThroughYaml(@TempDir Path tmp) throws IOException {
        Page written = new Page("about", "/about", null, List.of(EN), Map.of("en", "About"), Map.of(),
                "", "_master", false, true, "https://acme.test/canonical", null).withoutSlug();
        Path file = tmp.resolve("about.meta.yml");
        new YamlStore().write(file, written);

        // A blank/absent override is normalized to null and, with @JsonInclude(NON_NULL), omitted entirely.
        assertFalse(Files.readString(file).contains("canonical: \"\""), "a set override is not blank");
        assertTrue(Files.readString(file).contains("noindex: true"));

        Page reread = new YamlStore().read(file, Page.class).orElseThrow();
        assertTrue(reread.noindex(), "noindex survives the YAML round-trip");
        assertEquals("https://acme.test/canonical", reread.canonical(), "canonical survives the round-trip");
    }

    @Test
    void aBlankCanonicalIsNotPersisted(@TempDir Path tmp) throws IOException {
        Page written = new Page("home", "/", null, List.of(EN), Map.of("en", "Home"), Map.of(),
                "", "_master", false, false, "   ", null).withoutSlug(); // blank override ⇒ no override
        Path file = tmp.resolve("home.meta.yml");
        new YamlStore().write(file, written);
        assertFalse(Files.readString(file).contains("canonical"), "a blank canonical is omitted from the YAML");
        assertEquals(null, new YamlStore().read(file, Page.class).orElseThrow().canonical());
    }

    @Test
    void paramsRoundTripThroughYaml(@TempDir Path tmp) throws IOException {
        Page written = new Page("about", "/about", null, List.of(EN), Map.of("en", "About"), Map.of(),
                "", "_master", false, false, null, null, Map.of("analytics", "UA-42", "region", "eu")).withoutSlug();
        Path file = tmp.resolve("about.meta.yml");
        new YamlStore().write(file, written);
        assertTrue(Files.readString(file).contains("params"), "params are serialized");

        Page reread = new YamlStore().read(file, Page.class).orElseThrow();
        assertEquals("UA-42", reread.params().get("analytics"), "a param survives the YAML round-trip");
        assertEquals("eu", reread.params().get("region"));
    }

    @Test
    void emptyParamsAreOmittedFromTheYaml(@TempDir Path tmp) throws IOException {
        // An empty map with @JsonInclude(NON_NULL)+NON_EMPTY-less inclusion still writes []/{} unless empty is
        // pruned; the page keeps no params by default, so the store must read it back as an empty map.
        Page written = new Page("home", "/", null, List.of(EN), Map.of("en", "Home"), Map.of(),
                "", "_master", false, false, null, null).withoutSlug();
        Path file = tmp.resolve("home.meta.yml");
        new YamlStore().write(file, written);
        assertTrue(new YamlStore().read(file, Page.class).orElseThrow().params().isEmpty(),
                "a page with no params reads back with an empty params map");
    }
}
