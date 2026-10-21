package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateInputException;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.FileTemplateResolver;
import org.thymeleaf.templateresource.FileTemplateResource;
import org.thymeleaf.templateresource.ITemplateResource;

/**
 * The render-site rule of {@link SiteTemplateResolver} through the real render service ({@link PageRenderer}),
 * aimed at the paths the end-to-end test cannot set up: a plugin template that pulls {@code ${master}} (the
 * cache-hit path), a site id that is a prefix of another, a render carrying no rendering site, traversal, and
 * bean access.
 */
class SiteTemplateIsolationTest {

    /** Stands in for the plugin resolver: {@code __plugin__/p/<name>} under a temp folder, cacheable. */
    private static final class TestPluginResolver extends FileTemplateResolver {
        private final Path dir;

        TestPluginResolver(Path dir) {
            this.dir = dir;
            setTemplateMode(TemplateMode.HTML);
            setUseDecoupledLogic(true);
            setCacheable(true);
            setCheckExistence(true);
            setOrder(3);
        }

        @Override
        protected boolean computeResolvable(IEngineConfiguration c, String owner, String template, Map<String, Object> a) {
            return template.startsWith("__plugin__/p/");
        }

        @Override
        protected ITemplateResource computeTemplateResource(IEngineConfiguration c, String owner, String template,
                String resourceName, String encoding, Map<String, Object> a) {
            return new FileTemplateResource(dir.resolve(template.substring("__plugin__/p/".length()) + ".html")
                    .toString(), encoding);
        }
    }

    private record Fixture(SpringTemplateEngine engine, PageRenderer renderer, StorageLayout layout, Path plugin) {}

