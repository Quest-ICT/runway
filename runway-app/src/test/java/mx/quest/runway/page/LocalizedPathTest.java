package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.Alternate;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Per-locale URL overrides: sanitisation, URL generation, locale-scoped routing, collision, and canonical/hreflang. */
class LocalizedPathTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");

    private static Page about() {
        return new Page("about", "/about", Map.of("es", "/acerca-de"), List.of(EN, ES),
                Map.of("en", "About", "es", "Acerca"), Map.of(), "", "_master", false, false, null, null);
    }

    // --- Page sanitisation -------------------------------------------------------------------

    @Test
    void blankAndUnsafeLocalizedOverridesAreDropped() {
        Page page = new Page("p", "/p", Map.of("es", "/acerca-de", "fr", "  ", "de", "/../evil"),
                List.of(EN), Map.of(), Map.of(), "", "_master", false, false, null, null);
        assertEquals("/acerca-de", page.pathFor(ES));            // kept
        assertEquals("/p", page.pathFor(Locale.of("fr")));        // blank dropped → falls back to path
        assertEquals("/p", page.pathFor(Locale.GERMAN));          // unsafe dropped → falls back to path
    }

    // --- URL generation ----------------------------------------------------------------------

    @Test
    void urlForHonorsLocalizedSlugUnderTheLocalePrefix() {
        Page page = about();
        assertEquals("/about", LocaleUrls.urlFor(page, EN, EN));          // default: unprefixed, own path
        assertEquals("/es/acerca-de", LocaleUrls.urlFor(page, ES, EN));   // non-default: prefix + localized slug
    }

    @Test
    void urlForFallsBackToTheDefaultPathWhenNoOverride() {
        Page page = new Page("c", "/contact", Map.of(), List.of(EN, ES), Map.of(), Map.of(), "", "_master", false, false, null, null);
        assertEquals("/es/contact", LocaleUrls.urlFor(page, ES, EN));     // shared path under the prefix
    }

    // --- Locale-scoped routing + collision + unsafe-skip -------------------------------------

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static PageStore storeWith(Path tmp, String... metaByName) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        Path templates = layout.siteDir("acme").resolve("templates");
        for (int i = 0; i + 1 < metaByName.length; i += 2) {
            write(templates.resolve(metaByName[i] + ".meta.yml"), metaByName[i + 1]);
        }
        return new PageStore(layout, new YamlStore());
    }

    @Test
    void routingMatchesOnlyTheRequestLocalesPath(@TempDir Path tmp) throws IOException {
        PageStore store = storeWith(tmp, "about", "path: /about\nlocales: [en, es]\nlocalizedPaths:\n  es: /acerca-de\n");

        assertEquals("about", store.findByPath("acme", "/about", EN).orElseThrow().slug());
        assertEquals("about", store.findByPath("acme", "/acerca-de", ES).orElseThrow().slug());
        assertTrue(store.findByPath("acme", "/about", ES).isEmpty(), "es URL is /acerca-de, not /about");
        assertTrue(store.findByPath("acme", "/acerca-de", EN).isEmpty(), "the es slug is not an en URL");
    }

    @Test
    void collidingLocalizedPathsFailClosed(@TempDir Path tmp) throws IOException {
        PageStore store = storeWith(tmp,
                "a", "path: /a\nlocales: [en, es]\nlocalizedPaths:\n  es: /acerca-de\n",
                "b", "path: /b\nlocales: [en, es]\nlocalizedPaths:\n  es: /acerca-de\n");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> store.findByPath("acme", "/acerca-de", ES));
        assertTrue(ex.getMessage().contains("ambiguous"), ex::getMessage);
    }

    @Test
    void pageWithAnUnsafeDefaultPathIsSkipped(@TempDir Path tmp) throws IOException {
        PageStore store = storeWith(tmp,
                "good", "path: /good\nlocales: [en]\n",
                "bad", "path: /../evil\nlocales: [en]\n");

        assertEquals(List.of("good"), store.findAll("acme").stream().map(Page::slug).toList());
        assertTrue(store.findByPath("acme", "/../evil", EN).isEmpty());
    }

    // --- Canonical / hreflang derive from the localized URLs ---------------------------------

    @Test
    void defaultLocaleOverrideWinsUniformlyOverThePathField() {
        // localizedPaths for the DEFAULT locale: pathFor(default) returns the override, so the
        // override IS the canonical default URL and the bare `path` field is no longer a served URL.
        Page page = new Page("p", "/home", Map.of("en", "/about-us"), List.of(EN), Map.of(), Map.of(), "", "_master", false, false, null, null);
        assertEquals("/about-us", page.pathFor(EN));
        assertEquals("/about-us", LocaleUrls.urlFor(page, EN, EN));
    }

    @Test
    void defaultLocaleOverrideIsRoutableAndShadowsThePathField(@TempDir Path tmp) throws IOException {
        PageStore store = storeWith(tmp, "p", "path: /home\nlocales: [en]\nlocalizedPaths:\n  en: /about-us\n");
        assertEquals("p", store.findByPath("acme", "/about-us", EN).orElseThrow().slug());
        assertTrue(store.findByPath("acme", "/home", EN).isEmpty(), "the path field is shadowed by the en override");
    }

    @Test
    void defaultPathFallThroughCanCollideWithAnotherPagesOverride(@TempDir Path tmp) throws IOException {
        // A serves /acerca-de in every locale (its plain path); B overrides es to the same URL.
        PageStore store = storeWith(tmp,
                "a", "path: /acerca-de\nlocales: [en]\n",
                "b", "path: /b\nlocales: [en, es]\nlocalizedPaths:\n  es: /acerca-de\n");

        assertThrows(IllegalStateException.class, () -> store.findByPath("acme", "/acerca-de", ES));
        assertEquals("a", store.findByPath("acme", "/acerca-de", EN).orElseThrow().slug(), "no collision in the en namespace");
    }

    @Test
    void theSameLocalizedSlugUnderDifferentLocalesDoesNotCollide(@TempDir Path tmp) throws IOException {
        PageStore store = storeWith(tmp,
                "a", "path: /a\nlocales: [en, es]\nlocalizedPaths:\n  es: /shared\n",
                "b", "path: /b\nlocales: [en, fr]\nlocalizedPaths:\n  fr: /shared\n");

        assertEquals("a", store.findByPath("acme", "/shared", ES).orElseThrow().slug());
        assertEquals("b", store.findByPath("acme", "/shared", Locale.of("fr")).orElseThrow().slug());
    }

    @Test
    void canonicalAndAlternatesUseLocalizedUrls() {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN, ES), "https://acme.test");
        Map<String, Object> esModel = new RenderModelBuilder()
                .build(site, about(), ES, "__site__/acme/_master", "/es/acerca-de", "https://acme.test");

        assertEquals("https://acme.test/es/acerca-de", esModel.get("canonical"));
        assertEquals("https://acme.test/about", esModel.get("xDefault"), "x-default = primary (en) URL");

        @SuppressWarnings("unchecked")
        List<Alternate> alternates = (List<Alternate>) esModel.get("alternates");
        assertEquals("https://acme.test/about", urlOf(alternates, EN));
        assertEquals("https://acme.test/es/acerca-de", urlOf(alternates, ES));
    }

    private static String urlOf(List<Alternate> alternates, Locale locale) {
        return alternates.stream().filter(a -> a.locale().equals(locale)).map(Alternate::url).findFirst().orElseThrow();
    }
}
