package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.Alternate;
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.Test;

/**
 * {@link ContentUrls} — the single URL authority the head AND the sitemap share: canonical honors the
 * per-content override, while alternates/x-default always build REAL routing URLs (the deliberate asymmetry —
 * a sitemap lists addresses that serve, never overrides).
 */
class ContentUrlsTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");
    private static final Locale FR = Locale.of("fr");
    private static final String BASE = "https://acme.test";
    private static final SiteView SITE = new SiteView("acme", "Acme", EN, List.of(EN, ES), BASE);

    private static Page page(String canonical) {
        return new Page("about", "/about", Map.of("es", "/acerca"), List.of(EN, ES),
                Map.of("en", "About"), Map.of(), null, "_master", false, false, canonical, null, Map.of());
    }

    @Test
    void canonicalHonorsTheOverrideButAlternatesStayReal() {
        Page page = page("https://elsewhere.example/original");

        assertEquals("https://elsewhere.example/original", ContentUrls.canonicalUrl(page, EN, EN, BASE),
                "the head's canonical follows the override");
        List<Alternate> alternates = ContentUrls.alternates(page, SITE, BASE);
        assertEquals(2, alternates.size());
        assertEquals(BASE + "/about", alternates.get(0).url(), "alternates are REAL routing URLs — never the override");
        assertEquals(BASE + "/es/acerca", alternates.get(1).url(), "localized slug + locale prefix");
        assertEquals(BASE + "/about", ContentUrls.xDefault(page, SITE, BASE), "x-default is the primary's real URL");
    }

    @Test
    void aRootRelativeOverrideResolvesAgainstTheBase() {
        assertEquals(BASE + "/somewhere", ContentUrls.canonicalUrl(page("/somewhere"), EN, EN, BASE));
    }

    @Test
    void withoutAnOverrideTheCanonicalIsTheLocaleAwareRealUrl() {
        assertEquals(BASE + "/es/acerca", ContentUrls.canonicalUrl(page(null), ES, EN, BASE));
        assertEquals(BASE + "/about", ContentUrls.canonicalUrl(page(null), EN, EN, BASE));
    }

    @Test
    void emptyLocalesMeansTheSiteDefaultAlone() {
        Page defaultOnly = new Page("plain", "/plain", Map.of(), List.of(), Map.of(), Map.of(), null,
                "_master", false, false, null, null, Map.of());
        assertEquals(List.of(EN), ContentUrls.availableLocales(defaultOnly, SITE));
        assertEquals(1, ContentUrls.alternates(defaultOnly, SITE, BASE).size());
    }

    @Test
    void declaredLocalesAreIntersectedWithTheSitesOwn() {
        // Declared [en, fr] on an en+es site: fr is never served by the router, so no URL may advertise it.
        Page enFr = new Page("post", "/post", Map.of(), List.of(EN, FR), Map.of("en", "P"), Map.of(), null,
                "_master", false, false, null, null, Map.of());
        assertEquals(List.of(EN), ContentUrls.availableLocales(enFr, SITE), "fr dropped — the site can't serve it");
        assertEquals(1, ContentUrls.alternates(enFr, SITE, BASE).size(), "no /fr alternate is ever advertised");
    }

    @Test
    void aForeignOnlyDeclarationClampsToTheSiteDefault() {
        // Declared [fr] alone: nothing survives the intersection → the default-locale URL space, and the
        // x-default must clamp to the default too (the primary would be an unserved locale).
        Page frOnly = new Page("post", "/post", Map.of(), List.of(FR), Map.of("en", "P"), Map.of(), null,
                "_master", false, false, null, null, Map.of());
        assertEquals(List.of(EN), ContentUrls.availableLocales(frOnly, SITE));
        assertEquals(BASE + "/post", ContentUrls.xDefault(frOnly, SITE, BASE), "x-default clamped to the default");
    }

    /**
     * SPI 1.0.0: declared {@code [fr, es]} on an {@code en}+{@code es} site — the primary ({@code fr}) is
     * not served and the content is available in {@code es} alone. x-default used to clamp to the site default and
     * name the unprefixed {@code /post}, an address the content does not exist at (so llms.txt, via
     * {@code CatalogEntry.pathFor}, linked a page the site does not serve in that locale). It now clamps to the first
     * AVAILABLE locale — always one of the content's own alternates.
     */
    @Test
    void anUnservedPrimaryClampsToTheFirstAvailableLocaleNotTheSiteDefault() {
        Page frEs = new Page("post", "/post", Map.of(), List.of(FR, ES), Map.of("es", "P"), Map.of(), null,
                "_master", false, false, null, null, Map.of());
        assertEquals(List.of(ES), ContentUrls.availableLocales(frEs, SITE));
        String xDefault = ContentUrls.xDefault(frEs, SITE, BASE);
        assertEquals(BASE + "/es/post", xDefault);
        assertTrue(ContentUrls.alternates(frEs, SITE, BASE).stream().anyMatch(a -> a.url().equals(xDefault)),
                "x-default is one of the content's own alternates");
    }

    /**
     * {@code hreflang} has exactly ONE formatter, so the head and the sitemap cannot describe the same page
     * with different tags.
     *
     * <p>They previously used {@code getLanguage()} and {@code toLanguageTag()} respectively — identical for a
     * plain {@code es}, divergent the moment a region appears. Two surfaces disagreeing about the same page is
     * what makes an engine discard the whole annotation set, and nothing would have failed: they would simply
     * drift the first time a site used a regional locale.
     *
     * <p>Asserted on a REGIONAL locale on purpose. A test using {@code en}/{@code es} passes under both
     * implementations and would prove nothing.
     */
    @Test
    void hreflangUsesTheFullLanguageTagSoBothSurfacesAgree() {
        Locale brazilian = Locale.forLanguageTag("pt-BR");
        Alternate alternate = new Alternate(brazilian, "https://acme.test/pt/sobre");

        assertEquals("pt-BR", alternate.hreflang(),
                "hreflang must keep the region — dropping it mislabels a regional variant as the base language");
        assertFalse(alternate.hreflang().equals(brazilian.getLanguage()),
                "…and must therefore NOT be the bare language, which is what the head used to emit");
    }

    /**
     * The URL prefix is deliberately the language only, so a regional locale still lives under one directory.
     * That difference from {@code hreflang} is by design, not drift — pinned so nobody "fixes" it later.
     */
    @Test
    void theUrlPrefixStaysLanguageOnlyEvenForARegionalLocale() {
        Locale brazilian = Locale.forLanguageTag("pt-BR");

        assertEquals("/pt/sobre", LocaleUrls.urlFor("/sobre", brazilian, Locale.ENGLISH),
                "one directory per language, regardless of region");
    }
}
