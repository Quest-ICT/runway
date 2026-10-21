package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import org.junit.jupiter.api.Test;

class LocaleUrlsTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");
    private static final Site SITE =
            new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN, ES), null, SiteStatus.ACTIVE);

    @Test
    void parseStripsASupportedNonDefaultPrefix() {
        LocaleUrls.Prefix p = LocaleUrls.parse("/es/about", SITE);
        assertTrue(p.present());
        assertEquals(ES, p.locale());
        assertEquals("/about", p.restPath());
    }

    @Test
    void parseRecognizesTheDefaultPrefix() {
        LocaleUrls.Prefix p = LocaleUrls.parse("/en/about", SITE);
        assertTrue(p.present());
        assertEquals(EN, p.locale());
        assertEquals("/about", p.restPath());
    }

    @Test
    void parseLeavesUnprefixedPathsAlone() {
        LocaleUrls.Prefix about = LocaleUrls.parse("/about", SITE);
        assertFalse(about.present());
        assertNull(about.locale());
        assertEquals("/about", about.restPath());

        assertFalse(LocaleUrls.parse("/", SITE).present());
        assertFalse(LocaleUrls.parse("/xx/about", SITE).present(), "unknown 2-letter segment is not a locale");
    }

    @Test
    void urlForUsesUnprefixedDefaultAndPrefixedOthers() {
        assertEquals("/about", LocaleUrls.urlFor("/about", EN, EN));
        assertEquals("/es/about", LocaleUrls.urlFor("/about", ES, EN));
    }

    /** A site with the given default + locale set, matching the constructor the fixture above uses. */
    private static Site site(Locale defaultLocale, List<Locale> locales) {
        return new Site("acme", "Acme", List.of("acme.test"), defaultLocale, locales, null, SiteStatus.ACTIVE);
    }

    /**
     * A language code that is not exactly two letters must round-trip.
     *
     * <p>{@code urlFor} builds a prefix from ANY language, while {@code parse} used to slice a fixed two
     * characters — so a site using a three-letter ISO 639-3 code advertised {@code hreflang} URLs that its own
     * router then 404'd. Advertising a URL that does not resolve is worse than omitting the alternate: an
     * engine follows it and records a dead end.
     */
    @Test
    void aThreeLetterLanguageCodeRoundTripsThroughTheUrlAndBack() {
        Locale hawaiian = Locale.forLanguageTag("haw");
        Site site = site(Locale.ENGLISH, List.of(Locale.ENGLISH, hawaiian));

        String url = LocaleUrls.urlFor("/about", hawaiian, Locale.ENGLISH);
        assertEquals("/haw/about", url, "the URL is built from the full language code");

        LocaleUrls.Prefix parsed = LocaleUrls.parse(url, site);
        assertTrue(parsed.present(), "the router must recognise the prefix it advertises");
        assertEquals(hawaiian.getLanguage(), parsed.locale().getLanguage());
        assertEquals("/about", parsed.restPath());
    }

    /** The bare locale root must parse too — {@code /haw} with no trailing path. */
    @Test
    void aBareLocaleRootParsesForAnyCodeLength() {
        Locale hawaiian = Locale.forLanguageTag("haw");
        Site site = site(Locale.ENGLISH, List.of(Locale.ENGLISH, hawaiian));

        LocaleUrls.Prefix parsed = LocaleUrls.parse("/haw", site);
        assertTrue(parsed.present());
        assertEquals("/", parsed.restPath(), "a bare locale root resolves to the site root");
    }

    /**
     * The site's own locale list is the allowlist, so an arbitrary first segment is NOT a locale prefix — it is
     * an ordinary path. This is what keeps segment-based parsing traversal-safe by construction.
     */
    @Test
    void anUnknownFirstSegmentIsAPathNotALocale() {
        Site site = site(Locale.ENGLISH, List.of(Locale.ENGLISH, Locale.forLanguageTag("es")));

        for (String path : List.of("/products", "/de/about", "/..%2Fetc", "/x")) {
            LocaleUrls.Prefix parsed = LocaleUrls.parse(path, site);
            assertTrue(!parsed.present(), path + " must not be read as a locale prefix");
            assertEquals(path, parsed.restPath(), "the whole path is the rest when there is no prefix");
        }
    }
}
