package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.convert.support.DefaultConversionService;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;

/**
 * The load-bearing Thymeleaf SPIKE for page-as-entry. Proves, against a real
 * engine over a temp site, that the design's unproven assumptions actually hold:
 * <ul>
 *   <li>pulling a master region fragment <b>applies the master's {@code .th.xml}</b>;</li>
 *   <li>whole-{@code <head>} replace yields a single valid head;</li>
 *   <li>decoupled-logic binding works from the site-scoped file resolver;</li>
 *   <li>two sites sharing a template name render their <b>own</b> chrome (cache on);</li>
 *   <li>targeted eviction makes an edited template re-render.</li>
 * </ul>
 * If any assertion fails, the design's fallback (shared-chrome logic in generated rules) applies
 * before building the rest of the templating layer.
 */
class RenderSpikeTest {

    private static StorageLayout layoutIn(Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return StorageLayout.from(props);
    }

    private static final GenericApplicationContext APP_CONTEXT = freshContext();

    private static GenericApplicationContext freshContext() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        return context;
    }

    private static SpringTemplateEngine engineOver(StorageLayout layout) {
        // Match production: the SpEL-based SpringTemplateEngine (the core OGNL engine isn't on the
        // classpath). SpEL gets its Spring context via the eval-context variable in renderAbout.
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new SiteTemplateResolver(layout));
        return engine;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** Lays down a master (_master) and an about page for a site, with the given brand+content placeholders. */
    private static void seedSite(StorageLayout layout, String siteId, String brandPlaceholder, String extra) throws IOException {
        Path templates = layout.siteDir(siteId).resolve("templates");
        write(templates.resolve("_master.html"), """
                <!DOCTYPE html>
                <html><head><title>Master Title</title><meta id="m" name="m" content="x"></head>
                <body>
                <header id="site-header"><span id="brand">%s</span></header>
                <main id="content">master placeholder</main>
                <footer id="site-footer">Master Footer</footer>
                </body></html>""".formatted(brandPlaceholder));
        write(templates.resolve("_master.th.xml"), """
                <?xml version="1.0"?>
                <thlogic>
                  <attr sel="#brand" th:text="${siteName}"/>
                </thlogic>""");
        write(templates.resolve("about.html"), """
                <!DOCTYPE html>
                <html><head><title>About Page Title</title></head>
                <body>
                <header id="site-header">PAGE HEADER PLACEHOLDER</header>
                <main id="content"><h1 id="about-title">TITLE_PLACEHOLDER</h1><p id="extra">%s</p></main>
                <footer id="site-footer">PAGE FOOTER PLACEHOLDER</footer>
                </body></html>""".formatted(extra));
        write(templates.resolve("about.th.xml"), """
                <?xml version="1.0"?>
                <thlogic>
                  <attr sel="head" th:replace="~{${master} :: head}"/>
                  <attr sel="#site-header" th:replace="~{${master} :: #site-header}"/>
                  <attr sel="#site-footer" th:replace="~{${master} :: #site-footer}"/>
                  <attr sel="#about-title" th:text="${title}"/>
                </thlogic>""");
    }

    private static String renderAbout(SpringTemplateEngine engine, String siteId, String siteName, String title) {
        Context ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("master", SiteTemplateResolver.PREFIX + siteId + "/_master");
        ctx.setVariable("siteName", siteName);
        ctx.setVariable("title", title);
        // A standalone SpringTemplateEngine needs the Spring evaluation context for SpEL.
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(APP_CONTEXT, DefaultConversionService.getSharedInstance()));
        return engine.process(SiteTemplateResolver.renderSpec(siteId, SiteTemplateResolver.PREFIX + siteId + "/about"), ctx);
    }

    @Test
    void pageAsEntryPullsMasterChromeAndAppliesMasterLogic(@TempDir Path tmp) throws IOException {
        StorageLayout layout = layoutIn(tmp);
        seedSite(layout, "acme", "BRAND_PLACEHOLDER", "EXTRA");
        String html = renderAbout(engineOver(layout), "acme", "Acme Corp", "About Us");

        // The master's .th.xml ran on the pulled #site-header (brand <- siteName).
        assertTrue(html.contains("Acme Corp"), () -> "master fragment logic must apply; got:\n" + html);
        assertFalse(html.contains("BRAND_PLACEHOLDER"), "master logic replaced the brand placeholder");
        // Page's own decoupled binding ran.
        assertTrue(html.contains("About Us"));
        // Whole-<head> replaced from the master, exactly one head.
        assertTrue(html.contains("Master Title"));
        assertEquals(1, countOccurrences(html, "</head>"), () -> "exactly one <head>; got:\n" + html);
        // Footer pulled from the master; page placeholders gone.
        assertTrue(html.contains("Master Footer"));
        assertFalse(html.contains("PAGE HEADER PLACEHOLDER"));
        assertFalse(html.contains("PAGE FOOTER PLACEHOLDER"));
        // The page keeps its own #content.
        assertTrue(html.contains("EXTRA"));
    }

    @Test
    void siteScopedNamesKeepTenantsIsolatedWithCacheOn(@TempDir Path tmp) throws IOException {
        StorageLayout layout = layoutIn(tmp);
        seedSite(layout, "acme", "ACME_PH", "X");
        seedSite(layout, "globex", "GLOBEX_PH", "Y");
        var engine = engineOver(layout); // cacheable resolver, shared engine

        String acme = renderAbout(engine, "acme", "Acme Corp", "A");
        String globex = renderAbout(engine, "globex", "Globex LLC", "B");

        assertTrue(acme.contains("Acme Corp") && !acme.contains("Globex LLC"));
        assertTrue(globex.contains("Globex LLC") && !globex.contains("Acme Corp"),
                "site-scoped names must prevent cross-tenant cache bleed");
    }

    @Test
    void anEditedTemplateIsReParsedAndEvictionIsByName(@TempDir Path tmp) throws IOException {
        StorageLayout layout = layoutIn(tmp);
        seedSite(layout, "acme", "PH", "VERSION_ONE");
        var engine = engineOver(layout);
        String name = SiteTemplateResolver.PREFIX + "acme/about";
        java.util.function.BooleanSupplier cached = () -> engine.getCacheManager().getTemplateCache().keySet()
                .stream().anyMatch(key -> name.equals(key.getTemplate()));

        assertTrue(renderAbout(engine, "acme", "Acme", "T").contains("VERSION_ONE"));
        assertTrue(cached.getAsBoolean(), "the parse is cached (cache ON)");

        // Edit the page's static content on disk: the cached parse is no longer valid, so it is re-read at once
        // (the ETag folds the file's bytes, so serving the old parse would pair a new ETag with an old body).
        seedSite(layout, "acme", "PH", "VERSION_TWO");
        assertTrue(renderAbout(engine, "acme", "Acme", "T").contains("VERSION_TWO"), "an edited file is re-parsed");

        // Targeted eviction still works by the site-scoped name (console saves use it to free the entry at once).
        engine.clearTemplateCacheFor(name);
        assertTrue(!cached.getAsBoolean(), "eviction by site-scoped name drops the entry");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
