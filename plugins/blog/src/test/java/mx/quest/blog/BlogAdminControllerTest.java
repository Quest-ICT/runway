package mx.quest.blog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.ConsoleScreen;
import mx.quest.runway.spi.testkit.EventDispatch;
import mx.quest.runway.spi.testkit.FakeContentCatalog;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * {@link BlogAdminController} over SPI fakes: the form → entry parse (per-language fields, the default-language slug
 * as base slug, published checkbox), the {@code ContentChanges} announcement after every mutation (the platform's
 * freshness rule — and, delivered back, the source's own index drop), the reverse advisory through
 * {@code ContentCatalogView.pageAt}, the bound tenancy through {@code RequestContext}, console messages through
 * {@code Messages}, and the console-screen contract (annotation, paths, POST-only mutations, the permission gate).
 */
class BlogAdminControllerTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");

    private record Fixture(BlogAdminController controller, BlogStore store, EventDispatch events,
                           FakeContentCatalog catalog, BlogContentSource source) {
    }

    private static Fixture fixture(Path tmp, boolean bound) throws IOException {
        Files.createDirectories(tmp.resolve("acme"));
        BlogStore store = new BlogStore(new TempDirSiteFiles(tmp, "blog"));
        FakeContentCatalog catalog = new FakeContentCatalog().site("acme").route("/sitemap.xml");
        BlogContentSource source = new BlogContentSource(store, new FakePluginSettings(), catalog);
        EventDispatch events = new EventDispatch("blog", Set.of(BlogContentSource.KEY)).listener(source);
        FakeSiteServices kernel = new FakeSiteServices()
                .site(new SiteView("acme", "Acme", EN, List.of(EN, ES), null))
                .message("acme", "blog.flash.created", "Entrada creada.");
        if (bound) {
            kernel.current("acme");
        }
        BlogAdminController controller = new BlogAdminController(kernel, store, source, catalog, events, kernel);
        return new Fixture(controller, store, events, catalog, source);
    }

    private static Fixture fixture(Path tmp) throws IOException {
        return fixture(tmp, true);
    }

    private static MultiValueMap<String, String> form() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("title_en", "My first post");
        params.add("title_es", "Mi primera entrada");
        params.add("description_en", "The very first.");
        params.add("slug_en", "first");           // the default language's slug = the base slug
        params.add("slug_es", "primera");
        params.add("body_en", "Hello **world**.");
        params.add("body_es", "Hola **mundo**.");
        params.add("locales_en", "on");
        params.add("locales_es", "on");
        params.add("published", "on");
        params.add("publishedOn", "2026-07-17");
        params.add("author", "Jane Doe");
        return params;
    }

    private static List<String> announcedIds(Fixture f) {
        List<String> ids = new ArrayList<>();
        for (CatalogChange c : f.events().announced()) {
            assertEquals(BlogContentSource.KEY, c.sourceKey(), "always this plugin's own source");
            assertEquals("acme", c.siteId(), "always the bound site");
            ids.add(c.id());
        }
        return ids;
    }

    @Test
    void createParsesTheFormAndAnnouncesTheChange(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        String view = f.controller().create("First-Post ", form(), new ExtendedModelMap(), redirect);
        assertEquals("redirect:/__admin/blog/first-post/edit", view, "the id is trimmed and lower-cased");

        BlogEntry saved = f.store().read("acme", "first-post").orElseThrow();
        assertEquals("My first post", saved.title().get("en"));
        assertEquals("The very first.", saved.description().get("en"));
        assertEquals("first", saved.slug(), "the default language's slug field becomes the base slug");
        assertEquals(Map.of("es", "primera"), saved.localizedSlugs());
        assertEquals(List.of("en", "es"), saved.locales());
        assertTrue(saved.published());
        assertEquals("2026-07-17", saved.publishedOn());
        assertEquals("Jane Doe", saved.author());
        assertEquals("Hello **world**.", f.store().readBody("acme", "first-post", "en"));
        assertEquals("Hola **mundo**.", f.store().readBody("acme", "first-post", "es"));

        assertEquals(List.of("first-post"), announcedIds(f), "one announcement, after the write");
        assertEquals("Entry created.", redirect.getFlashAttributes().get("flash"),
                "console words come from Messages.console (its fallback here)");
        assertTrue(f.events().failures().isEmpty());
    }

    @Test
    void theAnnouncementReachesTheSourceSoTheNewEntryRoutesAtOnce(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        SiteView site = new SiteView("acme", "Acme", EN, List.of(EN, ES), null);
        assertTrue(f.source().resolve(site, "/blog/first", EN).isEmpty(), "builds (and caches) an empty index");
        f.controller().create("first-post", form(), new ExtendedModelMap(), new RedirectAttributesModelMap());
        assertEquals("first-post", f.source().resolve(site, "/blog/first", EN).orElseThrow().id(),
                "the source's SOURCE-phase listener dropped its stale index");
    }

    @Test
    void anInvalidIdReRendersTheEditorWithTheTypedStateNothingWritten(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        ExtendedModelMap model = new ExtendedModelMap();
        String view = f.controller().create("Bad:Id", form(), model, new RedirectAttributesModelMap());
        assertEquals(ConsoleScreen.LAYOUT_VIEW, view, "failure RE-RENDERS the editor — never a blank form");
        assertNotNull(model.getAttribute("error"), "the error message is in the model");
        BlogEntry typed = (BlogEntry) model.getAttribute("entry");
        assertEquals("My first post", typed.title().get("en"), "everything the author typed is preserved");
        assertEquals("__plugin__/blog/admin-edit", model.getAttribute(ConsoleScreen.CONTENT_TEMPLATE));
        assertEquals(Boolean.TRUE, model.getAttribute("creating"));
        assertTrue(f.store().list("acme").isEmpty(), "nothing was written");
        assertTrue(f.events().announced().isEmpty(), "nothing was announced");
    }

    @Test
    void reservedAndDuplicateIdsAreRejected(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        for (String reserved : List.of("create", "new")) {
            ExtendedModelMap model = new ExtendedModelMap();
            assertEquals(ConsoleScreen.LAYOUT_VIEW,
                    f.controller().create(reserved, form(), model, new RedirectAttributesModelMap()));
            assertEquals("That id is reserved — pick another.", model.getAttribute("error"));
            assertTrue(f.store().read("acme", reserved).isEmpty(), reserved + " would collide with the route table");
        }
        f.controller().create("post", form(), new ExtendedModelMap(), new RedirectAttributesModelMap());
        var other = form();
        other.set("title_en", "Another");
        ExtendedModelMap model = new ExtendedModelMap();
        assertEquals(ConsoleScreen.LAYOUT_VIEW,
                f.controller().create("post", other, model, new RedirectAttributesModelMap()));
        assertEquals("An entry with that id already exists.", model.getAttribute("error"));
        assertEquals("My first post", f.store().read("acme", "post").orElseThrow().title().get("en"),
                "the existing entry is untouched");
    }

    @Test
    void anUnsafeSlugOrCustomPathIsRejectedNotSilentlyDiscarded(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        var bad = form();
        bad.set("slug_es", "Bad Slug!");
        ExtendedModelMap model = new ExtendedModelMap();
        assertEquals(ConsoleScreen.LAYOUT_VIEW, f.controller().create("post", bad, model,
                new RedirectAttributesModelMap()));
        assertTrue(String.valueOf(model.getAttribute("error")).contains("slug (es)"));
        assertTrue(f.store().read("acme", "post").isEmpty(), "an invalid slug never persists");

        // Each of these would be dropped by the kernel's fail-closed re-filter (or is not even a path).
        for (String path : List.of("no-leading-slash", "/a|b", "/a:b", "/__admin/x", "/app/x", "/sitemap.xml",
                "/x/../y")) {
            var badPath = form();
            badPath.set("customPath", path);
            ExtendedModelMap m = new ExtendedModelMap();
            assertEquals(ConsoleScreen.LAYOUT_VIEW,
                    f.controller().create("post2", badPath, m, new RedirectAttributesModelMap()), path);
            assertTrue(String.valueOf(m.getAttribute("error")).contains("customPath"), path);
            assertTrue(f.store().read("acme", "post2").isEmpty(), path + ": an invalid custom path never persists");
        }
        var goodPath = form();
        goodPath.set("customPath", " /announcing-v2 ");
        f.controller().create("post3", goodPath, new ExtendedModelMap(), new RedirectAttributesModelMap());
        assertEquals("/announcing-v2", f.store().read("acme", "post3").orElseThrow().customPath());
        assertEquals(List.of("post3"), announcedIds(f), "only the valid save was announced");
    }

    @Test
    void theFullLifecycleRoundTripsAndAnnouncesEveryMutation(@TempDir Path tmp) throws IOException {
        // create → update (slug change + published checkbox ABSENT = silent unpublish, the intended
        // full-state-form UX) → publish-toggle twice (ONLY the flag flips) → delete (folder gone).
        Fixture f = fixture(tmp);
        f.controller().create("post", form(), new ExtendedModelMap(), new RedirectAttributesModelMap());

        var update = form();
        update.set("slug_en", "renamed");
        update.remove("published");
        RedirectAttributesModelMap saved = new RedirectAttributesModelMap();
        assertEquals("redirect:/__admin/blog/post/edit", f.controller().update("post", update, saved));
        assertEquals("Saved.", saved.getFlashAttributes().get("flash"));
        BlogEntry updated = f.store().read("acme", "post").orElseThrow();
        assertEquals("renamed", updated.slug(), "the slug change persisted");
        assertFalse(updated.published(), "checkbox absent ⇒ unpublished (full-state form semantics)");
        assertEquals("Mi primera entrada", updated.title().get("es"), "other fields intact");

        RedirectAttributesModelMap toggled = new RedirectAttributesModelMap();
        assertEquals("redirect:/__admin/blog", f.controller().togglePublished("post", toggled));
        assertEquals("Entry published.", toggled.getFlashAttributes().get("flash"));
        BlogEntry republished = f.store().read("acme", "post").orElseThrow();
        assertTrue(republished.published(), "toggle flips ONLY the flag");
        assertEquals("renamed", republished.slug(), "the positional rebuild kept every other field");
        assertEquals("2026-07-17", republished.publishedOn());
        RedirectAttributesModelMap untoggled = new RedirectAttributesModelMap();
        f.controller().togglePublished("post", untoggled);
        assertEquals("Entry unpublished.", untoggled.getFlashAttributes().get("flash"));
        assertFalse(f.store().read("acme", "post").orElseThrow().published());

        RedirectAttributesModelMap deleted = new RedirectAttributesModelMap();
        assertEquals("redirect:/__admin/blog", f.controller().delete("post", deleted));
        assertEquals("Entry deleted.", deleted.getFlashAttributes().get("flash"));
        assertTrue(f.store().read("acme", "post").isEmpty(), "deleted");
        assertEquals(List.of("post", "post", "post", "post", "post"), announcedIds(f),
                "create, update, publish, unpublish, delete — each announced");
    }

    @Test
    void anInvalidUpdateFlashesTheErrorAndWritesNothing(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.controller().create("post", form(), new ExtendedModelMap(), new RedirectAttributesModelMap());
        var bad = form();
        bad.set("customPath", "/__nope");
        bad.set("title_en", "Changed");
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        assertEquals("redirect:/__admin/blog/post/edit", f.controller().update("post", bad, redirect));
        assertTrue(String.valueOf(redirect.getFlashAttributes().get("error")).contains("customPath"));
        assertEquals("My first post", f.store().read("acme", "post").orElseThrow().title().get("en"));
        assertEquals(1, f.events().announced().size(), "only the create");
    }

    @Test
    void unknownEntriesAre404(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> f.controller().editForm("ghost", new ExtendedModelMap())).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> f.controller().update("ghost", form(), new RedirectAttributesModelMap())).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> f.controller().togglePublished("ghost", new RedirectAttributesModelMap()))
                .getStatusCode().value());
        assertTrue(f.store().read("acme", "ghost").isEmpty(), "an update never creates");
    }

    @Test
    void withoutABoundTenancyEveryScreenIs400(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, false);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> f.controller().list(new ExtendedModelMap())).getStatusCode().value(),
                "the application console has no site: RequestContext.site() is empty there");
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> f.controller().create("x", form(), new ExtendedModelMap(), new RedirectAttributesModelMap()))
                .getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> f.controller().delete("x", new RedirectAttributesModelMap())).getStatusCode().value());
    }

    @Test
    void theListModelCarriesRowsPathsAndTheEnabledFlag(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        BlogStoreTest.install131(tmp, "acme");
        ExtendedModelMap model = new ExtendedModelMap();
        assertEquals(ConsoleScreen.LAYOUT_VIEW, f.controller().list(model));
        @SuppressWarnings("unchecked")
        List<BlogAdminController.EntryRow> rows = (List<BlogAdminController.EntryRow>) model.getAttribute("rows");
        assertEquals(List.of("launch", "no-author", "hello-world", "draft-post"),
                rows.stream().map(r -> r.entry().id()).toList(), "every entry, drafts included, newest first");
        assertEquals("/announcing-v2", rows.get(0).publicPath());
        assertEquals("/blog/hello-world", rows.get(2).publicPath());
        assertEquals(Boolean.TRUE, model.getAttribute("blogEnabled"));
        assertEquals("__plugin__/blog/admin-list", model.getAttribute(ConsoleScreen.CONTENT_TEMPLATE));
        assertEquals("blog.admin.title", model.getAttribute(ConsoleScreen.PAGE_TITLE_KEY));
    }

    @Test
    void theEditorModelCarriesTheReverseAdvisoryWhenAPageOwnsThePath(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.controller().create("first-post", form(), new ExtendedModelMap(), new RedirectAttributesModelMap());
        f.catalog().page("acme", "/blog/first", EN, "about");
        f.catalog().page("acme", "/blog/first", ES, "wrong-locale-claim");
        f.catalog().page("acme", "/blog/primera", ES, "acerca");

        ExtendedModelMap model = new ExtendedModelMap();
        assertEquals(ConsoleScreen.LAYOUT_VIEW, f.controller().editForm("first-post", model));
        assertEquals(List.of("about (en)", "acerca (es)"), model.getAttribute("shadowedBy"),
                "each language's REAL URL is checked in that language's URL space");
        assertEquals("__plugin__/blog/admin-edit", model.getAttribute(ConsoleScreen.CONTENT_TEMPLATE));
        assertEquals("/__admin/blog/first-post", model.getAttribute("formAction"));
        assertEquals(List.of("en", "es"), model.getAttribute("siteLocaleTags"));
        assertEquals("en", model.getAttribute("defaultLocaleTag"));
        @SuppressWarnings("unchecked")
        Map<String, String> bodies = (Map<String, String>) model.getAttribute("bodies");
        assertEquals(Map.of("en", "Hello **world**.", "es", "Hola **mundo**."), bodies);

        ExtendedModelMap creating = new ExtendedModelMap();
        f.controller().createForm(creating);
        assertEquals(List.of(), creating.getAttribute("shadowedBy"), "no advisory before the entry exists");
        assertEquals("/__admin/blog/create", creating.getAttribute("formAction"));
    }

    /** Toggling publish/unpublish must not erase the byline (the positional rebuild is a known-recurring hazard). */
    @Test
    void togglingPublishPreservesTheAuthor(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        f.store().save("acme", new BlogEntry("post", Map.of("en", "Post"), Map.of(), List.of("en"),
                null, Map.of(), null, true, "2026-03-04", "Jane Doe", null), Map.of("en", "# Hi"));
        f.controller().togglePublished("post", new RedirectAttributesModelMap());
        BlogEntry hidden = f.store().read("acme", "post").orElseThrow();
        assertFalse(hidden.published(), "precondition: the toggle actually flipped visibility");
        assertEquals("Jane Doe", hidden.author(), "unpublishing must not un-attribute the entry");
        f.controller().togglePublished("post", new RedirectAttributesModelMap());
        assertEquals("Jane Doe", f.store().read("acme", "post").orElseThrow().author());
        assertEquals("# Hi", f.store().readBody("acme", "post", "en"), "a toggle never touches a body");
    }

    @Test
    void itIsAConsoleScreenWhoseMutationsArePermissionGatedPosts() {
        Class<BlogAdminController> type = BlogAdminController.class;
        assertNotNull(type.getAnnotation(Controller.class));
        assertNotNull(type.getAnnotation(ConsoleScreen.class), "the kernel renders it inside the operator shell");
        int gets = 0;
        int posts = 0;
        for (Method m : type.getDeclaredMethods()) {
            GetMapping get = m.getAnnotation(GetMapping.class);
            PostMapping post = m.getAnnotation(PostMapping.class);
            if (get == null && post == null) {
                continue;
            }
            String[] paths = get != null ? get.value() : post.value();
            for (String path : paths) {
                assertTrue(path.equals("/__admin/blog") || path.startsWith("/__admin/blog/"),
                        m.getName() + ": every mapping lies under /__admin/blog (" + path + ")");
            }
            PreAuthorize gate = m.getAnnotation(PreAuthorize.class);
            assertNotNull(gate, m.getName() + " is permission-gated");
            assertEquals("@authz.may('blog|manage')", gate.value());
            if (get != null) {
                gets++;
                assertTrue(Set.of("list", "createForm", "editForm").contains(m.getName()),
                        m.getName() + ": a GET only renders");
            } else {
                posts++;
            }
        }
        assertEquals(3, gets);
        assertEquals(4, posts, "create, update, publish toggle, delete — each a CSRF-checked POST");
    }
}
