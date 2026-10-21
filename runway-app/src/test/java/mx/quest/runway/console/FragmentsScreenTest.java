package mx.quest.runway.console;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.page.FragmentTemplates;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteContextFilter;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The tenant Fragments editor ({@code /__admin/fragments}). Proves the screen renders and is
 * CONFIGURE_SITE-gated; that create/edit/delete persist to the <b>bound</b> site's {@code templates/} only
 * (a second site is byte-for-byte untouched — isolation); that a traversal / error-page / non-underscore name
 * is rejected without writing; that the editor cannot clobber a page's {@code .meta.yml} or a master; and that
 * a save is visible on the next read (the source round-trips) — the immediate-apply contract.
 *
 * <p>The bound tenancy is {@code acme} (host {@code localhost}, which MockMvc requests carry). A second site
 * {@code other} (host {@code other.test}) exists only to prove isolation — this route can never touch it.
 */
@SpringBootTest
class FragmentsScreenTest {

    private static final String PW = "Fragments-Screen-2";

    private static Path externalDir;
    private static StorageLayout layout;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-2-2");
        externalDir = external;
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        sites.save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH,
                List.of(Locale.ENGLISH, Locale.forLanguageTag("es")), null, SiteStatus.ACTIVE));
        sites.save(new Site("other", "Other", List.of("other.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));

        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        Path acmeUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(acmeUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));      // wildcard → has CONFIGURE_SITE
        users.save(acmeUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));    // no CONFIGURE_SITE
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private FragmentTemplates fragments;
    @Autowired
    private PageStore pages;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        // Reset both sites' templates/ to a known baseline before each test (they share the class's external
        // dir), so the mutating tests do not depend on each other's ordering.
        resetTemplates("acme");
        resetTemplates("other");
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    /** Lays down a master, a fragment, and one routable page under a site's templates dir. */
    private static void resetTemplates(String siteId) throws IOException {
        Path templates = layout.siteDir(siteId).resolve("templates");
        // Wipe any files a prior test wrote (only regular files; the dir itself is kept).
        if (Files.isDirectory(templates)) {
            try (var walk = Files.list(templates)) {
                for (Path p : walk.toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        Files.createDirectories(templates);
        write(templates.resolve("_master.html"), "<!DOCTYPE html><html><head><title>M</title></head><body>m</body></html>");
        write(templates.resolve("_head.html"), "<head id=\"head\"><meta charset=\"utf-8\"></head>");
        // A routable page (its .meta.yml is the anchor the fragment editor must never touch).
        write(templates.resolve("index.html"), "<!DOCTYPE html><html><body><main id=\"content\">hi</main></body></html>");
        write(templates.resolve("index.meta.yml"), "path: /\ntemplate: _master\ntitle:\n  en: Home\n");
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult r = mvc.perform(formLogin("/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    // --- list + gating -----------------------------------------------------------------------------

    @Test
    void theListRendersTheBoundSitesFragments() throws Exception {
        mvc.perform(get("/__admin/fragments").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("_master")))   // the master is a fragment
                .andExpect(content().string(Matchers.containsString("_head")))     // and the head partial
                .andExpect(content().string(Matchers.containsString("/__admin/fragments/_head/edit")))
                .andExpect(content().string(Matchers.containsString("New fragment")));
    }

    @Test
    void thePageAndItsMetaAreNotListedAsFragments() throws Exception {
        // index is a routable page (backed by index.meta.yml) — it must not appear in the chrome list.
        mvc.perform(get("/__admin/fragments").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString(">index<"))));
    }

    @Test
    void anOperatorWithoutConfigureSiteCannotSeeTheList() throws Exception {
        mvc.perform(get("/__admin/fragments").session(sessionFor("op")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOperatorWithoutConfigureSiteCannotSave() throws Exception {
        mvc.perform(post("/__admin/fragments/_head").session(sessionFor("op")).with(csrf())
                        .param("html", "<x/>"))
                .andExpect(status().isForbidden());
    }

    // --- editor render -----------------------------------------------------------------------------

    @Test
    void theEditorRendersTheFragmentSourceAndTheCodeMirrorBundle() throws Exception {
        mvc.perform(get("/__admin/fragments/_head/edit").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/__assets/vendor/codemirror.min.js")))
                .andExpect(content().string(Matchers.containsString("meta charset")))               // the html source
                .andExpect(content().string(Matchers.containsString("action=\"/__admin/fragments/_head\"")));
    }

    @Test
    void theEditorIs404ForAnUnknownFragment() throws Exception {
        mvc.perform(get("/__admin/fragments/_nope/edit").session(sessionFor("admin")))
                .andExpect(status().isNotFound());
    }

    @Test
    void theCreateFormPostsToTheCollection() throws Exception {
        mvc.perform(get("/__admin/fragments/new").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("action=\"/__admin/fragments\"")))
                .andExpect(content().string(Matchers.containsString("name=\"name\"")));
    }

    // --- create / edit / delete round-trip ---------------------------------------------------------

    @Test
    void creatingAFragmentPersistsBothFilesToTheBoundSite() throws Exception {
        mvc.perform(post("/__admin/fragments").session(sessionFor("admin")).with(csrf())
                        .param("name", "_scripts")
                        .param("html", "<div id=\"scripts\">S</div>")
                        .param("thxml", "<thlogic></thlogic>"))
                .andExpect(status().is3xxRedirection());

        assertEquals("<div id=\"scripts\">S</div>", fragments.readHtml("acme", "_scripts").orElseThrow());
        assertEquals("<thlogic></thlogic>", fragments.readLogic("acme", "_scripts").orElseThrow());
        // A create with no name in 'other' proves nothing crossed over.
        assertFalse(fragments.exists("other", "_scripts"), "the second site got no fragment");
    }

    @Test
    void savingOverwritesTheFragmentAndIsVisibleOnTheNextRead() throws Exception {
        // Immediate-apply contract: the write path + cache evict means a re-read returns the new source.
        mvc.perform(post("/__admin/fragments/_head").session(sessionFor("admin")).with(csrf())
                        .param("html", "<head id=\"head\">CHANGED</head>")
                        .param("thxml", ""))
                .andExpect(status().is3xxRedirection());

        assertEquals("<head id=\"head\">CHANGED</head>", fragments.readHtml("acme", "_head").orElseThrow());
        assertTrue(fragments.readLogic("acme", "_head").isEmpty(), "a blank thxml removes any logic file");
    }

    @Test
    void deletingRemovesTheFragmentFiles() throws Exception {
        assertTrue(fragments.exists("acme", "_head"));
        mvc.perform(post("/__admin/fragments/_head/delete").session(sessionFor("admin")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertFalse(fragments.exists("acme", "_head"), "the fragment was deleted from the bound site");
    }

    // --- isolation ---------------------------------------------------------------------------------

    @Test
    void mutationsLeaveASecondSiteEntirelyUntouched() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        // Create, overwrite and delete in the bound site (acme)…
        mvc.perform(post("/__admin/fragments").session(admin).with(csrf())
                        .param("name", "_nav").param("html", "<nav>acme</nav>"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/__admin/fragments/_head").session(admin).with(csrf())
                        .param("html", "<head>acme-changed</head>"))
                .andExpect(status().is3xxRedirection());

        // …the 'other' site (host other.test) must be byte-for-byte its baseline.
        Path otherHead = layout.siteDir("other").resolve("templates").resolve("_head.html");
        assertEquals("<head id=\"head\"><meta charset=\"utf-8\"></head>",
                Files.readString(otherHead, StandardCharsets.UTF_8), "the other site's _head is unchanged");
        assertFalse(Files.exists(layout.siteDir("other").resolve("templates").resolve("_nav.html")),
                "the created fragment did not leak into the other site");
    }

    // --- path-safety + scope -----------------------------------------------------------------------

    @Test
    void aTraversalNameIsRejectedAndWritesNothing() throws Exception {
        // A '..'-bearing name reaches the create form's `name` param; isFragmentName rejects it (its safe-id
        // grammar forbids '/' and '..'), so nothing is written and nothing escapes templates/.
        mvc.perform(post("/__admin/fragments").session(sessionFor("admin")).with(csrf())
                        .param("name", "../evil").param("html", "PWNED"))
                .andExpect(status().is3xxRedirection());
        assertFalse(Files.exists(layout.siteDir("acme").resolve("evil.html")),
                "a traversal name never writes outside templates/");
        assertFalse(Files.exists(externalDir.resolve("evil.html")), "nothing landed in the external root");
        assertFalse(Files.exists(layout.siteDir("acme").resolve("templates").resolve("evil.html")),
                "and no 'evil' fragment was created either");
    }

    @Test
    void anErrorPageNameIsRejected() throws Exception {
        // _404 is an error page, not a chrome fragment — out of scope, rejected.
        mvc.perform(post("/__admin/fragments").session(sessionFor("admin")).with(csrf())
                        .param("name", "_404").param("html", "<x/>"))
                .andExpect(status().is3xxRedirection());
        assertFalse(fragments.exists("acme", "_404"), "an error-page name is refused");
        assertFalse(Files.exists(layout.siteDir("acme").resolve("templates").resolve("_404.html")));
    }

    @Test
    void aNonUnderscoreNameIsRejected() throws Exception {
        // A page-shaped slug (no leading underscore) is not a fragment — the two areas stay disjoint.
        mvc.perform(post("/__admin/fragments").session(sessionFor("admin")).with(csrf())
                        .param("name", "index").param("html", "<x/>"))
                .andExpect(status().is3xxRedirection());
        // The routable page's files are untouched (the create was refused before any write).
        Path meta = layout.siteDir("acme").resolve("templates").resolve("index.meta.yml");
        assertTrue(Files.exists(meta), "the page's meta anchor is intact");
        assertEquals("<!DOCTYPE html><html><body><main id=\"content\">hi</main></body></html>",
                Files.readString(layout.siteDir("acme").resolve("templates").resolve("index.html"),
                        StandardCharsets.UTF_8), "the page body was not clobbered");
        // And the page still resolves through the read-only PageStore.
        assertTrue(pages.find("acme", "index").isPresent(), "the page is still a page");
    }

    @Test
    void savingAFragmentDoesNotDisturbTheRoutablePagesMeta() throws Exception {
        // Editing the _head fragment must leave index.meta.yml exactly as it was.
        Path meta = layout.siteDir("acme").resolve("templates").resolve("index.meta.yml");
        String before = Files.readString(meta, StandardCharsets.UTF_8);
        mvc.perform(post("/__admin/fragments/_head").session(sessionFor("admin")).with(csrf())
                        .param("html", "<head>edited</head>"))
                .andExpect(status().is3xxRedirection());
        assertEquals(before, Files.readString(meta, StandardCharsets.UTF_8), "a fragment save never touches page meta");
    }

    @Test
    void anErrorPageIsCreatedFromTheMasterEditedAndServedForThatStatus() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        String list = mvc.perform(get("/__admin/fragments").session(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(list.contains("Error pages"), "the screen has the error-pages card");
        assertTrue(list.contains("value=\"404\""), "404 is offered");

        mvc.perform(post("/__admin/fragments/error-pages").session(admin).with(csrf()).param("code", "404"))
                .andExpect(redirectedUrl("/__admin/fragments/_404/edit"));
        assertTrue(fragments.errorPageExists("acme", "_404"));
        assertFalse(fragments.exists("acme", "_404"), "it is an error page, never a fragment");
        String starter = fragments.readErrorPageHtml("acme", "_404").orElseThrow();
        assertTrue(starter.contains("Page not found"), "starter text for the status");
        assertTrue(fragments.readErrorPageLogic("acme", "_404").orElseThrow().contains("${master} :: head"),
                "it pulls the master's head like a page born from the master");

        mvc.perform(get("/__admin/fragments/_404/edit").session(admin)).andExpect(status().isOk());
        mvc.perform(post("/__admin/fragments/_404").session(admin).with(csrf())
                        .param("html", "<!DOCTYPE html><html><head><title>x</title></head><body>"
                                + "<main id=\"content\">LOST_IN_ACME</main></body></html>")
                        .param("thxml", ""))
                .andExpect(redirectedUrl("/__admin/fragments/_404/edit"));
        mvc.perform(get("/no-such-page-here"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(Matchers.containsString("LOST_IN_ACME")));

        String after = mvc.perform(get("/__admin/fragments").session(admin)).andReturn().getResponse()
                .getContentAsString();
        assertTrue(after.contains("404 Not Found"), "listed with its status");
        mvc.perform(post("/__admin/fragments/error-pages").session(admin).with(csrf()).param("code", "404"))
                .andExpect(flash().attributeExists("error"));

        mvc.perform(post("/__admin/fragments/_404/delete").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/__admin/fragments"));
        assertFalse(fragments.errorPageExists("acme", "_404"));
        mvc.perform(get("/no-such-page-here"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(Matchers.not(Matchers.containsString("LOST_IN_ACME"))));
    }

    @Test
    void onlyErrorStatusesMakeErrorPagesAndOnlyForAuthorizedUsers() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        for (String bad : new String[] {"200", "302", "600", "4044", "abc", "../4"}) {
            mvc.perform(post("/__admin/fragments/error-pages").session(admin).with(csrf()).param("code", bad))
                    .andExpect(flash().attributeExists("error"));
        }
        mvc.perform(post("/__admin/fragments/_404").session(admin).with(csrf()).param("html", "<x/>"))
                .andExpect(flash().attributeExists("error"));
        assertFalse(fragments.errorPageExists("acme", "_404"), "saving cannot conjure an error page up");
        mvc.perform(post("/__admin/fragments/error-pages").session(sessionFor("op")).with(csrf()).param("code", "500"))
                .andExpect(status().isForbidden());
        assertFalse(fragments.errorPageExists("acme", "_500"));
    }
}
