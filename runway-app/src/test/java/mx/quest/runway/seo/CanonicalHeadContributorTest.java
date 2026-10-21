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

/** The kernel canonical/robots/hreflang head contributor — the "for free" technical-SEO markup. */
class CanonicalHeadContributorTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");

    private final CanonicalHeadContributor contributor = new CanonicalHeadContributor();

    private static HeadContext context(String canonical, String xDefault, List<Alternate> alternates,
                                       boolean noindex) {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN, ES), "https://acme.test");
        PageMeta page = new PageMeta("about", "About", "", List.of(EN, ES), EN, true, noindex);
        return new HeadContext(site, page, EN, canonical, xDefault, alternates, false, noindex,
                SeoTags.empty());
    }

    @Test
    void aStableKey() {
        assertEquals("runway-core-canonical", contributor.key());
    }

    @Test
    void emitsTheCanonicalLink() {
        String out = contributor.render(context("https://acme.test/about", null, List.of(), false));
        assertTrue(out.contains("<link rel=\"canonical\" href=\"https://acme.test/about\">"), out);
        assertFalse(out.contains("robots"), "an indexable page emits no robots meta");
    }

    @Test
    void emitsNoindexOnlyWhenNotIndexable() {
        assertFalse(contributor.render(context("https://acme.test/x", null, List.of(), false)).contains("robots"));
        String hidden = contributor.render(context("https://acme.test/x", null, List.of(), true));
        assertTrue(hidden.contains("<meta name=\"robots\" content=\"noindex, follow\">"), hidden);
    }

    @Test
    void emitsHreflangAlternatesAndXDefault() {
        List<Alternate> alts = List.of(
                new Alternate(EN, "https://acme.test/about"),
                new Alternate(ES, "https://acme.test/es/about"));
        String out = contributor.render(context("https://acme.test/about", "https://acme.test/about", alts, false));
        assertTrue(out.contains("<link rel=\"alternate\" hreflang=\"en\" href=\"https://acme.test/about\">"), out);
        assertTrue(out.contains("<link rel=\"alternate\" hreflang=\"es\" href=\"https://acme.test/es/about\">"), out);
        assertTrue(out.contains("<link rel=\"alternate\" hreflang=\"x-default\" href=\"https://acme.test/about\">"), out);
    }

    @Test
    void contributesNothingWhenThereIsNothingToEmit() {
        assertEquals("", contributor.render(context(null, null, List.of(), false)));
    }

    @Test
    void aHostileCanonicalCannotBreakOutOfTheAttribute() {
        String out = contributor.render(context("https://acme.test/\"><script>", null, List.of(), false));
        assertFalse(out.contains("<script>"), "the raw tag must not survive: " + out);
        assertTrue(out.contains("&quot;&gt;&lt;script&gt;"), out);
    }

    /** A context for the two robots inputs alone — canonical and alternates are irrelevant to those tests. */
    private static HeadContext context(boolean noindex, boolean nosnippet) {
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN), "https://acme.test");
        PageMeta page = new PageMeta("about", "About", "", List.of(EN), EN, true, noindex, null,
                mx.quest.runway.spi.model.Provenance.NONE, nosnippet);
        return new HeadContext(site, page, EN, "https://acme.test/about", null, List.of(), false, noindex,
                SeoTags.empty());
    }

    /**
     * {@code nosnippet} is emitted, and is INDEPENDENT of {@code noindex}.
     *
     * <p>A page that stays indexed but is not quoted is the whole point: it keeps its ranking while opting out
     * of being excerpted, which is also the documented control over whether generative features may use its
     * text. Before this the only positions available were fully usable or out of the index entirely.
     */
    @Test
    void nosnippetIsEmittedIndependentlyOfNoindex() {
        String indexedButQuiet = contributor.render(context(false, true));
        assertTrue(indexedButQuiet.contains("content=\"nosnippet\""), indexedButQuiet);
        assertFalse(indexedButQuiet.contains("noindex"), "the page stays indexable: " + indexedButQuiet);
    }

    /** Both together must be ONE meta — a second robots meta is not additive; an engine may take either. */
    @Test
    void noindexAndNosnippetCombineIntoASingleRobotsMeta() {
        String both = contributor.render(context(true, true));

        assertEquals(1, both.split("name=\"robots\"", -1).length - 1, "exactly one robots meta: " + both);
        assertTrue(both.contains("noindex, follow") && both.contains("nosnippet"), both);
    }

    /** A page with neither must emit no robots meta at all — silence is the default, not an empty directive. */
    @Test
    void anOrdinaryPageEmitsNoRobotsMeta() {
        assertFalse(contributor.render(context(false, false)).contains("name=\"robots\""));
    }
}
