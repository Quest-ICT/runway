package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

class TemplateCacheServiceTest {

    private static StorageLayout layoutIn(Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return StorageLayout.from(props);
    }

    private static SpringTemplateEngine engineOver(StorageLayout layout) {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new SiteTemplateResolver(layout));
        return engine;
    }

    /** A static (no {@code .th.xml}) page, so rendering needs no SpEL context. */
    private static void writePage(StorageLayout layout, String siteId, String version) throws IOException {
        Path file = layout.siteDir(siteId).resolve("templates").resolve("about.html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<!DOCTYPE html><html><head><title>t</title></head><body><p id=\"v\">"
                + version + "</p></body></html>", StandardCharsets.UTF_8);
    }

    private static String render(SpringTemplateEngine engine) {
        return engine.process(SiteTemplateResolver.renderSpec("acme", SiteTemplateResolver.PREFIX + "acme/about"), new Context(Locale.ENGLISH));
    }

    /**
     * Whether the engine holds a parse of {@code siteId/name}. Eviction is asserted on the cache itself: since a
     * cached parse is also dropped when its file changes on disk ({@link FileStampValidity}), "the edit shows" can
     * no longer tell an eviction from a stale-file check.
     */
    private static boolean isCached(SpringTemplateEngine engine, String siteId, String name) {
        String template = SiteTemplateResolver.PREFIX + siteId + "/" + name;
        return engine.getCacheManager().getTemplateCache().keySet().stream()
                .anyMatch(key -> template.equals(key.getTemplate()));
    }

    @Test
    void evictPageDropsThatPagesParse(@TempDir Path tmp) throws IOException {
        StorageLayout layout = layoutIn(tmp);
        SpringTemplateEngine engine = engineOver(layout);
        TemplateCacheService cache = new TemplateCacheService(engine);

        writePage(layout, "acme", "V1");
        writePage(layout, "acme", "contact", "<p>C1</p>");
        assertTrue(render(engine).contains("V1"));
        render(engine, "acme", "contact");
        assertTrue(isCached(engine, "acme", "about"), "precondition: the cache is on");

        cache.evictPage("acme", "about");

        assertFalse(isCached(engine, "acme", "about"), "targeted eviction drops the page's parse");
        assertTrue(isCached(engine, "acme", "contact"), "... and only that page's");
    }

    @Test
    void anEditOnDiskShowsWithoutAnyEviction(@TempDir Path tmp) throws IOException {
        StorageLayout layout = layoutIn(tmp);
        SpringTemplateEngine engine = engineOver(layout);

        writePage(layout, "acme", "V1");
        assertTrue(render(engine).contains("V1"));
        assertTrue(isCached(engine, "acme", "about"), "precondition: the cache is on");

        writePage(layout, "acme", "V2"); // same size, written at once: the racily-new content check catches it
        assertTrue(render(engine).contains("V2"), "a stale parse is never served once its file changed");
    }

    @Test
    void siteRestoredEventClearsTheCache(@TempDir Path tmp) throws IOException {
        StorageLayout layout = layoutIn(tmp);
        SpringTemplateEngine engine = engineOver(layout);
        TemplateCacheService cache = new TemplateCacheService(engine);

        writePage(layout, "acme", "V1");
        writePage(layout, "beta", "about", "<p>B1</p>");
        render(engine); // populate cache
        render(engine, "beta", "about");

        cache.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.RESTORED));
        assertFalse(isCached(engine, "acme", "about"), "a restore clears the template cache");
        assertFalse(isCached(engine, "beta", "about"), "... all of it (a restore replaces files wholesale)");
    }

    private static void writePage(StorageLayout layout, String siteId, String name, String body) throws IOException {
        Path file = layout.siteDir(siteId).resolve("templates").resolve(name + ".html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<!DOCTYPE html><html><head><title>t</title></head><body>" + body + "</body></html>",
                StandardCharsets.UTF_8);
    }

    private static String render(SpringTemplateEngine engine, String siteId, String name) {
        return engine.process(SiteTemplateResolver.renderSpec(siteId, SiteTemplateResolver.PREFIX + siteId + "/" + name), new Context(Locale.ENGLISH));
    }

    @Test
    void evictSiteDropsThatSitesTemplatesAndNoOtherSites(@TempDir Path tmp) throws IOException {
        StorageLayout layout = layoutIn(tmp);
        SpringTemplateEngine engine = engineOver(layout);
        TemplateCacheService cache = new TemplateCacheService(engine);

        writePage(layout, "acme", "about", "<p>A1</p>");
        writePage(layout, "acme", "contact", "<p>C1</p>");
        writePage(layout, "acme2", "about", "<p>B1</p>"); // shares the "acme" prefix but is another site
        render(engine, "acme", "about");
        render(engine, "acme", "contact");
        render(engine, "acme2", "about");

        assertEquals(2, cache.evictSite("acme"), "both of acme's pages, nothing else");
        assertFalse(isCached(engine, "acme", "about"));
        assertFalse(isCached(engine, "acme", "contact"));
        assertTrue(isCached(engine, "acme2", "about"), "acme2 is not acme: still cached");
    }

    @Test
    void evictSiteAlsoDropsAFragmentTheSitesPagePulledIn(@TempDir Path tmp) throws IOException {
        StorageLayout layout = layoutIn(tmp);
        SpringTemplateEngine engine = engineOver(layout);
        TemplateCacheService cache = new TemplateCacheService(engine);

        writePage(layout, "acme", "frag", "<div th:fragment=\"f\">F1</div>");
        Path page = layout.siteDir("acme").resolve("templates").resolve("home.html");
        Files.writeString(page, "<!DOCTYPE html><html><body><div id=\"slot\">x</div></body></html>");
        // Decoupled logic: the include names the fragment through a variable (a literal __site__ would be
        // pre-processed).
        Files.writeString(page.resolveSibling("home.th.xml"),
                "<?xml version=\"1.0\"?><thlogic><attr sel=\"#slot\" th:replace=\"~{${frag} :: f}\"/></thlogic>");
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("frag", SiteTemplateResolver.PREFIX + "acme/frag");
        assertTrue(engine.process(SiteTemplateResolver.renderSpec("acme", SiteTemplateResolver.PREFIX + "acme/home"), context).contains("F1"));

        assertTrue(isCached(engine, "acme", "frag"), "precondition: the fragment's parse is cached");

        cache.evictSite("acme");
        assertFalse(isCached(engine, "acme", "frag"), "the fragment, cached under its owner page, is evicted too");
        assertFalse(isCached(engine, "acme", "home"));
    }

    @Test
    void anUpdatedEventEvictsOnlyThatSite(@TempDir Path tmp) throws IOException {
        StorageLayout layout = layoutIn(tmp);
        SpringTemplateEngine engine = engineOver(layout);
        TemplateCacheService cache = new TemplateCacheService(engine);

        writePage(layout, "acme", "V1");
        writePage(layout, "beta", "about", "<p>B1</p>");
        render(engine);
        render(engine, "beta", "about");

        cache.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.UPDATED));
        assertFalse(isCached(engine, "acme", "about"), "an update (a reload) re-reads the site's templates");
        assertTrue(isCached(engine, "beta", "about"), "another site stays warm");
    }

    @Test
    void evictSiteToleratesABlankIdAndAnEmptyCache(@TempDir Path tmp) {
        TemplateCacheService cache = new TemplateCacheService(engineOver(layoutIn(tmp)));
        assertEquals(0, cache.evictSite(null));
        assertEquals(0, cache.evictSite(" "));
        assertEquals(0, cache.evictSite("acme"));
    }

    @Test
    void harmlessLifecycleEventsDoNotClear(@TempDir Path tmp) throws IOException {
        StorageLayout layout = layoutIn(tmp);
        SpringTemplateEngine engine = engineOver(layout);
        TemplateCacheService cache = new TemplateCacheService(engine);

        writePage(layout, "acme", "V1");
        render(engine); // populate cache

        cache.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.CREATED));
        cache.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.SUSPENDED));
        assertTrue(isCached(engine, "acme", "about"), "create/suspend do not touch templates, so the cache stays");
    }
}
