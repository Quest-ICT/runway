package mx.quest.runway.icon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.IconHints;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.Test;

/** The icon head contributor: discovery links from present slots + manifest link + theme-color. */
class IconHeadContributorTest {

    private final IconHeadContributor contributor = new IconHeadContributor();

    private static HeadContext contextWith(IconHints hints) {
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
        PageMeta page = new PageMeta("home", "Home", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        return new HeadContext(site, page, Locale.ENGLISH, "https://acme.test/", "https://acme.test/",
                List.of(), false, false, mx.quest.runway.spi.model.SeoTags.empty(), hints);
    }

    @Test
    void aSiteWithNoIconsContributesNothing() {
        assertEquals("", contributor.render(contextWith(IconHints.NONE)));
    }

    @Test
    void emitsHeadLinksForTheFaviconsAndAppleTouchButNotThePngSizes() {
        IconHints hints = new IconHints(
                List.of(SiteIcon.SVG_FAVICON.key(), SiteIcon.FAVICON_ICO.key(), SiteIcon.APPLE_TOUCH.key(),
                        SiteIcon.ICON_192.key()),
                false, null);
        String out = contributor.render(contextWith(hints));
        assertTrue(out.contains("<link rel=\"icon\" type=\"image/svg+xml\" href=\"/favicon.svg\">"), out);
        assertTrue(out.contains("<link rel=\"icon\" sizes=\"any\" href=\"/favicon.ico\">"), out);
        assertTrue(out.contains("<link rel=\"apple-touch-icon\" href=\"/apple-touch-icon.png\">"), out);
        assertFalse(out.contains("icon-192"), "PNG sizes are manifest icons, not head links");
    }

    @Test
    void emitsTheManifestLinkAndThemeColorWhenSet() {
        String out = contributor.render(contextWith(new IconHints(List.of(), true, "#0d6efd")));
        assertTrue(out.contains("<link rel=\"manifest\" href=\"/manifest.webmanifest\">"), out);
        assertTrue(out.contains("<meta name=\"theme-color\" content=\"#0d6efd\">"), out);
    }

    @Test
    void themeColorIsAttributeEscaped() {
        String out = contributor.render(contextWith(new IconHints(List.of(), false, "\"><script>")));
        assertFalse(out.contains("<script>"), out);
        assertTrue(out.contains("&quot;&gt;&lt;script&gt;"), out);
    }
}
