package mx.quest.runway.seo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import mx.quest.runway.spi.model.Alternate;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SeoTags;
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.Test;

/**
 * The kernel SEO head contributor: page-derived OpenGraph/Twitter social cards (emitted even for a
 * site with no SEO config), config-driven image/type/card/verification, verbatim extra-head, and
 * attribute escaping.
 */
class SeoHeadContributorTest {

    private final SeoHeadContributor contributor = new SeoHeadContributor();

    private static final SiteView SITE =
            new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
    private static final PageMeta PAGE =
            new PageMeta("about", "About", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);

    private static HeadContext contextWith(SeoTags seo) {
        return new HeadContext(SITE, PAGE, Locale.ENGLISH, "https://acme.test/about", "https://acme.test/about",
                List.of(), false, false, seo);
    }

    @Test
    void aStableKey() {
        assertEquals("runway-core-seo", contributor.key());
    }

    @Test
    void anUnconfiguredSiteStillEmitsAPageDerivedCard() {
        // Old behaviour returned "" for a site with no SEO config; now every page unfurls with a
        // title+url+site_name card derived from the page, so demo/unconfigured sites are shareable.
        String out = contributor.render(contextWith(SeoTags.empty()));
        assertTrue(out.contains("<meta property=\"og:title\" content=\"About\">"), out);
        assertTrue(out.contains("<meta property=\"og:url\" content=\"https://acme.test/about\">"), out);
        assertTrue(out.contains("<meta property=\"og:site_name\" content=\"Acme\">"), out);
        assertTrue(out.contains("<meta property=\"og:type\" content=\"website\">"), out);
        assertTrue(out.contains("<meta property=\"og:locale\" content=\"en\">"), out);
        // No share image → the card is summary (a summary_large_image with no image renders blank).
        assertTrue(out.contains("<meta name=\"twitter:card\" content=\"summary\">"), out);
        assertTrue(out.contains("<meta name=\"twitter:title\" content=\"About\">"), out);
        // No image configured, and the default page description is blank → those tags are omitted.
        assertFalse(out.contains("og:image"), out);
        assertFalse(out.contains("og:description"), out);
        assertFalse(out.contains("google-site-verification"), out);
    }

    @Test
    void configuredImageTypeCardAndVerificationAreEmitted() {
        SeoTags seo = new SeoTags("https://acme.test/og.png", "article", "summary", "@acme",
                "gtoken", "btoken", null);
        String out = contributor.render(contextWith(seo));
        // Config overrides the derived defaults for type + card...
        assertTrue(out.contains("<meta property=\"og:type\" content=\"article\">"), out);
        assertTrue(out.contains("<meta name=\"twitter:card\" content=\"summary\">"), out);
        // ...the image drives BOTH og:image and twitter:image...
        assertTrue(out.contains("<meta property=\"og:image\" content=\"https://acme.test/og.png\">"), out);
        assertTrue(out.contains("<meta name=\"twitter:image\" content=\"https://acme.test/og.png\">"), out);
        // ...and verification + twitter:site come straight from config.
        assertTrue(out.contains("<meta name=\"twitter:site\" content=\"@acme\">"), out);
        assertTrue(out.contains("<meta name=\"google-site-verification\" content=\"gtoken\">"), out);
        assertTrue(out.contains("<meta name=\"msvalidate.01\" content=\"btoken\">"), out);
    }

    @Test
    void twitterCardIsSummaryLargeImageOnlyWhenAShareImageExists() {
        // with an image → large card
        String withImage = contributor.render(contextWith(
                new SeoTags("https://acme.test/og.png", null, null, null, null, null, null)));
        assertTrue(withImage.contains("<meta name=\"twitter:card\" content=\"summary_large_image\">"), withImage);
        // configured summary_large_image but NO image → downgraded to summary (would otherwise render blank)
        String noImage = contributor.render(contextWith(
                new SeoTags(null, null, "summary_large_image", null, null, null, null)));
        assertTrue(noImage.contains("<meta name=\"twitter:card\" content=\"summary\">"), noImage);
        assertFalse(noImage.contains("summary_large_image"), "no large-image card without an image: " + noImage);
    }

    @Test
    void descriptionIsEmittedOnlyWhenThePageHasOne() {
        PageMeta described = new PageMeta("about", "About", "The about page.",
                List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        HeadContext ctx = new HeadContext(SITE, described, Locale.ENGLISH, "https://acme.test/about",
                "https://acme.test/about", List.of(), false, false, SeoTags.empty());
        String out = contributor.render(ctx);
        assertTrue(out.contains("<meta property=\"og:description\" content=\"The about page.\">"), out);
        assertTrue(out.contains("<meta name=\"twitter:description\" content=\"The about page.\">"), out);
    }

    @Test
    void ogLocaleAlternatesExcludeTheCurrentLocaleAndDeduplicate() {
        List<Alternate> alts = List.of(
                new Alternate(Locale.ENGLISH, "https://acme.test/en/about"),
                new Alternate(Locale.FRENCH, "https://acme.test/fr/about"),
                new Alternate(Locale.ENGLISH, "https://acme.test/en/about-dup")); // duplicate language
        HeadContext ctx = new HeadContext(SITE, PAGE, Locale.forLanguageTag("es"), "https://acme.test/es/about",
                "https://acme.test/about", alts, false, false, SeoTags.empty());
        String out = contributor.render(ctx);
        assertTrue(out.contains("<meta property=\"og:locale\" content=\"es\">"), out);
        assertTrue(out.contains("<meta property=\"og:locale:alternate\" content=\"en\">"), out);
        assertTrue(out.contains("<meta property=\"og:locale:alternate\" content=\"fr\">"), out);
        // the current locale never appears as an alternate, and the duplicate language collapses to one
        assertEquals(1, countOccurrences(out, "og:locale:alternate\" content=\"en\">"), out);
        assertFalse(out.contains("og:locale:alternate\" content=\"es\">"), out);
    }

    @Test
    void structuredValuesAreAttributeEscaped() {
        // A hostile value must not break out of content="…".
        SeoTags seo = new SeoTags("x\"><script>alert(1)</script>", null, null, null, null, null, null);
        String out = contributor.render(contextWith(seo));
        assertFalse(out.contains("<script>"), "the raw tag must not survive: " + out);
        assertTrue(out.contains("&quot;&gt;&lt;script&gt;"), out);
    }

    @Test
    void suppressesTheDerivedCardWhenExtraHeadHandAuthorsOgTags() {
        SeoTags seo = new SeoTags(null, null, null, null, null, null,
                "<meta property=\"og:title\" content=\"Brand\">");
        String out = contributor.render(contextWith(seo));
        assertTrue(out.contains("content=\"Brand\""), "the hand-authored tag is kept: " + out);
        assertEquals(1, countOccurrences(out, "og:title"), "no duplicate og:title: " + out);
        assertFalse(out.contains("twitter:card"), "no derived twitter card is added on top: " + out);
    }

    @Test
    void extraHeadIsInjectedVerbatim() {
        SeoTags seo = new SeoTags(null, null, null, null, null, null,
                "<link rel=\"me\" href=\"https://social.example/@acme\">");
        String out = contributor.render(contextWith(seo));
        assertTrue(out.contains("<link rel=\"me\" href=\"https://social.example/@acme\">"), out);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        int at;
        while ((at = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from = at + needle.length();
        }
        return count;
    }
}