    private static Fixture wire(Path tmp) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp.resolve("external"));
        StorageLayout layout = StorageLayout.from(props);
        Path plugin = tmp.resolve("plugin");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.addTemplateResolver(new SiteTemplateResolver(layout));
        engine.addTemplateResolver(new TestPluginResolver(plugin));
        for (String site : new String[] {"acme", "acme2", "globex"}) {
            String mark = site.toUpperCase(Locale.ROOT);
            write(layout, site, "_master.html", doc("<main id=\"content\">" + mark + "_CHROME</main>"));
            write(layout, site, "secret.html", doc("<p id=\"secret\">" + mark + "_SECRET</p>"));
        }
        // A content-source body: pulls the rendering site's master through ${master} — a model variable.
        Files.createDirectories(plugin);
        Files.writeString(plugin.resolve("body.html"), doc("<div id=\"body\"><main id=\"content\">B</main></div>"));
        Files.writeString(plugin.resolve("body.th.xml"),
                "<?xml version=\"1.0\"?><thlogic><attr sel=\"#content\" th:replace=\"~{${master} :: #content}\"/></thlogic>");
        return new Fixture(engine, new PageRenderer(engine), layout, plugin);
    }

    private static String doc(String body) {
        return "<!DOCTYPE html><html><head><title>T</title></head><body>" + body + "</body></html>";
    }

    private static void write(StorageLayout layout, String site, String name, String content) throws IOException {
        Path file = layout.siteDir(site).resolve("templates").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** A page of {@code site} whose only content is the given decoupled rules on {@code #x} / {@code #w}. */
    private static void page(StorageLayout layout, String site, String slug, String rules) throws IOException {
        write(layout, site, slug + ".html", doc("<div id=\"w\"><div id=\"x\">PH</div></div>"));
        write(layout, site, slug + ".th.xml", "<?xml version=\"1.0\"?><thlogic>" + rules + "</thlogic>");
    }

    /** A SpEL literal for {@code <prefix>/<rest>} with no {@code __} pair left for preprocessing. */
    private static String lit(String prefixWord, String rest) {
        return "'_' + '_" + prefixWord + "_' + '_/" + rest + "'";
    }

    private static Map<String, Object> model(String site) {
        Map<String, Object> model = new HashMap<>();
        model.put("master", SiteTemplateResolver.PREFIX + site + "/_master");
        return model;
    }

    private static String render(Fixture f, String site, String name) {
        return f.renderer().render(site, SiteTemplateResolver.PREFIX + site + "/" + name, Locale.ENGLISH, model(site));
    }

    @Test
    void aWarmedPluginBodyParseOfAnotherSitesMasterIsNotACacheHitForThisSite(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        // globex's own content render: the plugin body pulls globex's master — cached under the owner template
        // __plugin__/p/body, a name with no site in it.
        String globex = f.renderer().render("globex", "__plugin__/p/body", Locale.ENGLISH, model("globex"));
        assertTrue(globex.contains("GLOBEX_CHROME"), globex);
        // acme overrides ${master} and pulls the same plugin body: the nested key (owner __plugin__/p/body, name
        // __site__/globex/_master, selector #content) is the warmed one in all but the rendering site.
        page(f.layout(), "acme", "steal",
                "<attr sel=\"#w\" th:with=\"master=" + lit("site", "globex/_master") + "\"/>"
                + "<attr sel=\"#x\" th:replace=\"~{${" + lit("plugin", "p/body") + "} :: #body}\"/>");

        TemplateInputException e = assertThrows(TemplateInputException.class, () -> render(f, "acme", "steal"));
        assertFalse(String.valueOf(e.getMessage()).contains("GLOBEX_CHROME"));
        // And the legitimate path through the same plugin body still serves acme its own chrome.
        String acme = f.renderer().render("acme", "__plugin__/p/body", Locale.ENGLISH, model("acme"));
        assertTrue(acme.contains("ACME_CHROME") && !acme.contains("GLOBEX"), acme);
    }

    @Test
    void aSiteWhoseIdPrefixesAnothersReachesOnlyItsOwn(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        page(f.layout(), "acme", "p", "<attr sel=\"#x\" th:insert=\"~{${master.replace('/acme/', '/acme2/')"
                + ".replace('_master', 'secret')} :: #secret}\"/>");
        assertThrows(TemplateInputException.class, () -> render(f, "acme", "p"));
        page(f.layout(), "acme2", "p", "<attr sel=\"#x\" th:insert=\"~{${master.replace('/acme2/', '/acme/')"
                + ".replace('_master', 'secret')} :: #secret}\"/>");
        assertThrows(TemplateInputException.class, () -> render(f, "acme2", "p"));
        // Same form, own site: renders.
        page(f.layout(), "acme2", "q", "<attr sel=\"#x\" th:insert=\"~{${master.replace('_master', 'secret')} :: #secret}\"/>");
        assertTrue(render(f, "acme2", "q").contains("ACME2_SECRET"));
    }

    @Test
    void aRenderThatNamesNoSiteResolvesNoSiteTemplate(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        // The template exists; a render started without a rendering site (a console view, a stray engine call)
        // still cannot reach it.
        assertThrows(TemplateInputException.class,
                () -> f.engine().process(SiteTemplateResolver.PREFIX + "acme/secret", new Context(Locale.ENGLISH)));
        assertTrue(f.renderer().render("acme", SiteTemplateResolver.PREFIX + "acme/secret", Locale.ENGLISH, model("acme"))
                .contains("ACME_SECRET"));
        assertThrows(IllegalArgumentException.class, () -> SiteTemplateResolver.renderSpec(" ", "x"));
        assertThrows(IllegalArgumentException.class, () -> SiteTemplateResolver.renderSpec(null, "x"));
    }

    /**
     * The editor preview shows a failed render's exception message to the site manager, so the message is the
     * oracle that matters: another site's existing template, another site's missing one and an own missing one
     * must fail with the same message (up to the names written in the template).
     */
    @Test
    void theErrorMessageDoesNotTellAnotherSitesTemplateFromAMissingOne(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        page(f.layout(), "acme", "e1", "<attr sel=\"#w\" th:with=\"n=" + lit("site", "globex/secret") + "\"/>"
                + "<attr sel=\"#x\" th:insert=\"~{${n} :: #secret}\"/>");
        page(f.layout(), "acme", "e2", "<attr sel=\"#w\" th:with=\"n=" + lit("site", "globex/absent") + "\"/>"
                + "<attr sel=\"#x\" th:insert=\"~{${n} :: #secret}\"/>");
        page(f.layout(), "acme", "e3", "<attr sel=\"#w\" th:with=\"n=" + lit("site", "acme/absent") + "\"/>"
                + "<attr sel=\"#x\" th:insert=\"~{${n} :: #secret}\"/>");
        String foreignExisting = assertThrows(RuntimeException.class, () -> render(f, "acme", "e1")).getMessage();
        String foreignMissing = assertThrows(RuntimeException.class, () -> render(f, "acme", "e2")).getMessage();
        String ownMissing = assertThrows(RuntimeException.class, () -> render(f, "acme", "e3")).getMessage();
        assertEquals(foreignMissing, foreignExisting.replace("/e1", "/e2").replace("globex/secret", "globex/absent"));
        assertEquals(ownMissing, foreignMissing.replace("/e2", "/e3").replace("globex/absent", "acme/absent"));
    }

    @Test
    void theEntryTemplateItselfMustBelongToTheRenderingSite(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        assertThrows(TemplateInputException.class, () -> f.renderer().render("acme",
                SiteTemplateResolver.PREFIX + "globex/secret", Locale.ENGLISH, model("acme")));
    }

    @Test
    void traversalOutOfTheOwnFolderFailsAndShowsNothing(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        page(f.layout(), "acme", "t", "<attr sel=\"#x\" th:insert=\"~{${master.replace('_master', '../globex/secret')} :: #secret}\"/>");
        RuntimeException e = assertThrows(RuntimeException.class, () -> render(f, "acme", "t"));
        assertFalse(String.valueOf(e.getMessage()).contains("GLOBEX_SECRET"));
    }

    @Test
    void aTenantTemplateCannotCallSpringBeans(@TempDir Path tmp) throws IOException {
        Fixture f = wire(tmp);
        page(f.layout(), "acme", "b", "<attr sel=\"#x\" th:text=\"${@siteStore}\"/>");
        assertThrows(org.thymeleaf.exceptions.TemplateProcessingException.class, () -> render(f, "acme", "b"));
        // Ordinary SpEL — including Spring conversion via ${{…}} — still evaluates.
        page(f.layout(), "acme", "c", "<attr sel=\"#x\" th:text=\"${{'a' + 'b'}} + ${#strings.toUpperCase('ok')}\"/>");
        assertEquals(1, render(f, "acme", "c").split("abOK", -1).length - 1);
    }
}
