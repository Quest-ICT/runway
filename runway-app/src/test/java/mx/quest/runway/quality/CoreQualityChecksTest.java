package mx.quest.runway.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageViews;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteViews;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.PageView;
import mx.quest.runway.spi.model.Severity;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The kernel's structural {@link PageQualityCheck}s. Each is a pure function of its {@link PageView}
 * (the duplicate check also reads the site's page list), so they are exercised directly: the finding codes, their
 * deliberate severities (only {@code title.missing} and {@code img.alt-missing} are errors — the two things that
 * are clearly broken), the boundaries of every threshold, and the markup each one must NOT flag.
 */
class CoreQualityChecksTest {

    @TempDir
    Path external;

    private static final Site SITE = new Site("acme", "Acme", List.of("acme.test"), Locale.ENGLISH,
            List.of(Locale.ENGLISH, Locale.forLanguageTag("es")), null, SiteStatus.ACTIVE);

    private static PageView view(String title, String description, String html) {
        Page page = new Page("home", "/", Map.of(), List.of(),
                title == null ? Map.of() : Map.of("en", title),
                description == null ? Map.of() : Map.of("en", description), "", "_master", false, false, null, null);
        PageMeta meta = PageViews.toView(page, Locale.ENGLISH, Locale.ENGLISH);
        return new PageView(SiteViews.toView(SITE), meta, Locale.ENGLISH, "https://acme.test/", html);
    }

    private static PageView html(String body) {
        return view("Title", "d".repeat(80), "<!DOCTYPE html><html><head><title>t</title></head><body>" + body
                + "</body></html>");
    }

    private static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::code).toList();
    }

    private static Severity only(List<Finding> findings, String code) {
        List<Finding> matching = findings.stream().filter(f -> f.code().equals(code)).toList();
        assertEquals(1, matching.size(), "exactly one " + code + " in " + findings);
        return matching.get(0).severity();
    }

    // --- title ---------------------------------------------------------------------------------------

    @Test
    void aMissingTitleIsAnErrorAndAnOverlongOneAWarning() {
        TitlePresenceCheck check = new TitlePresenceCheck();
        assertEquals(Severity.ERROR, only(check.inspect(view(null, null, "")), "title.missing"));
        assertEquals(Severity.ERROR, only(check.inspect(view("   ", null, "")), "title.missing"));
        assertTrue(check.inspect(view("x".repeat(TitlePresenceCheck.MAX_LENGTH), null, "")).isEmpty(),
                "exactly the limit is fine");
        assertEquals(Severity.WARNING,
                only(check.inspect(view("x".repeat(TitlePresenceCheck.MAX_LENGTH + 1), null, "")), "title.too-long"));
    }

    // --- description ---------------------------------------------------------------------------------

    @Test
    void theDescriptionIsPresentAndInRange() {
        DescriptionCheck check = new DescriptionCheck();
        assertEquals(Severity.WARNING, only(check.inspect(view("t", null, "")), "description.missing"));
        assertEquals(Severity.WARNING, only(check.inspect(view("t", " ", "")), "description.missing"));
        assertEquals(Severity.INFO,
                only(check.inspect(view("t", "d".repeat(DescriptionCheck.MIN_LENGTH - 1), "")), "description.too-short"));
        assertTrue(check.inspect(view("t", "d".repeat(DescriptionCheck.MIN_LENGTH), "")).isEmpty());
        assertTrue(check.inspect(view("t", "d".repeat(DescriptionCheck.MAX_LENGTH), "")).isEmpty());
        assertEquals(Severity.WARNING,
                only(check.inspect(view("t", "d".repeat(DescriptionCheck.MAX_LENGTH + 1), "")), "description.too-long"));
    }

    // --- headings ------------------------------------------------------------------------------------

    @Test
    void headingStructure() {
        HeadingStructureCheck check = new HeadingStructureCheck();
        assertTrue(check.inspect(html("<h1>A</h1><h2>B</h2><h3>C</h3><h2>D</h2><h3>E</h3>")).isEmpty(),
                "a clean outline, including stepping back up, is clean");
        assertEquals(Severity.WARNING, only(check.inspect(html("<h2>B</h2>")), "h1.missing"));
        assertEquals(Severity.WARNING, only(check.inspect(html("<h1>A</h1><h1>B</h1>")), "h1.multiple"));
        List<Finding> skipped = check.inspect(html("<h1>A</h1><h2>B</h2><h4>C</h4><h6>D</h6>"));
        assertEquals(Severity.WARNING, only(skipped, "heading.skipped"));
        assertTrue(skipped.get(0).message().contains("<h2> to <h4>"), "the first skip is named");
        assertEquals(List.of("h1.missing"), codes(check.inspect(html("<p>no headings</p>"))));
        assertTrue(check.inspect(view("t", "d", "")).stream().allMatch(f -> f.code().equals("h1.missing")),
                "an empty render reports only the missing h1");
    }

    // --- images --------------------------------------------------------------------------------------

    @Test
    void anImageWithoutAltIsAnErrorButDecorativeOrHiddenImagesAreNot() {
        ImageAltCheck check = new ImageAltCheck();
        assertTrue(check.inspect(html("<img src=a.png alt=\"A cat\"><img src=b.png alt=\"\">"
                + "<img src=c.png aria-hidden=\"true\"><img src=d.png role=\"presentation\"><img src=e.png role=none>"))
                .isEmpty(), "described, decorative (alt=\"\") and hidden images are fine");
        List<Finding> missing = check.inspect(html("<img src=a.png><img src=b.png alt=x><img src=c.png>"));
        assertEquals(Severity.ERROR, only(missing, "img.alt-missing"));
        assertTrue(missing.get(0).message().startsWith("2 image"), "counted once, with the number");
        assertTrue(missing.get(0).message().contains("a.png"), "the first offender is named");
    }

    // --- links ---------------------------------------------------------------------------------------

    @Test
    void emptyAndGenericLinkText() {
        LinkTextCheck check = new LinkTextCheck();
        assertTrue(check.inspect(html("<a href=/x>Our pricing</a><a href=/y aria-label=\"Home\"><i></i></a>"
                + "<a href=/z><img src=l.png alt=\"Logo\"></a><a href=/t title=\"Twitter\"><i></i></a>"
                + "<a name=anchor></a><a href=/l aria-labelledby=lbl></a>")).isEmpty(),
                "named links, and an anchor without href, are fine");
        assertEquals(Severity.WARNING, only(check.inspect(html("<a href=/x><i class=icon></i></a>")), "link.empty"));
        assertEquals(Severity.WARNING, only(check.inspect(html("<a href=/x><img src=i.png alt=\"\"></a>")), "link.empty"));
        for (String generic : List.of("Click here", "click here!", "Read more…", "¡Haz clic aquí!", "aquí", "Leer más",
                "En savoir plus", "  MORE  ")) {
            assertEquals(Severity.INFO, only(check.inspect(html("<a href=/x>" + generic + "</a>")), "link.generic"),
                    generic);
        }
        assertTrue(check.inspect(html("<a href=/x>Read more about pricing</a>")).isEmpty(),
                "descriptive text that merely starts like a generic phrase is fine");
        List<Finding> both = check.inspect(html("<a href=/a></a><a href=/b>here</a><a href=/c>here</a>"));
        assertEquals(List.of("link.empty", "link.generic"), codes(both));
        assertTrue(both.get(1).message().startsWith("2 link"));
    }

    // --- thin content --------------------------------------------------------------------------------

    @Test
    void thinContentCountsOnlyTheMainRegionsWords() {
        ThinContentCheck check = new ThinContentCheck();
        String words149 = "word ".repeat(ThinContentCheck.MIN_WORDS - 1);
        String words150 = "word ".repeat(ThinContentCheck.MIN_WORDS);
        assertEquals(Severity.INFO, only(check.inspect(html("<main>" + words149 + "</main>")), "content.thin"));
        assertTrue(check.inspect(html("<main>" + words150 + "</main>")).isEmpty());
        // The master's chrome outside <main> does not count, nor do scripts or punctuation.
        assertEquals(Severity.INFO, only(check.inspect(html("<header>" + words150 + "</header><main>short "
                + "<script>" + words150 + "</script> — · !</main>")), "content.thin"));
        // Without a <main>, the body counts.
        assertTrue(check.inspect(html(words150)).isEmpty());
        assertEquals(3, ThinContentCheck.countWords("año 2026 — niños !"));
    }

    // --- duplicates ----------------------------------------------------------------------------------

    @Test
    void duplicatesAreFoundAmongOtherPublishedPagesOfTheLocaleOnly() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        Path templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("home.meta.yml"), "path: /\ntitle:\n  en: Home\n");
        Files.writeString(templates.resolve("twin.meta.yml"),
                "path: /twin\ntitle:\n  en: '  welcome   HOME '\ndescription:\n  en: Same words\n");
        Files.writeString(templates.resolve("draft.meta.yml"),
                "path: /draft\ndraft: true\ntitle:\n  en: Welcome home\n");
        Files.writeString(templates.resolve("spanish.meta.yml"),
                "path: /es\nlocales: [es]\ntitle:\n  es: Welcome home\n");
        DuplicateMetaCheck check = new DuplicateMetaCheck(new PageStore(layout, new YamlStore()));

        List<Finding> found = check.inspect(view("Welcome home", "same   words", ""));
        assertEquals(Severity.WARNING, only(found, "title.duplicate"));
        assertEquals(Severity.WARNING, only(found, "description.duplicate"));
        assertEquals("the title is also used by: twin", found.get(0).message(),
                "only the published, same-locale twin — not the draft, not the Spanish-only page");

        assertTrue(check.inspect(view("Home", null, "")).isEmpty(),
                "the page itself (same slug, its own live version) is never its own duplicate");
        assertTrue(check.inspect(view("Unique", "", "")).isEmpty());
        assertTrue(check.inspect(view(null, null, "")).isEmpty(), "absence is the presence checks' business");
    }

    @Test
    void theKernelRegistryCollectsEveryCoreCheckWithUniqueKeys() {
        PageQualityCheckRegistry registry = new PageQualityCheckRegistry(List.of(new TitlePresenceCheck(),
                new DescriptionCheck(), new HeadingStructureCheck(), new ImageAltCheck(), new LinkTextCheck(),
                new ThinContentCheck()));
        List<Finding> all = registry.inspect(view(null, null, "<html><body><img src=x></body></html>"));
        assertEquals(Severity.ERROR, only(all, "title.missing"));
        assertEquals(Severity.ERROR, only(all, "img.alt-missing"));
        assertEquals(2, all.stream().filter(f -> f.severity() == Severity.ERROR).count(),
                "only the two clearly-broken findings are errors");
    }
}
