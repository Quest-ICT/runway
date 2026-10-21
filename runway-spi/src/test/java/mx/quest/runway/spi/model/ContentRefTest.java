package mx.quest.runway.spi.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ContentRef}'s dispatch surface and shape normalization. The dispatch helpers must mirror the kernel
 * page's semantics EXACTLY (localized-override-else-default path fallback, empty locales = site-default only,
 * primary-locale preference) — that parity is what lets virtual content run the same locale-dispatch ladder as
 * a real page. Pure unit tests: no Spring, no kernel.
 */
class ContentRefTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");
    private static final Locale FR = Locale.of("fr");

    private static ContentRef ref(Map<String, String> localizedPaths, List<Locale> locales) {
        return ContentRef.of("my-post", "/blog/my-post", localizedPaths, locales,
                Map.of("en", "My post", "es", "Mi entrada"), Map.of("en", "A post"),
                "__plugin__/blog/entry-classic", Instant.parse("2026-07-16T12:00:00Z"));
    }

    // --- dispatch-surface parity with Page ----------------------------------------------------------------

    @Test
    void pathForReturnsLocalizedOverrideElseDefault() {
        ContentRef r = ref(Map.of("es", "/blog/mi-entrada"), List.of(EN, ES));
        assertEquals("/blog/mi-entrada", r.pathFor(ES), "localized override wins");
        assertEquals("/blog/my-post", r.pathFor(EN), "no override → the default path (the Page fallback rule)");
        assertEquals("/blog/my-post", r.pathFor(FR), "an unlisted locale also falls back — availability is hasLocale's job, not pathFor's");
    }

    @Test
    void emptyLocalesMeansSiteDefaultOnly() {
        ContentRef r = ref(Map.of(), List.of());
        assertTrue(r.hasLocale(EN, EN), "empty locales → available in the site default");
        assertFalse(r.hasLocale(ES, EN), "empty locales → NOT available in any other locale");
    }

    @Test
    void declaredLocalesGovernAvailability() {
        ContentRef r = ref(Map.of(), List.of(EN, ES));
        assertTrue(r.hasLocale(ES, FR), "a declared locale is available regardless of the site default");
        assertFalse(r.hasLocale(FR, FR), "an undeclared locale is not available even as site default");
    }

    @Test
    void primaryLocalePrefersTheSiteDefault() {
        assertEquals(EN, ref(Map.of(), List.of(EN, ES)).primaryLocale(EN), "default available → default");
        assertEquals(ES, ref(Map.of(), List.of(ES, FR)).primaryLocale(EN), "default unavailable → first declared");
        assertEquals(EN, ref(Map.of(), List.of()).primaryLocale(EN), "empty → default");
    }

    @Test
    void titleAndDescriptionFallBackToAnyPresentValue() {
        // An ORDERED input map makes the "falls back to a present value" contract deterministic to assert
        // (Map.of is unordered; the fallback picks the map's first value, whichever that is).
        Map<String, String> titles = new LinkedHashMap<>();
        titles.put("en", "My post");
        titles.put("es", "Mi entrada");
        ContentRef r = ContentRef.of("my-post", "/blog/my-post", Map.of(), List.of(EN, ES, FR),
                titles, Map.of("en", "A post"), "__plugin__/blog/entry-classic", null);
        assertEquals("My post", r.titleFor(EN));
        assertEquals("Mi entrada", r.titleFor(ES));
        assertEquals("My post", r.titleFor(FR), "untranslated locale falls back to a present value");
        assertEquals("A post", r.descriptionFor(FR), "same rule for descriptions");
    }

    // --- normalization (shape only) ------------------------------------------------------------------------

    @Test
    void identityFieldsAreRequiredAndTrimmed() {
        assertThrows(NullPointerException.class, () -> ContentRef.of(null, "/p", Map.of(), List.of(),
                Map.of(), Map.of(), "t", null));
        assertThrows(IllegalArgumentException.class, () -> ContentRef.of("  ", "/p", Map.of(), List.of(),
                Map.of(), Map.of(), "t", null));
        assertThrows(IllegalArgumentException.class, () -> ContentRef.of("id", "/p", Map.of(), List.of(),
                Map.of(), Map.of(), " ", null));
        ContentRef r = ContentRef.of(" id ", " /p ", Map.of(), List.of(), Map.of(), Map.of(), " t ", null);
        assertEquals("id", r.id());
        assertEquals("/p", r.path());
        assertEquals("t", r.bodyTemplate());
    }

    @Test
    void blankLocalizedPathsAreDroppedSoTheLocaleFallsBack() {
        // A blank override must NOT become a routing key for "" — it must fall back to the default path,
        // exactly as the page model drops unusable localized paths.
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("es", "  ");
        raw.put("fr", "/blog/mon-billet");
        raw.put(null, "/x");
        ContentRef r = ref(raw, List.of(EN, ES, FR));
        assertEquals("/blog/my-post", r.pathFor(ES), "blank override dropped → default path");
        assertEquals("/blog/mon-billet", r.pathFor(FR), "real override kept (trimmed)");
        assertEquals(1, r.localizedPaths().size(), "null keys and blank values are gone");
    }

    @Test
    void nullCollectionsNormalizeToEmpty() {
        ContentRef r = new ContentRef("id", "/p", null, null, null, null, null, "t",
                true, null, false, null, null, null);
        assertEquals(Map.of(), r.localizedPaths());
        assertEquals(List.of(), r.locales());
        assertEquals(Map.of(), r.title());
        assertEquals(Map.of(), r.description());
        assertEquals(Map.of(), r.params());
        assertEquals("", r.titleFor(EN), "no titles → empty, never null");
    }

    @Test
    void paramsKeepInsertionOrderAndCoerceNullValues() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("zeta", "1");
        raw.put("alpha", null);
        raw.put(" ", "dropped");
        ContentRef r = new ContentRef("id", "/p", Map.of(), List.of(), Map.of(), Map.of(), null, "t",
                true, null, false, null, null, raw);
        assertEquals(List.of("zeta", "alpha"), List.copyOf(r.params().keySet()), "insertion order preserved");
        assertEquals("", r.params().get("alpha"), "null value coerced to empty (the params convention)");
    }

    @Test
    void collectionsAreImmutable() {
        ContentRef r = ref(Map.of("es", "/blog/mi-entrada"), List.of(EN, ES));
        assertThrows(UnsupportedOperationException.class, () -> r.localizedPaths().put("fr", "/x"));
        assertThrows(UnsupportedOperationException.class, () -> r.locales().add(FR));
        assertThrows(UnsupportedOperationException.class, () -> r.params().put("k", "v"));
    }

    @Test
    void ofFactoryBuildsAPublishedRefWithSafeDefaults() {
        ContentRef r = ref(Map.of(), List.of(EN));
        assertTrue(r.published(), "the of() factory builds published refs");
        assertFalse(r.noindex());
        assertEquals(Instant.parse("2026-07-16T12:00:00Z"), r.updatedAt());
    }
}
