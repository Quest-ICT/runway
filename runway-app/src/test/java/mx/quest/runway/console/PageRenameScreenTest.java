package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageWriteService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import mx.quest.runway.authz.GrantFile;
import mx.quest.runway.authz.GrantStore;

/**
 * Renaming and deleting a page from its editor. Renaming: the files move to the new id, a derived path follows it and a custom one is kept.
 * {@code EDIT_PAGES} renames a draft or a page with its own path; moving a published page's public address also
 * needs the permission to publish. Invalid or taken ids change nothing. Each test uses its own pages.
 */
@SpringBootTest
class PageRenameScreenTest {

    private static final String PW = "Rename-Screen-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-rename");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme Corp", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        Path siteUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(siteUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));
        users.save(siteUsers, new User("acme-ed", "ed", hash, "Ed", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));
        users.save(siteUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        // "ed" may author content but not publish.
        new GrantStore(layout, yaml).saveSiteGrants("acme", GrantFile.empty().withUserPermission("acme-ed", "EDIT_PAGES"));

        Path templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        page(templates, "about", "path: /about\ndraft: false\n");
        page(templates, "pub", "path: /pub\ndraft: false\n");
        page(templates, "draftx", "path: /draftx\ndraft: true\n");
        page(templates, "custom", "path: /our-team\ndraft: false\n");
        page(templates, "taken", "path: /taken\ndraft: true\n");
        page(templates, "other", "path: /other\ndraft: true\n");
        page(templates, "gone", "path: /gone\ndraft: false\n");
        page(templates, "scrap", "path: /scrap\ndraft: true\n");
        page(templates, "live", "path: /live\ndraft: false\n");
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    private static void page(Path templates, String slug, String meta) throws IOException {
        Files.writeString(templates.resolve(slug + ".html"), "<html><body>" + slug + "</body></html>",
                StandardCharsets.UTF_8);
        Files.writeString(templates.resolve(slug + ".meta.yml"), meta, StandardCharsets.UTF_8);
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private PageStore pages;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void theEditorOffersRenameAndAPublisherMovesAPublishedPage() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        String html = mvc.perform(get("/__admin/pages/about/edit").session(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("action=\"/__admin/pages/about/rename\""), "the rename form is on the editor");
        mvc.perform(post("/__admin/pages/about/rename").session(admin).with(csrf()).param("to", "about-us"))
                .andExpect(redirectedUrl("/__admin/pages/about-us/edit"))
                .andExpect(flash().attribute("flash", "Renamed page \"about\" to \"about-us\"."));
        assertTrue(pages.find("acme", "about").isEmpty(), "the old id is gone");
        assertEquals("/about-us", pages.find("acme", "about-us").orElseThrow().path(), "the derived path follows");
    }

    @Test
    void anEditorWhoMayNotPublishCannotMoveAPublishedAddress() throws Exception {
        mvc.perform(post("/__admin/pages/pub/rename").session(sessionFor("ed")).with(csrf()).param("to", "moved"))
                .andExpect(redirectedUrl("/__admin/pages/pub/edit"))
                .andExpect(flash().attributeExists("error"));
        assertTrue(pages.find("acme", "pub").isPresent(), "unchanged");
        assertTrue(pages.find("acme", "moved").isEmpty());
    }

    @Test
    void anEditorRenamesADraftAndAPageWithItsOwnPath() throws Exception {
        MockHttpSession ed = sessionFor("ed");
        mvc.perform(post("/__admin/pages/draftx/rename").session(ed).with(csrf()).param("to", "draft-y"))
                .andExpect(redirectedUrl("/__admin/pages/draft-y/edit"));
        assertEquals("/draft-y", pages.find("acme", "draft-y").orElseThrow().path());
        mvc.perform(post("/__admin/pages/custom/rename").session(ed).with(csrf()).param("to", "team"))
                .andExpect(redirectedUrl("/__admin/pages/team/edit"));
        assertEquals("/our-team", pages.find("acme", "team").orElseThrow().path(),
                "a page's own path is kept, so its public address does not move");
    }

    @Test
    void aTakenOrInvalidIdChangesNothing() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(post("/__admin/pages/other/rename").session(admin).with(csrf()).param("to", "taken"))
                .andExpect(flash().attribute("error", "A page with that id already exists."));
        mvc.perform(post("/__admin/pages/other/rename").session(admin).with(csrf()).param("to", "../escape"))
                .andExpect(flash().attribute("error", "The page id is not valid."));
        mvc.perform(post("/__admin/pages/ghost/rename").session(admin).with(csrf()).param("to", "x"))
                .andExpect(flash().attribute("error", "No such page."));
        assertTrue(pages.find("acme", "other").isPresent());
        assertTrue(pages.find("acme", "taken").isPresent());
    }

    @Test
    void withoutEditPagesRenameIsForbidden() throws Exception {
        mvc.perform(post("/__admin/pages/taken/rename").session(sessionFor("op")).with(csrf()).param("to", "nope"))
                .andExpect(status().isForbidden());
        assertTrue(pages.find("acme", "taken").isPresent());
    }

    @Test
    void aPublisherDeletesAPublishedPageAfterConfirming() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        String html = mvc.perform(get("/__admin/pages/gone/edit").session(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("action=\"/__admin/pages/gone/delete\""));
        assertTrue(html.contains("data-confirm=\"Delete page &quot;gone&quot; (/gone)?"), "the button asks first");
        mvc.perform(post("/__admin/pages/gone/delete").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/__admin/pages"))
                .andExpect(flash().attribute("flash", "Deleted page \"gone\"."));
        assertTrue(pages.find("acme", "gone").isEmpty());
    }

    @Test
    void anEditorDeletesADraftButNotAPublishedPage() throws Exception {
        MockHttpSession ed = sessionFor("ed");
        mvc.perform(post("/__admin/pages/scrap/delete").session(ed).with(csrf()))
                .andExpect(redirectedUrl("/__admin/pages"));
        assertTrue(pages.find("acme", "scrap").isEmpty(), "a draft goes with EDIT_PAGES");
        mvc.perform(post("/__admin/pages/live/delete").session(ed).with(csrf()))
                .andExpect(redirectedUrl("/__admin/pages/live/edit"))
                .andExpect(flash().attributeExists("error"));
        assertTrue(pages.find("acme", "live").isPresent(), "a published page needs the permission to publish");
        mvc.perform(post("/__admin/pages/live/delete").session(sessionFor("op")).with(csrf()))
                .andExpect(status().isForbidden());
    }
}
