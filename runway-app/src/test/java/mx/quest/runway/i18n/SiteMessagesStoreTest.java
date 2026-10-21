package mx.quest.runway.i18n;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import mx.quest.runway.site.Site;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SiteMessagesStore} (the write side of the tenant messages editor): the file layout
 * (default→base, others→suffixed), the blank=delete rule, deterministic sorted output, special-character
 * round-trip, and per-site isolation — all with no Spring context.
 */
class SiteMessagesStoreTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");
    private static final List<Locale> LOCALES = List.of(EN, ES); // default first

    private record Fixture(StorageLayout layout, SiteMessagesStore store) {
    }

    private static Fixture fixture(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        return new Fixture(layout, new SiteMessagesStore(layout));
    }

    private static Path bundle(StorageLayout layout, String siteId, String suffix) {
        return layout.siteDir(siteId).resolve("i18n").resolve("messages" + suffix + ".properties");
    }

    private static Map<String, Map<String, String>> grid(Object... kv) {
        // kv: key, enValue, esValue, key, enValue, esValue, ...
        Map<String, Map<String, String>> grid = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 3) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("en", (String) kv[i + 1]);
            row.put("es", (String) kv[i + 2]);
            grid.put((String) kv[i], row);
        }
        return grid;
    }

    @Test
    void writesDefaultToBaseAndOthersToSuffixedFiles(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.store.save("acme", LOCALES, grid("about.title", "About", "Acerca"));

        // Default (en) → messages.properties; es → messages_es.properties.
        assertTrue(Files.isRegularFile(bundle(f.layout, "acme", "")), "base file");
        assertTrue(Files.isRegularFile(bundle(f.layout, "acme", "_es")), "es file");
        assertEquals("about.title=About\n", Files.readString(bundle(f.layout, "acme", ""), StandardCharsets.UTF_8));
        assertEquals("about.title=Acerca\n", Files.readString(bundle(f.layout, "acme", "_es"), StandardCharsets.UTF_8));
    }

    @Test
    void loadReturnsTheKeyByLocaleGrid(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.store.save("acme", LOCALES, grid("a", "A", "Aa", "b", "B", "")); // b has no es value

        Map<String, Map<String, String>> loaded = f.store.load("acme", LOCALES);
        assertEquals("A", loaded.get("a").get("en"));
        assertEquals("Aa", loaded.get("a").get("es"));
        assertEquals("B", loaded.get("b").get("en"));
        assertEquals("", loaded.get("b").get("es"), "a locale with no entry is a blank gap");
    }

    @Test
    void blankValueDropsTheKeyFromThatLocaleOnly(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        // en has a value, es is blank → key present only in the base file.
        f.store.save("acme", LOCALES, grid("greeting", "Hi", "  "));

        assertEquals("greeting=Hi\n", Files.readString(bundle(f.layout, "acme", ""), StandardCharsets.UTF_8));
        assertFalse(Files.exists(bundle(f.layout, "acme", "_es")), "an all-blank es bundle is not written");
    }

    @Test
    void aFullyClearedLocaleFileIsDeleted(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.store.save("acme", LOCALES, grid("k", "v", "w"));
        assertTrue(Files.exists(bundle(f.layout, "acme", "_es")));

        // Re-save with es blanked → the previously-written es file is removed (no stray empty bundle).
        f.store.save("acme", LOCALES, grid("k", "v", ""));
        assertFalse(Files.exists(bundle(f.layout, "acme", "_es")), "cleared es file deleted");
        assertTrue(Files.exists(bundle(f.layout, "acme", "")), "base file kept");
    }

    @Test
    void removingAKeyDropsItFromEveryLocale(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.store.save("acme", LOCALES, grid("keep", "K", "Kk", "drop", "D", "Dd"));
        // Re-save without "drop" (as the controller does when the key is un-kept).
        f.store.save("acme", LOCALES, grid("keep", "K", "Kk"));

        Map<String, Map<String, String>> loaded = f.store.load("acme", LOCALES);
        assertTrue(loaded.containsKey("keep"));
        assertFalse(loaded.containsKey("drop"), "removed key is gone from all locales");
    }

    @Test
    void keysAreWrittenSortedForStableDiffs(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        Map<String, Map<String, String>> g = new LinkedHashMap<>();
        g.put("zzz", Map.of("en", "z", "es", "z"));
        g.put("aaa", Map.of("en", "a", "es", "a"));
        g.put("mmm", Map.of("en", "m", "es", "m"));
        f.store.save("acme", LOCALES, g);
        assertEquals("aaa=a\nmmm=m\nzzz=z\n",
                Files.readString(bundle(f.layout, "acme", ""), StandardCharsets.UTF_8), "sorted, no timestamp comment");
    }

    @Test
    void specialCharactersRoundTripIntact(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        // A value with '=', ':', a newline, a tab, unicode, and a leading space; and a dotted key.
        String tricky = " a=b : c\td\né中文\\x";
        f.store.save("acme", LOCALES, grid("weird.key", tricky, "ok"));

        Map<String, Map<String, String>> loaded = f.store.load("acme", LOCALES);
        assertEquals(tricky, loaded.get("weird.key").get("en"), "value round-trips byte-for-byte");
        // A raw newline in the value must not have injected a second key.
        assertEquals(1, loaded.size(), "the multiline value did not create a spurious key");
    }

    @Test
    void keyWithSeparatorsRoundTrips(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        // A key containing '=' and ':' must be escaped so it survives the round-trip as one key.
        f.store.save("acme", LOCALES, grid("odd=key:name", "value", "valor"));
        Map<String, Map<String, String>> loaded = f.store.load("acme", LOCALES);
        assertTrue(loaded.containsKey("odd=key:name"), "escaped key parses back whole");
        assertEquals("value", loaded.get("odd=key:name").get("en"));
    }

    // kv: key, en, es, fr, key, en, es, fr, …
    private static Map<String, Map<String, String>> grid3(Object... kv) {
        Map<String, Map<String, String>> grid = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 4) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("en", (String) kv[i + 1]);
            row.put("es", (String) kv[i + 2]);
            row.put("fr", (String) kv[i + 3]);
            grid.put((String) kv[i], row);
        }
        return grid;
    }

    @Test
    void switchingTheDefaultReHomesTheBaseFileSoNoColumnIsWronglyEmptyOrSwapped(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        Locale fr = Locale.of("fr");
        // The exact Northbeam starting layout: en default (English in the base file), es/fr suffixed.
        f.store.save("nb", List.of(EN, ES, fr), grid3("nav.about", "About", "Acerca", "À propos"));
        assertEquals("nav.about=About\n", Files.readString(bundle(f.layout, "nb", ""), StandardCharsets.UTF_8));

        // The operator switches the site default en → es (the trigger for the reported bug).
        f.store.migrateDefaultLocale("nb", EN, ES);

        // Base now holds the NEW default (Spanish); English moved to its own suffixed file; the old es file is
        // folded into base; French is untouched.
        assertEquals("nav.about=Acerca\n", Files.readString(bundle(f.layout, "nb", ""), StandardCharsets.UTF_8));
        assertEquals("nav.about=About\n", Files.readString(bundle(f.layout, "nb", "_en"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(bundle(f.layout, "nb", "_es")), "the new default's suffixed file is folded into base");
        assertEquals("nav.about=À propos\n", Files.readString(bundle(f.layout, "nb", "_fr"), StandardCharsets.UTF_8));

        // The editor grid, now loaded default-first [es, en, fr], shows each language under ITS OWN column —
        // NOT English-as-Spanish with a blank English column (the bug).
        Map<String, Map<String, String>> g = f.store.load("nb", List.of(ES, EN, fr));
        assertEquals("Acerca", g.get("nav.about").get("es"), "Spanish under es (bug showed English here)");
        assertEquals("About", g.get("nav.about").get("en"), "English under en (bug left this blank)");
        assertEquals("À propos", g.get("nav.about").get("fr"));
    }

    @Test
    void migratingToAnUntranslatedDefaultClearsBaseAndPreservesTheOldDefault(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        Locale de = Locale.of("de");
        // en default (English in base), es suffixed; NO German bundle yet.
        f.store.save("acme", LOCALES, grid("hello", "Hello", "Hola"));

        f.store.migrateDefaultLocale("acme", EN, de);

        // English is preserved as its own suffixed file; the base (now the German default) is cleared — not a
        // stray 0-byte file — the honest result of switching to an untranslated default.
        assertEquals("hello=Hello\n", Files.readString(bundle(f.layout, "acme", "_en"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(bundle(f.layout, "acme", "")), "base cleared (no 0-byte stray)");
        // The editor grid, default-first [de, en, es]: German blank, English/Spanish intact under their columns.
        Map<String, Map<String, String>> g = f.store.load("acme", List.of(de, EN, ES));
        assertEquals("", g.get("hello").get("de"), "untranslated new default is blank");
        assertEquals("Hello", g.get("hello").get("en"));
        assertEquals("Hola", g.get("hello").get("es"));
    }

    @Test
    void migratingWithNoDefaultChangeIsANoOp(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.store.save("acme", LOCALES, grid("k", "V", "Vv"));
        f.store.migrateDefaultLocale("acme", EN, EN);         // same default
        assertEquals("k=V\n", Files.readString(bundle(f.layout, "acme", ""), StandardCharsets.UTF_8));
        assertFalse(Files.exists(bundle(f.layout, "acme", "_en")), "no spurious suffixed file for the default");
    }

    @Test
    void savingOneSiteLeavesAnotherUntouched(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.store.save("globex", LOCALES, grid("brand", "Globex", "Globex"));
        f.store.save("acme", LOCALES, grid("brand", "Acme", "Acme"));

        // acme wrote only under acme/i18n; globex is byte-for-byte unchanged.
        assertEquals("brand=Globex\n",
                Files.readString(bundle(f.layout, "globex", ""), StandardCharsets.UTF_8));
        assertEquals("brand=Acme\n",
                Files.readString(bundle(f.layout, "acme", ""), StandardCharsets.UTF_8));
        // acme's i18n dir contains only its own two bundles (no cross-write).
        assertTrue(Files.exists(bundle(f.layout, "acme", "")));
        assertTrue(Files.exists(bundle(f.layout, "acme", "_es")));
    }

    // --- merge (incremental save) -----------------------------------------------------------------------------

    @Test
    void mergeUpsertsOneCellAndPreservesEveryOtherKey(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.store.save("acme", LOCALES, grid("a", "A", "Aa", "b", "B", "Bb"));

        // The whole point: change ONE cell without rewriting the bundle from a full grid.
        f.store.merge("acme", LOCALES, Map.of("en", Map.of("a", "A-edited")), Set.of());

        Map<String, Map<String, String>> loaded = f.store.load("acme", LOCALES);
        assertEquals("A-edited", loaded.get("a").get("en"), "the edited cell is updated");
        assertEquals("B", loaded.get("b").get("en"), "an untouched key in the SAME file is preserved (merge, not replace)");
        assertEquals("Aa", loaded.get("a").get("es"), "the other locale is untouched");
        assertEquals("Bb", loaded.get("b").get("es"));
    }

    @Test
    void mergeBlankValueRemovesTheKeyFromThatLocaleOnly(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.store.save("acme", LOCALES, grid("g", "Hi", "Hola"));

        // Clearing the es cell drops the key from es alone; en keeps it.
        Map<String, String> esBlank = new LinkedHashMap<>();
        esBlank.put("g", "");
        f.store.merge("acme", LOCALES, Map.of("es", esBlank), Set.of());

        Map<String, Map<String, String>> loaded = f.store.load("acme", LOCALES);
        assertEquals("Hi", loaded.get("g").get("en"), "en still has the key");
        assertEquals("", loaded.get("g").get("es"), "es cell cleared");
        assertFalse(Files.exists(bundle(f.layout, "acme", "_es")), "the now-empty es bundle is deleted, not left 0-byte");
    }

    @Test
    void mergeRemovedKeyDropsFromEveryLocale(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.store.save("acme", LOCALES, grid("keep", "K", "Kk", "drop", "D", "Dd"));

        f.store.merge("acme", LOCALES, Map.of(), Set.of("drop"));

        Map<String, Map<String, String>> loaded = f.store.load("acme", LOCALES);
        assertTrue(loaded.containsKey("keep"));
        assertFalse(loaded.containsKey("drop"), "a removed key is gone from every locale");
        assertEquals("K", loaded.get("keep").get("en"), "the surviving key is intact");
    }

    @Test
    void mergeRemovalWinsOverAnUpsertOfTheSameKey(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.store.save("acme", LOCALES, grid("x", "X", "Xx"));

        // Same key both upserted and removed in one call → removal is authoritative.
        f.store.merge("acme", LOCALES, Map.of("en", Map.of("x", "X-new")), Set.of("x"));

        assertFalse(f.store.load("acme", LOCALES).containsKey("x"), "removal wins over the upsert");
    }

    @Test
    void mergeAddsANewKeyToTheGivenLocaleOnly(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.store.save("acme", LOCALES, grid("a", "A", "Aa"));

        f.store.merge("acme", LOCALES, Map.of("en", Map.of("newk", "NV")), Set.of());

        Map<String, Map<String, String>> loaded = f.store.load("acme", LOCALES);
        assertEquals("NV", loaded.get("newk").get("en"), "new key created in en");
        assertEquals("", loaded.get("newk").get("es"), "and absent (blank gap) in es");
    }

    @Test
    void mergeIgnoresAnUpsertForALocaleTheSiteDoesNotOffer(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.store.save("acme", LOCALES, grid("k", "V", "Vv"));

        // Defense in depth: even if a stray tag reaches the store, merge only iterates the site's own locales, so
        // it can never coin a foreign bundle file (the controller also rejects it up front).
        f.store.merge("acme", LOCALES, Map.of("de", Map.of("k", "German")), Set.of());

        assertFalse(Files.exists(bundle(f.layout, "acme", "_de")), "no bundle for a non-site locale");
    }

    @Test
    void mergeLeavesAnUnaddressedLocaleFileByteForByteUnchanged(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.store.save("acme", LOCALES, grid("a", "A", "Aa"));
        byte[] esBefore = Files.readAllBytes(bundle(f.layout, "acme", "_es"));

        f.store.merge("acme", LOCALES, Map.of("en", Map.of("a", "A2")), Set.of());

        assertArrayEquals(esBefore, Files.readAllBytes(bundle(f.layout, "acme", "_es")),
                "a locale with nothing addressed to it is not rewritten");
    }

    @Test
    void mergeWithNothingToDoWritesNothing(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.store.save("acme", LOCALES, grid("a", "A", "Aa"));
        byte[] enBefore = Files.readAllBytes(bundle(f.layout, "acme", ""));

        f.store.merge("acme", LOCALES, Map.of(), Set.of());   // empty edit

        assertArrayEquals(enBefore, Files.readAllBytes(bundle(f.layout, "acme", "")), "an empty merge is a no-op");
    }

    @Test
    void mergeLeavesASpecialCharacterNeighborKeyIntactThroughTheReadModifyWriteCycle(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        // A neighbor value with '=', ':', tab, newline, unicode, leading space and a backslash.
        String tricky = " a=b : c\td\né中文\\x";
        f.store.save("acme", LOCALES, grid("neighbor", tricky, "ok", "target", "T", "Tt"));

        // Editing a DIFFERENT key rewrites the en file; the tricky neighbor must survive the decode→re-encode.
        f.store.merge("acme", LOCALES, Map.of("en", Map.of("target", "T2")), Set.of());

        Map<String, Map<String, String>> loaded = f.store.load("acme", LOCALES);
        assertEquals(tricky, loaded.get("neighbor").get("en"), "the special-char neighbor round-trips unchanged");
        assertEquals("T2", loaded.get("target").get("en"), "the edited key is updated");
        assertEquals(2, loaded.size(), "the multiline neighbor did not inject a spurious key");
    }

    @Test
    void concurrentMergesToOneSiteDoNotLoseUpdates(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        int n = 60;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                start.await();                                 // maximize the overlap window
                f.store.merge("acme", LOCALES, Map.of("en", Map.of("k" + idx, "v" + idx)), Set.of());
                return null;
            }));
        }
        start.countDown();
        for (Future<?> fut : futures) {
            fut.get();
        }
        pool.shutdown();

        // Without the per-site lock, the read-modify-write would race and drop keys; with it, all 60 survive.
        Map<String, Map<String, String>> loaded = f.store.load("acme", LOCALES);
        assertEquals(n, loaded.size(), "every concurrent merge survived (per-site lock prevents lost updates)");
        for (int i = 0; i < n; i++) {
            assertEquals("v" + i, loaded.get("k" + i).get("en"), "k" + i + " present");
        }
    }

    @Test
    void theDefaultColumnLoadsFromBaseEvenWhenSiteYmlHadTheWrongLocaleOrder(@TempDir Path tmp) throws IOException {
        // The nocturne regression: site.yml had defaultLocale=en but locales=[es,en,fr] (a hand edit that did
        // not reorder), and the base messages.properties held ENGLISH (no messages_en). Because the Site record
        // now normalizes locales default-first, the editor resolves the default (en) column from the BASE file
        // instead of a non-existent messages_en — so it shows the English content, not an empty column.
        Fixture f = fixture(tmp);
        Locale es = Locale.of("es");
        Locale fr = Locale.FRENCH;
        Path i18n = f.layout.siteDir("nocturne").resolve("i18n");
        Files.createDirectories(i18n);
        Files.writeString(bundle(f.layout, "nocturne", ""), "nav.coffees=Coffees\n", StandardCharsets.UTF_8);
        Files.writeString(bundle(f.layout, "nocturne", "_es"), "nav.coffees=Caf\\u00e9s\n", StandardCharsets.UTF_8);
        Files.writeString(bundle(f.layout, "nocturne", "_fr"), "nav.coffees=Caf\\u00e9s\n", StandardCharsets.UTF_8);
        // NOTE: no messages_en.properties — English lives in the base, as in the imported demo site.

        // The Site record repairs the order, so site.locales() is default-first (en, es, fr).
        Site nocturne = new Site("nocturne", "Nocturne", List.of("nocturne.test"), EN, List.of(es, EN, fr), null, null);
        assertEquals(EN, nocturne.locales().get(0), "the Site normalized the default to first");

        Map<String, Map<String, String>> loaded = f.store.load(nocturne.id(), nocturne.locales());
        assertEquals("Coffees", loaded.get("nav.coffees").get("en"), "the default (en) column reads the base file");
        assertEquals("Cafés", loaded.get("nav.coffees").get("es"), "es reads its suffixed file");
        assertEquals("Cafés", loaded.get("nav.coffees").get("fr"), "fr reads its suffixed file");
    }
}
