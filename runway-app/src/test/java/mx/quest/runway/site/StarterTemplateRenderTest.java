package mx.quest.runway.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.SiteTemplateResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.convert.support.DefaultConversionService;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;

/**
 * Renders the <b>real seeded starter template</b> through the real engine.
 *
 * <h2>Why this exists separately from {@code TenantSeederTest}</h2>
 * That test asserts the seeded files <em>contain</em> the right bindings. Containing them is not the same as
 * them working: decoupled-logic bindings fail <b>silently</b>. A selector that matches nothing produces no
 * error and no log line — the attribute is simply never applied, and the page renders looking almost right
 * with a dead link, an empty cell, or in this case a {@code <title>} that never got its value. A string
 * assertion over the source cannot tell the difference. Only rendering can.
 *
 * <p>Two things here are specifically the kind that break quietly: the {@code th:if} on the description sits
 * on an element inside a fragment that is pulled in by {@code th:replace}, and the {@code lang} binding is on
 * the page's own {@code <html>} rather than the master's — both easy to get subtly wrong and impossible to
 * notice from the file contents.
 */
class StarterTemplateRenderTest {

    private static final GenericApplicationContext APP_CONTEXT = freshContext();

    private static GenericApplicationContext freshContext() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        return context;
    }

    private static StorageLayout layoutIn(Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return StorageLayout.from(props);
    }

    /** Seeds a tenant from the REAL classpath starter, then renders one of its pages. */
    private static String render(Path tmp, String page, Locale locale, String pageTitle, String pageDescription,
                                 String activeNav) throws IOException {
        StorageLayout layout = layoutIn(tmp);
        new TenantSeeder(layout, new SeedTemplateStore(layout, new YamlStore())).seed("acme");

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new SiteTemplateResolver(layout));

        Context ctx = new Context(locale);
        ctx.setVariable("master", SiteTemplateResolver.PREFIX + "acme/_master");
        ctx.setVariable("site", new StubSite("Acme Coffee"));
        ctx.setVariable("pageTitle", pageTitle);
        ctx.setVariable("pageDescription", pageDescription);
        ctx.setVariable("activeNav", activeNav);
        ctx.setVariable("year", "2026");
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(APP_CONTEXT, DefaultConversionService.getSharedInstance()));
        return engine.process(SiteTemplateResolver.renderSpec("acme", SiteTemplateResolver.PREFIX + "acme/" + page), ctx);
    }

    /** Minimal stand-in for the site view — the master only ever asks it for the display name. */
    public static final class StubSite {
        private final String displayName;

        StubSite(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * The defect that motivated this whole change: every page of a new site rendered the identical
     * {@code <title>Site</title>}. The page-specific part must come first, because that is the half that
     * survives truncation in a tab, a search result and a bookmark.
     */
    @Test
    void theTitleIsPerPageAndLeadsWithThePageName(@TempDir Path tmp) throws IOException {
        String home = render(tmp, "index", Locale.ENGLISH, "Home", "The home page.", "home");

        // Matched on the title's TEXT, not exact markup: decoupled logic selects by id, so the id stays on
        // the rendered element. Asserting the raw tag would fail for a reason that has nothing to do with the
        // behaviour under test.
        assertTrue(home.contains(">Home · Acme Coffee</title>"),
                () -> "expected a per-page title; got:\n" + head(home));
        assertFalse(home.contains(">Site</title>"), "the hardcoded placeholder title must be gone");
    }

    /** The second page must render its own title through the same shared master, not the home page's. */
    @Test
    void asecondPageRendersItsOwnTitleThroughTheSameMaster(@TempDir Path tmp) throws IOException {
        String about = render(tmp, "about", Locale.ENGLISH, "About", "About this site.", "about");

        assertTrue(about.contains(">About · Acme Coffee</title>"),
                () -> "the about page must carry its own title; got:\n" + head(about));
        assertTrue(about.contains("Acme Coffee"), "the master's chrome came through");
        assertEquals(1, count(about, "</head>"), () -> "exactly one <head>; got:\n" + about);
    }

    /** A description must actually reach the rendered head — the binding sits inside a replaced fragment. */
    @Test
    void theDescriptionIsEmittedWhenThePageHasOne(@TempDir Path tmp) throws IOException {
        String home = render(tmp, "index", Locale.ENGLISH, "Home", "Freshly roasted coffee in Portland.", "home");

        assertTrue(home.contains("name=\"description\""), () -> "description element missing; got:\n" + head(home));
        assertTrue(home.contains("Freshly roasted coffee in Portland."),
                () -> "the description value never bound; got:\n" + head(home));
    }

    /**
     * An EMPTY description is worse than none: engines read it as the page describing itself as nothing, rather
     * than falling back to their own snippet. The element must disappear entirely, which is what the
     * {@code th:if} is for — and a {@code th:if} on an element inside a replaced fragment is exactly the kind
     * of binding that silently does nothing.
     */
    @Test
    void theDescriptionElementIsDroppedEntirelyWhenThePageHasNone(@TempDir Path tmp) throws IOException {
        String home = render(tmp, "index", Locale.ENGLISH, "Home", "", "home");

        assertFalse(home.contains("name=\"description\""),
                () -> "an empty description must be omitted, not emitted blank; got:\n" + head(home));
    }

    /**
     * The rendered document must declare the locale actually being rendered. Hardcoding {@code lang="en"} tells
     * screen readers and search engines the wrong language on every translated page — and Runway is multilingual
     * by default, so this is the common case, not the edge case.
     */
    @Test
    void theDocumentLanguageFollowsTheRenderedLocale(@TempDir Path tmp) throws IOException {
        String spanish = render(tmp, "index", Locale.forLanguageTag("es"), "Inicio", "Café recién tostado.", "home");

        assertTrue(spanish.contains("lang=\"es\""), () -> "expected lang=\"es\"; got:\n" + head(spanish));
        assertFalse(spanish.contains("lang=\"en\""), "the hardcoded English language must be gone");
    }

    /** The nav highlight is driven by the page's own activeNav, so one shared header stays correct everywhere. */
    @Test
    void theNavigationHighlightsThePageBeingViewed(@TempDir Path tmp) throws IOException {
        String about = render(tmp, "about", Locale.ENGLISH, "About", "About.", "about");

        int aboutLink = about.indexOf("href=\"/about\"");
        int homeLink = about.indexOf("href=\"/\"");
        assertTrue(aboutLink >= 0 && homeLink >= 0, () -> "both nav links must render; got:\n" + about);
        assertTrue(about.substring(Math.min(aboutLink, about.length()), Math.min(aboutLink + 120, about.length()))
                        .contains("is-active")
                    || about.substring(Math.max(0, aboutLink - 120), aboutLink).contains("is-active"),
                () -> "the about link must be marked active; got:\n" + about);
        assertFalse(about.substring(Math.max(0, homeLink - 120), Math.min(homeLink + 60, about.length()))
                        .contains("is-active"),
                "the home link must NOT be active on the about page");
    }

    /**
     * Self-containment: a tenant must carry everything it needs to render. A starter that quietly depends on a
     * remote font or image breaks on an air-gapped install, under a strict CSP, and whenever the third party
     * goes away — and the bundled demo sites shipped for a long time pointing every image at a public
     * placeholder host, so this is a mistake this project has actually made.
     */
    @Test
    void theStarterLoadsNothingFromOffTheSite(@TempDir Path tmp) throws IOException {
        String home = render(tmp, "index", Locale.ENGLISH, "Home", "Home.", "home");

        assertFalse(home.contains("http://") || home.contains("https://"),
                () -> "the starter must not reference any external URL; got:\n" + home);
        assertFalse(home.contains("<img"), "the starter ships no images, so none can 404 or leak a referrer");
    }

    private static String head(String html) {
        int end = html.indexOf("</head>");
        return end < 0 ? html : html.substring(0, end + 7);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
