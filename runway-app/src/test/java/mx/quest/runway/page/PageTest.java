package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PageTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");

    @Test
    void withSlugAppliesIdAndDefaultsPath() {
        Page page = new Page(null, null, null, null, null, null, null, null, false, false, null, null).withSlug("home");
        assertEquals("home", page.slug());
        assertEquals("/home", page.path());
        assertEquals("_master", page.template());
        assertTrue(page.published());
    }

    @Test
    void draftIsNotPublished() {
        Page page = new Page("d", "/d", Map.of(), List.of(), Map.of(), Map.of(), "", "_master", true, false, null, null);
        assertFalse(page.published());
    }

    @Test
    void titleAndDescriptionResolvePerLocaleWithFallback() {
        Page page = new Page("a", "/a", Map.of(), List.of(EN, ES),
                Map.of("en", "About", "es", "Acerca"), Map.of("en", "desc"), "about", "_master", false, false, null, null);
        assertEquals("About", page.titleFor(EN));
        assertEquals("Acerca", page.titleFor(ES));
        assertEquals("desc", page.descriptionFor(EN));
        assertEquals("desc", page.descriptionFor(ES), "missing locale falls back to a present value");
        assertEquals("", new Page("b", "/b", Map.of(), List.of(), Map.of(), Map.of(), "", "_master", false, false, null, null).titleFor(EN));
    }

    @Test
    void pathForUsesLocalizedOverrideElseDefault() {
        Page page = new Page("a", "/about", Map.of("es", "/acerca-de"), List.of(EN, ES),
                Map.of(), Map.of(), "", "_master", false, false, null, null);
        assertEquals("/about", page.pathFor(EN));
        assertEquals("/acerca-de", page.pathFor(ES));
    }

    @Test
    void availabilityFollowsLocalesOrSiteDefault() {
        Page declared = new Page("a", "/a", Map.of(), List.of(EN, ES), Map.of(), Map.of(), "", "_master", false, false, null, null);
        assertTrue(declared.hasLocale(ES, EN));
        assertTrue(declared.hasLocale(EN, EN));

        Page implicit = new Page("b", "/b", Map.of(), List.of(), Map.of(), Map.of(), "", "_master", false, false, null, null);
        assertTrue(implicit.hasLocale(EN, EN), "empty locales ⇒ the site default only");
        assertFalse(implicit.hasLocale(ES, EN));
    }

    @Test
    void primaryLocaleIsSiteDefaultWhenAvailableElseFirstDeclared() {
        assertEquals(EN, new Page("a", "/a", Map.of(), List.of(EN, ES), Map.of(), Map.of(), "", "_master", false, false, null, null).primaryLocale(EN));
        assertEquals(ES, new Page("b", "/b", Map.of(), List.of(ES), Map.of(), Map.of(), "", "_master", false, false, null, null).primaryLocale(EN));
        assertEquals(EN, new Page("c", "/c", Map.of(), List.of(), Map.of(), Map.of(), "", "_master", false, false, null, null).primaryLocale(EN));
    }

    @Test
    void paramsDefaultToEmptyAndTheLegacyConstructorOmitsThem() {
        // The 12-arg (pre-params) form still compiles and yields an empty params map.
        Page legacy = new Page("a", "/a", Map.of(), List.of(), Map.of(), Map.of(), "", "_master", false, false, null, null);
        assertTrue(legacy.params().isEmpty(), "the legacy constructor gives an empty params map");
        // A null params argument to the canonical form also normalizes to empty.
        Page nulled = new Page("b", "/b", Map.of(), List.of(), Map.of(), Map.of(), "", "_master", false, false, null, null, null);
        assertTrue(nulled.params().isEmpty());
    }

    @Test
    void paramsAreCarriedAndSanitized() {
        Map<String, String> raw = new java.util.LinkedHashMap<>();
        raw.put("  analytics ", "UA-123"); // key trimmed
        raw.put("", "dropped");            // blank key dropped
        raw.put("empty", null);            // null value → ""
        Page page = new Page("a", "/a", Map.of(), List.of(), Map.of(), Map.of(), "", "_master", false, false, null, null, raw);
        assertEquals("UA-123", page.params().get("analytics"), "the key is trimmed");
        assertFalse(page.params().containsKey(""), "a blank key is dropped");
        assertEquals("", page.params().get("empty"), "a null value becomes empty");
        assertEquals(2, page.params().size());
    }

    @Test
    void withSlugAndWithoutSlugPreserveParams() {
        Page page = new Page(null, null, null, null, null, null, null, null, false, false, null, null,
                Map.of("k", "v")).withSlug("home");
        assertEquals("v", page.params().get("k"), "withSlug carries params");
        assertEquals("v", page.withoutSlug().params().get("k"), "withoutSlug carries params");
    }
}
