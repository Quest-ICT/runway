package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.quest.runway.authz.GrantFile;
import mx.quest.runway.authz.GrantStore;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.page.PageReview;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageWriteService;
import mx.quest.runway.page.PendingRevisionStore;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.EditorialLevel;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteContextFilter;
import mx.quest.runway.site.SiteLifecycleService;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The editorial workflow end to end through the tenant console and the public site, with real rendering
 * and the real quality checks: a reviewed site's pending revision leaves the live page (body, ETag, routing)
 * untouched until approved; approval, send-back, submit and withdraw; the enforced gate refusing a publish and a
 * live save; the Site config level switch and who may change it; every new route's permission (403); and that a
 * site's queue can never show or act on another site's pages.
 *
 * <p>Users on {@code acme}: {@code admin} (wildcard), {@code editor} (EDIT_PAGES + DISABLE_PAGES, no approval),
 * {@code approver} (EDIT_PAGES + APPROVE_PAGES), {@code reviewer} (APPROVE_PAGES only), {@code configurer}
 * (CONFIGURE_SITE only) and {@code op} (nothing). Each test sets the level it needs and uses its own pages.
 */
@SpringBootTest
class EditorialWorkflowScreenTest {

    private static final String PW = "Editorial-Flow-7";
    private static Path templates;
    private static Path betaTemplates;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-editorial");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        sites.save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE));
        sites.save(new Site("beta", "Beta", List.of("beta.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE, false, null, EditorialLevel.REVIEWED));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        Path siteUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(siteUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));
        for (String name : List.of("editor", "approver", "reviewer", "configurer", "op")) {
            users.save(siteUsers, new User("acme-" + name, name, hash, name, "", true, false, Realm.TENANT,
                    Set.of(Roles.OPERATOR), List.of()));
        }
        new GrantStore(layout, yaml).saveSiteGrants("acme", GrantFile.empty()
                .withUserPermission("acme-editor", "EDIT_PAGES")
                .withUserPermission("acme-editor", "DISABLE_PAGES")
                .withUserPermission("acme-approver", "EDIT_PAGES")
                .withUserPermission("acme-approver", "APPROVE_PAGES")
                .withUserPermission("acme-reviewer", "APPROVE_PAGES")
                .withUserPermission("acme-configurer", "CONFIGURE_SITE"));
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));

        templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        for (String slug : List.of("rev1", "rev2", "rev3", "rev4", "rev5", "enf2", "enf3")) {
            page(templates, slug, false, "Title " + slug, "LIVE_" + slug, "");
        }
        page(templates, "draft1", true, "Title draft1", "DRAFT_draft1", "");
        // Re-seed: a master with fresh chrome, and bare published pages carrying a stale copy of it — a
        // page without .th.xml seams serves its own .html, so a re-seed changes what visitors see.
        Files.writeString(templates.resolve("_master.html"), "<!DOCTYPE html><html><head><title>m</title></head>"
                + "<body><header id=\"site-header\">FRESH_HEADER</header><main id=\"content\"></main></body></html>",
                StandardCharsets.UTF_8);
        for (String slug : List.of("rs1", "rs2", "rs3")) {
            page(templates, slug, false, "Title " + slug, "LIVE_" + slug, slug.equals("rs3") ? "<img src=\"x.png\">" : "");
            Files.writeString(templates.resolve(slug + ".html"), Files.readString(templates.resolve(slug + ".html"))
                    .replace("<body>", "<body><header id=\"site-header\">STALE_HEADER</header>"),
                    StandardCharsets.UTF_8);
        }
        page(templates, "enf1", true, "Title enf1", "DRAFT_enf1", "<img src=\"x.png\">");

        betaTemplates = layout.siteDir("beta").resolve("templates");
        Files.createDirectories(betaTemplates);
        page(betaTemplates, "secret-beta", true, "Beta secret", "BETA_ONLY", "");
        Files.createDirectories(betaTemplates.resolve(".pending/secret-beta"));
        yaml.write(betaTemplates.resolve(".pending/secret-beta/review.yml"),
                PageReview.submitted("beta-ed", Instant.parse("2026-10-01T00:00:00Z")));

        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    /** A bare page (no master, no logic) whose rendered markup passes every ERROR check unless {@code extra} adds one. */
    private static void page(Path dir, String slug, boolean draft, String title, String marker, String extra)
            throws IOException {
        Files.writeString(dir.resolve(slug + ".html"), html(marker, extra), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(slug + ".meta.yml"),
                "path: /" + slug + "\ndraft: " + draft + "\ntitle:\n  en: " + title + "\n", StandardCharsets.UTF_8);
    }

    private static String html(String marker, String extra) {
        return "<!DOCTYPE html><html><head><title>t</title></head><body><main><h1>Heading</h1><p>" + marker
                + "</p>" + extra + "</main></body></html>";
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private SiteLifecycleService lifecycle;
    @Autowired
    private SiteStore siteStore;
    @Autowired
    private PageStore pages;
    @Autowired
    private PageWriteService writer;
    @MockitoSpyBean
    private SiteLogService siteLog;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
        lifecycle.updateEditorial("acme", EditorialLevel.REVIEWED);
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private org.springframework.test.web.servlet.ResultActions save(MockHttpSession session, String slug,
                                                                    String title, String marker, String extra)
            throws Exception {
        return mvc.perform(post("/__admin/pages/" + slug).session(session).with(csrf())
                .param("html", html(marker, extra)).param("path", "/" + slug)
                .param("template", "_master").param("title[en]", title));
    }

    private String body(String slug) {
        return writer.readBody("acme", slug).orElseThrow();
    }

    private String fingerprintOn(MockHttpSession session, String slug) throws Exception {
        String page = mvc.perform(get("/__admin/review/" + slug).session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("id=\"ri-fingerprint\"[^>]*value=\"([0-9a-f]{64})\"").matcher(page);
        assertTrue(m.find(), "the review screen carries the fingerprint");
        return m.group(1);
    }

    // --- reviewed: a live edit waits as a pending revision ---------------------------------------------

    @Test
    void aReviewedLiveEditIsInvisibleUntilApprovedThenReplacesTheLivePage() throws Exception {
        MvcResult before = mvc.perform(get("/rev1")).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("LIVE_rev1"))).andReturn();
        String etag = before.getResponse().getHeader("ETag");
        assertNotNull(etag, "the live page carries an ETag");

        MockHttpSession editor = login("editor");
        save(editor, "rev1", "Title rev1 v2", "PROPOSED_rev1", "")
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flash", Matchers.containsString("pending revision")));

        // The live page is exactly as it was: body on disk, public render, ETag.
        assertTrue(body("rev1").contains("LIVE_rev1"));
        MvcResult after = mvc.perform(get("/rev1")).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("LIVE_rev1")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("PROPOSED_rev1")))).andReturn();
        assertEquals(etag, after.getResponse().getHeader("ETag"), "the live ETag did not move");

        // The editor reopens the page and sees their revision, with the banner; the list shows the status.
        mvc.perform(get("/__admin/pages/rev1/edit").session(editor)).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("PROPOSED_rev1")))
                .andExpect(content().string(Matchers.containsString("You are editing a pending revision")))
                .andExpect(content().string(Matchers.containsString("/__admin/pages/rev1/preview?revision=1")));
        mvc.perform(get("/__admin/pages").session(editor)).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("data-status=\"published-pending\"")));

        // The reviewer sees it in the queue and on its screen (diff + both previews), then approves.
        MockHttpSession reviewer = login("reviewer");
        mvc.perform(get("/__admin/review").session(reviewer)).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("href=\"/__admin/review/rev1\"")));
        mvc.perform(get("/__admin/review/rev1").session(reviewer)).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("PROPOSED_rev1")))     // the diff's + line
                .andExpect(content().string(Matchers.containsString("rw-d-added")))
                .andExpect(content().string(Matchers.containsString("Title rev1 v2")))    // the metadata change
                .andExpect(content().string(Matchers.containsString("src=\"/__admin/pages/rev1/preview\"")));
        mvc.perform(get("/__admin/pages/rev1/preview").param("revision", "1").session(reviewer))
                .andExpect(status().isOk()).andExpect(content().string(Matchers.containsString("PROPOSED_rev1")));
        mvc.perform(post("/__admin/review/rev1/approve").session(reviewer).with(csrf())
                        .param("fingerprint", fingerprintOn(reviewer, "rev1")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flash"));

        assertTrue(body("rev1").contains("PROPOSED_rev1"));
        assertEquals("Title rev1 v2", pages.find("acme", "rev1").orElseThrow().title().get("en"));
        assertFalse(writer.pending().exists("acme", "rev1"));
        mvc.perform(get("/rev1")).andExpect(content().string(Matchers.containsString("PROPOSED_rev1")));
        verify(siteLog).audit(eq("acme"), eq("editor"), eq("page.revision.submitted"), eq("rev1"));
        verify(siteLog).audit(eq("acme"), eq("reviewer"), eq("page.review.approved"), eq("rev1"));
    }

    @Test
    void anApprovalOfContentThatChangedAfterItWasOpenedIsRefused() throws Exception {
        MockHttpSession editor = login("editor");
        save(editor, "rev2", "Title rev2", "FIRST_rev2", "").andExpect(status().is3xxRedirection());
        MockHttpSession reviewer = login("reviewer");
        String seen = fingerprintOn(reviewer, "rev2");
        save(editor, "rev2", "Title rev2", "SECOND_rev2", "").andExpect(status().is3xxRedirection());

        mvc.perform(post("/__admin/review/rev2/approve").session(reviewer).with(csrf()).param("fingerprint", seen))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", Matchers.containsString("changed")));
        assertTrue(body("rev2").contains("LIVE_rev2"), "nothing was published");
    }

    @Test
    void sendingBackNeedsANoteAndReturnsTheRevisionToItsEditor() throws Exception {
        MockHttpSession editor = login("editor");
        save(editor, "rev3", "Title rev3", "PROPOSED_rev3", "").andExpect(status().is3xxRedirection());
        MockHttpSession reviewer = login("reviewer");

        mvc.perform(post("/__admin/review/rev3/return").session(reviewer).with(csrf()).param("note", "  "))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", Matchers.containsString("note")));
        assertTrue(writer.pending().review("acme", "rev3").orElseThrow().isSubmitted());

        mvc.perform(post("/__admin/review/rev3/return").session(reviewer).with(csrf())
                        .param("note", "Please <b>cite</b> the source."))
                .andExpect(status().is3xxRedirection());
        assertTrue(writer.pending().review("acme", "rev3").orElseThrow().isReturned());
        assertTrue(body("rev3").contains("LIVE_rev3"));
        mvc.perform(get("/__admin/pages/rev3/edit").session(editor)).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Please &lt;b&gt;cite&lt;/b&gt; the source.")))
                .andExpect(content().string(Matchers.containsString("PROPOSED_rev3")));
        mvc.perform(get("/__admin/pages").session(editor))
                .andExpect(content().string(Matchers.containsString("data-status=\"published-returned\"")));
        mvc.perform(get("/__admin/review").session(reviewer))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/__admin/review/rev3"))));

        // Discarding the revision restores the plain published state.
        mvc.perform(post("/__admin/pages/rev3/withdraw").session(editor).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertFalse(writer.pending().exists("acme", "rev3"));
    }

    @Test
    void onAReviewedSiteAnEditorSubmitsADraftAndOnlyAnApproverPublishesIt() throws Exception {
        MockHttpSession editor = login("editor");
        // DISABLE_PAGES alone no longer publishes, and the list offers no Enable for the draft.
        mvc.perform(post("/__admin/pages/draft1/enable").session(editor).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", Matchers.containsString("requires review")));
        assertTrue(pages.find("acme", "draft1").orElseThrow().draft());
        mvc.perform(get("/__admin/pages").session(editor))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/__admin/pages/draft1/enable"))))
                .andExpect(content().string(Matchers.containsString("/__admin/pages/rev5/disable")));
        mvc.perform(get("/__admin/pages/draft1/edit").session(editor))
                .andExpect(content().string(Matchers.containsString("/__admin/pages/draft1/submit")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/__admin/pages/draft1/enable"))));

        mvc.perform(post("/__admin/pages/draft1/submit").session(editor).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flash"));
        mvc.perform(get("/__admin/pages").session(editor))
                .andExpect(content().string(Matchers.containsString("data-status=\"in-review\"")));

        MockHttpSession reviewer = login("reviewer");
        mvc.perform(get("/__admin/review/draft1").session(reviewer)).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("First publication")));
        mvc.perform(post("/__admin/review/draft1/approve").session(reviewer).with(csrf())
                        .param("fingerprint", fingerprintOn(reviewer, "draft1")))
                .andExpect(status().is3xxRedirection());
        assertTrue(pages.find("acme", "draft1").orElseThrow().published());
        mvc.perform(get("/draft1")).andExpect(status().isOk());
    }

    @Test
    void anApproverPublishesTheirOwnEditDirectly() throws Exception {
        MockHttpSession approver = login("approver");
        mvc.perform(get("/__admin/pages/rev4/edit").session(approver))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Save as pending revision"))));
        save(approver, "rev4", "Title rev4", "DIRECT_rev4", "").andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flash", Matchers.not(Matchers.containsString("pending"))));
        assertTrue(body("rev4").contains("DIRECT_rev4"));
        assertFalse(writer.pending().exists("acme", "rev4"));
    }

    // --- enforced -----------------------------------------------------------------------------------------

    @Test
    void enforcedRefusesToPublishAPageWithErrors() throws Exception {
        lifecycle.updateEditorial("acme", EditorialLevel.ENFORCED);
        MockHttpSession editor = login("editor");
        mvc.perform(post("/__admin/pages/enf1/enable").session(editor).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", Matchers.containsString("no alt attribute")));
        assertTrue(pages.find("acme", "enf1").orElseThrow().draft());
        verify(siteLog).audit(eq("acme"), eq("editor"), eq("page.quality.blocked"), eq("enf1 publish: img.alt-missing"));
        // On an enforced (not reviewed) site DISABLE_PAGES still publishes a clean page.
        save(editor, "enf1", "Title enf1", "FIXED_enf1", "<img src=\"x.png\" alt=\"\">")
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/__admin/pages/enf1/enable").session(editor).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeCount(0));
        assertTrue(pages.find("acme", "enf1").orElseThrow().published());
    }

    @Test
    void enforcedRefusesALiveSaveWithErrorsAndKeepsTheAuthorsWork() throws Exception {
        lifecycle.updateEditorial("acme", EditorialLevel.ENFORCED);
        MockHttpSession editor = login("editor");
        // No title: an ERROR. The editor is re-shown (200, not a redirect) with the typed content.
        save(editor, "enf2", "", "TYPED_enf2", "")
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("TYPED_enf2")))
                .andExpect(content().string(Matchers.containsString("id=\"pe-ed-blocked\"")))
                .andExpect(content().string(Matchers.containsString("The page has no title")));
        assertTrue(body("enf2").contains("LIVE_enf2"), "nothing was written");
        // Warnings never block: no description, a generic link — saved.
        save(editor, "enf3", "Title enf3 new", "OK_enf3", "<a href=\"/x\">click here</a>")
                .andExpect(status().is3xxRedirection());
        assertTrue(body("enf3").contains("OK_enf3"));
    }

    // --- site config --------------------------------------------------------------------------------------

    @Test
    void theLevelIsChosenInSiteConfigAndOnlyAnApproverMayChangeIt() throws Exception {
        lifecycle.updateEditorial("acme", EditorialLevel.ADVISORY);
        MockHttpSession admin = login("admin");
        mvc.perform(get("/__admin/config").session(admin)).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("value=\"reviewed\"")));
        mvc.perform(post("/__admin/config").session(admin).with(csrf()).param("name", "Acme")
                        .param("defaultLocale", "en").param("locale", "en").param("editorial", "bogus"))
                .andExpect(flash().attribute("error", Matchers.containsString("editorial level")));
        assertEquals(EditorialLevel.ADVISORY, siteStore.find("acme").orElseThrow().editorial());
        mvc.perform(post("/__admin/config").session(admin).with(csrf()).param("name", "Acme")
                        .param("defaultLocale", "en").param("locale", "en").param("editorial", "reviewed"))
                .andExpect(status().is3xxRedirection());
        assertEquals(EditorialLevel.REVIEWED, siteStore.find("acme").orElseThrow().editorial());
        verify(siteLog).audit(eq("acme"), eq("admin"), eq("site-config.changed"), eq("editorial=reviewed"));

        MockHttpSession configurer = login("configurer");
        mvc.perform(get("/__admin/config").session(configurer)).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Only a user who may approve pages")))
                .andExpect(content().string(Matchers.matchesPattern("(?s).*id=\"cfg-ed-advisory\"[^>]*disabled.*")));
        mvc.perform(post("/__admin/config").session(configurer).with(csrf()).param("name", "Acme")
                        .param("defaultLocale", "en").param("locale", "en").param("editorial", "advisory"))
                .andExpect(flash().attribute("error", Matchers.containsString("Approve pages")));
        assertEquals(EditorialLevel.REVIEWED, siteStore.find("acme").orElseThrow().editorial(),
                "a configurer cannot switch review off");
        mvc.perform(post("/__admin/config").session(configurer).with(csrf()).param("name", "Acme")
                        .param("defaultLocale", "en").param("locale", "en"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("flash"));
        assertEquals(EditorialLevel.REVIEWED, siteStore.find("acme").orElseThrow().editorial(),
                "a form without the field keeps the level");
    }

    // --- authorization + isolation ------------------------------------------------------------------------

    @Test
    void everyNewRouteIsGated() throws Exception {
        MockHttpSession op = login("op");
        MockHttpSession editor = login("editor");
        MockHttpSession reviewer = login("reviewer");
        for (MockHttpSession noApprove : List.of(op, editor)) {
            mvc.perform(get("/__admin/review").session(noApprove)).andExpect(status().isForbidden());
            mvc.perform(get("/__admin/review/rev5").session(noApprove)).andExpect(status().isForbidden());
            mvc.perform(post("/__admin/review/rev5/approve").session(noApprove).with(csrf()).param("fingerprint", "x"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/__admin/review/rev5/return").session(noApprove).with(csrf()).param("note", "n"))
                    .andExpect(status().isForbidden());
        }
        for (MockHttpSession noEdit : List.of(op, reviewer)) {
            mvc.perform(post("/__admin/pages/rev5/submit").session(noEdit).with(csrf()))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/__admin/pages/rev5/withdraw").session(noEdit).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        // The preview admits an approver without EDIT_PAGES, and nobody without either.
        mvc.perform(get("/__admin/pages/rev5/preview").session(reviewer)).andExpect(status().isOk());
        mvc.perform(get("/__admin/pages/rev5/preview-window").param("revision", "1").session(reviewer))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/__admin/pages/rev5/preview?revision=1")));
        mvc.perform(get("/__admin/pages/rev5/preview").session(op)).andExpect(status().isForbidden());
        mvc.perform(get("/__admin/review")).andExpect(status().is3xxRedirection()); // anonymous → login
    }

    @Test
    void aSitesQueueNeverShowsOrActsOnAnotherSitesPages() throws Exception {
        MockHttpSession reviewer = login("reviewer");
        mvc.perform(get("/__admin/review").param("site", "beta").param("siteId", "beta").session(reviewer))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("secret-beta"))));
        mvc.perform(get("/__admin/review/secret-beta").session(reviewer))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/__admin/review/secret-beta/approve").session(reviewer).with(csrf())
                        .param("fingerprint", "x"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/__admin/review/secret-beta/return").session(reviewer).with(csrf()).param("note", "n"))
                .andExpect(status().is3xxRedirection());
        assertTrue(pages.find("beta", "secret-beta").orElseThrow().draft(), "beta's page was not published");
        assertTrue(writer.pending().review("beta", "secret-beta").orElseThrow().isSubmitted(),
                "beta's review was not touched");
    }

    // --- re-seed follows the editorial level --------------------------

    /**
     * The reproduction: on a reviewed site an editor without APPROVE_PAGES re-seeded a published page
     * and its live file changed ({@code page.saved}). Now the re-seed is a pending revision; the public page is
     * untouched until approved.
     */
    @Test
    void aReviewedReseedByANonApproverIsAPendingRevision() throws Exception {
        String before = body("rs1");
        mvc.perform(post("/__admin/pages/rs1/reseed").session(login("editor")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flash", Matchers.containsString("pending revision")));
        assertEquals(before, body("rs1"), "the live file is byte-for-byte unchanged");
        mvc.perform(get("/rs1")).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("STALE_HEADER")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("FRESH_HEADER"))));
        PendingRevisionStore.Revision revision = writer.pending().revision("acme", "rs1").orElseThrow();
        assertTrue(revision.html().contains("FRESH_HEADER") && revision.html().contains("LIVE_rs1"),
                "the re-seeded page waits for review, content kept");
        assertTrue(writer.pending().review("acme", "rs1").orElseThrow().isSubmitted(), "and it is in the queue");
    }

    @Test
    void aReviewedReseedByAnApproverWritesLiveAfterTheGate() throws Exception {
        mvc.perform(post("/__admin/pages/rs2/reseed").session(login("approver")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flash", "Chrome re-seeded from the master."));
        assertTrue(body("rs2").contains("FRESH_HEADER") && body("rs2").contains("LIVE_rs2"));
        assertFalse(writer.pending().exists("acme", "rs2"), "nothing pending");
    }

    @Test
    void anEnforcedReseedOfAPublishedPageWithQualityErrorsIsRefused() throws Exception {
        lifecycle.updateEditorial("acme", EditorialLevel.ENFORCED);
        String before = body("rs3");
        mvc.perform(post("/__admin/pages/rs3/reseed").session(login("editor")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", Matchers.startsWith("Not re-seeded")));
        assertEquals(before, body("rs3"), "nothing was written");
    }
}
