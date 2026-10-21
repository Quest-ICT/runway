package mx.quest.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.InstallResult;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.PluginInfo;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.model.StarterPage;
import mx.quest.runway.spi.service.ConsoleScreen;
import mx.quest.runway.spi.service.SitePages;
import mx.quest.runway.spi.testkit.EventDispatch;
import mx.quest.runway.spi.testkit.FakeContentCatalog;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The console screen driven through Spring MVC in-process (MockMvc), over the testkit's fakes. The
 * {@code @PreAuthorize} gates are enforced by the host's method security, which a standalone MockMvc does not have:
 * {@link DemoPluginConfigTest} proves every handler carries one, and the live verify script proves a request without
 * the permission or the CSRF token is refused.
 */
class DemoConsoleControllerTest {

    private static final SiteView SITE = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), null);
    private static final String BASE = "/__admin/runway-demo";

    @TempDir
    Path sites;
    private FakeSiteServices kernel;
    private FakeContentCatalog catalog;
    private EventDispatch events;
    private DemoNotesStore notes;
    private DemoShowcaseStore showcase;
    private DemoActivity activity;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(sites.resolve("acme"));
        kernel = new FakeSiteServices().site(SITE).current("acme");
        catalog = new FakeContentCatalog().site("acme");
        TempDirSiteFiles files = new TempDirSiteFiles(sites, "runway-demo");
        events = new EventDispatch("runway-demo", Set.of("runway-demo"));
        notes = new DemoNotesStore(files);
        showcase = new DemoShowcaseStore(files, events);
        activity = new DemoActivity(notes, kernel);
        events.listener(new DemoShowcaseSource(showcase, kernel)).listener(activity);
        mvc = mvcFor(kernel);
    }

    private MockMvc mvcFor(FakeSiteServices services) {
        DemoConsoleController controller = new DemoConsoleController(services, services, services, services,
                catalog, notes, showcase, activity, new PluginInfo("runway-demo", "2.0.1", sites));
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private DemoConsoleController.Screen screen(MvcResult result) {
        return (DemoConsoleController.Screen) result.getModelAndView().getModel().get("rd");
    }

    @Test
    void theScreenRendersInsideTheConsoleShell() throws Exception {
        catalog.entry("acme", new CatalogEntry("pages", ContentRef.of("about", "/about", Map.of(), List.of(),
                Map.of("en", "About"), Map.of(), "about", null), List.of(new LocalizedUrl(Locale.ENGLISH, "/about",
                "/about")), "/about"));
        MvcResult result = mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(view().name(ConsoleScreen.LAYOUT_VIEW))
                .andExpect(model().attribute(ConsoleScreen.CONTENT_TEMPLATE, "__plugin__/runway-demo/console"))
                .andExpect(model().attribute(ConsoleScreen.PAGE_TITLE_KEY, "runwaydemo.console.title"))
                .andReturn();
        DemoConsoleController.Screen screen = screen(result);
        assertEquals("acme", screen.site().id(), "the site comes from RequestContext");
        assertEquals("2.0.1", screen.pluginVersion());
        assertEquals(1, screen.noteCount(), "the welcome note");
        assertEquals(1, screen.catalogTotal());
        assertEquals("/about", screen.catalog().getFirst().path());
        assertNull(screen.showcaseText());
        assertEquals("", screen.starterPath());
        assertNull(screen.pathCheck());
    }

    @Test
    void openingTheScreenWritesNothingAndTheFirstNoteKeepsTheWelcome() throws Exception {
        Path pluginFolder = sites.resolve("acme").resolve("plugins").resolve("runway-demo");
        mvc.perform(get(BASE)).andExpect(status().isOk());
        mvc.perform(get(BASE)).andExpect(status().isOk());
        assertFalse(Files.exists(pluginFolder), "a GET created no notes.yml, not even the plugin's folder");

        mvc.perform(post(BASE + "/notes").param("text", "First")).andExpect(redirectedUrl(BASE));
        List<Note> stored = notes.list("acme");
        assertEquals(List.of(DemoNotesStore.WELCOME_ID, stored.get(1).id()), stored.stream().map(Note::id).toList(),
                "the first POST writes the welcome the screen was showing, then the new note");
        assertTrue(Files.isRegularFile(pluginFolder.resolve(DemoNotesStore.FILE)));
        assertEquals(2, screen(mvc.perform(get(BASE)).andReturn()).noteCount());
    }

    @Test
    void theApplicationConsoleHasNoSiteSoTheScreenIsNotFound() throws Exception {
        mvcFor(new FakeSiteServices().site(SITE)).perform(get(BASE)).andExpect(status().isNotFound());
        mvcFor(new FakeSiteServices().site(SITE)).perform(post(BASE + "/notes").param("text", "x"))
                .andExpect(status().isNotFound());
        assertEquals(1, notes.count("acme"), "nothing was written");
    }

    @Test
    void addANote() throws Exception {
        mvc.perform(post(BASE + "/notes").param("text", "Remember the milk"))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute(DemoConsoleController.FLASH, "Note added."));
        Note last = notes.list("acme").getLast();
        assertEquals("Remember the milk", last.text());
        assertEquals(Note.ORIGIN_CONSOLE, last.origin());
        mvc.perform(post(BASE + "/notes").param("text", "   "))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute(DemoConsoleController.FLASH_ERROR, "Write something first."));
    }

    @Test
    void publishAndUnpublishTheShowcaseAnnounceAndReachTheListener() throws Exception {
        mvc.perform(post(BASE + "/showcase").param("text", "Fresh"))
                .andExpect(flash().attribute(DemoConsoleController.FLASH, "Showcase published at /demo/showcase."));
        assertEquals(1, events.announced().size(), "ContentChanges.changed was called");
        assertEquals("content", activity.recent("acme").getFirst().cause(), "…and the CONSUMER listener saw it");
        assertEquals("Fresh", screen(mvc.perform(get(BASE)).andReturn()).showcaseText());

        mvc.perform(post(BASE + "/showcase/unpublish"))
                .andExpect(flash().attribute(DemoConsoleController.FLASH, "Showcase unpublished."));
        mvc.perform(post(BASE + "/showcase/unpublish"))
                .andExpect(flash().attribute(DemoConsoleController.FLASH, "No showcase was published."));
        assertEquals(2, events.announced().size(), "an unpublish of nothing announces nothing");
        mvc.perform(post(BASE + "/showcase").param("text", ""))
                .andExpect(flash().attribute(DemoConsoleController.FLASH_ERROR, "Write something first."));
    }

    @Test
    void installingTheStarterPageNeedsEditPagesAndReportsTheOutcome() throws Exception {
        mvc.perform(post(BASE + "/install-page"))
                .andExpect(flash().attribute(DemoConsoleController.FLASH_ERROR,
                        "Installing a page needs the permission to edit pages."));
        assertTrue(kernel.installs().isEmpty(), "nothing installed without EDIT_PAGES");

        kernel.grant("EDIT_PAGES");
        mvc.perform(post(BASE + "/install-page"))
                .andExpect(flash().attribute(DemoConsoleController.FLASH, "Demo page published at /demo."));
        StarterPage page = kernel.installs().getFirst();
        assertEquals("runway-demo", page.slug());
        assertEquals("/demo", page.path());
        assertTrue(page.noindex(), "a demo page is kept out of search engines");
        assertTrue(page.html().contains("data-widget=\"demo-notice/page\"")
                && page.html().contains("data-widget=\"demo-feedback/page\""), page.html());
        assertTrue(page.logic().contains("~{${master} :: head}"), "chrome by the ${master} variable");

        mvc.perform(post(BASE + "/install-page"))
                .andExpect(flash().attribute(DemoConsoleController.FLASH,
                        "A demo page already exists at /demo; nothing changed."));
        assertEquals("/demo", screen(mvc.perform(get(BASE)).andReturn()).starterPath());
    }

    @Test
    void everyInstallOutcomeHasWords() {
        DemoConsoleController controller = new DemoConsoleController(kernel, kernel, kernel, kernel, catalog, notes,
                showcase, activity, new PluginInfo("runway-demo", "2.0.1", sites));
        assertEquals("Demo page created at /demo and waiting in the review queue.", controller.describe(
                new InstallResult(InstallResult.Outcome.PENDING_REVIEW, "/demo", List.of())));
        assertEquals("Demo page created at /demo as a draft: the quality checks blocked publishing."
                        + " h1.missing: No heading", controller.describe(new InstallResult(
                InstallResult.Outcome.DRAFT_BLOCKED, "/demo", List.of(Finding.error("h1.missing", "No heading")))));
    }

    @Test
    void aFailedInstallIsReportedNotThrown() throws Exception {
        kernel.grant("EDIT_PAGES");
        SitePages failing = new SitePages() {
            @Override
            public Optional<String> pathOf(String siteId, String slug) {
                return Optional.empty();
            }

            @Override
            public InstallResult install(String siteId, StarterPage page) {
                throw new IllegalStateException("another page serves /demo");
            }
        };
        DemoConsoleController controller = new DemoConsoleController(kernel, kernel, kernel, failing, catalog, notes,
                showcase, activity, new PluginInfo("runway-demo", "2.0.1", sites));
        MockMvcBuilders.standaloneSetup(controller).build().perform(post(BASE + "/install-page"))
                .andExpect(flash().attribute(DemoConsoleController.FLASH_ERROR, "The demo page could not be installed."));
    }

    @Test
    void thePathCheckAsksTheCatalog() throws Exception {
        catalog.page("acme", "/about", Locale.ENGLISH, "about").route("/sitemap.xml");
        DemoConsoleController.PathCheck owned = screen(mvc.perform(get(BASE).param("path", " /about "))
                .andReturn()).pathCheck();
        assertEquals(new DemoConsoleController.PathCheck("/about", true, true, "about"), owned);
        DemoConsoleController.PathCheck route = screen(mvc.perform(get(BASE).param("path", "/sitemap.xml"))
                .andReturn()).pathCheck();
        assertEquals(new DemoConsoleController.PathCheck("/sitemap.xml", true, false, null), route);
        DemoConsoleController.PathCheck reserved = screen(mvc.perform(get(BASE).param("path", "/__admin"))
                .andReturn()).pathCheck();
        assertEquals(false, reserved.servable());
    }

    @Test
    void refreshDropsTheCachedListing() throws Exception {
        mvc.perform(post(BASE + "/catalog/refresh"))
                .andExpect(flash().attribute(DemoConsoleController.FLASH, "Content listing refreshed."));
        assertEquals(List.of("acme"), catalog.refreshed());
    }
}
