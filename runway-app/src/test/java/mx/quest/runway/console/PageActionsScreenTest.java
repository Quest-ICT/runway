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

/**
 * Page actions: disable/enable flip the draft flag (gated by DISABLE_PAGES) and re-seed refreshes a
 * page's chrome from its master (gated by EDIT_PAGES), all bound-tenancy only.
 */
@SpringBootTest
class PageActionsScreenTest {

    private static final String PW = "Actions-Screen-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-e6");
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
        users.save(siteUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));

        Path templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        write(templates.resolve("_master.html"), """
                <!DOCTYPE html><html><head><title>Master</title></head>
                <body><header id="site-header">FRESH_HEADER</header>
                <main id="content">m</main><footer id="site-footer">FRESH_FOOTER</footer></body></html>""");
        write(templates.resolve("home.html"), "<html><body>home</body></html>");
        write(templates.resolve("home.meta.yml"), "path: /home\ndraft: false\n");   // published → can disable
        write(templates.resolve("draftp.html"), "<html><body>draft</body></html>");
        write(templates.resolve("draftp.meta.yml"), "path: /draftp\ndraft: true\n"); // draft → can enable
        // A page whose html materialized STALE chrome, to be re-seeded from the master.
        write(templates.resolve("stale.html"), "<!DOCTYPE html><html><head><title>Stale</title></head>"
                + "<body><header id=\"site-header\">STALE_HEADER</header>"
                + "<main id=\"content\">STALE_CONTENT_KEEP</main>"
                + "<footer id=\"site-footer\">STALE_FOOTER</footer></body></html>");
        write(templates.resolve("stale.meta.yml"), "path: /stale\ntemplate: _master\ndraft: true\n");

        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private PageStore pages;
    @Autowired
    private PageWriteService writer;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void disableMakesAPublishedPageADraft() throws Exception {
        mvc.perform(post("/__admin/pages/home/disable").session(sessionFor("admin")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertTrue(pages.find("acme", "home").orElseThrow().draft(), "the page is now a draft");
    }

    @Test
    void enablePublishesADraftPage() throws Exception {
        mvc.perform(post("/__admin/pages/draftp/enable").session(sessionFor("admin")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertTrue(pages.find("acme", "draftp").orElseThrow().published(), "the page is now published");
    }

    @Test
    void anOperatorWithoutDisablePagesCannotToggleVisibility() throws Exception {
        mvc.perform(post("/__admin/pages/home/disable").session(sessionFor("op")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void reseedRefreshesTheChromeFromTheMasterAndKeepsContent() throws Exception {
        mvc.perform(post("/__admin/pages/stale/reseed").session(sessionFor("admin")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        String body = writer.readBody("acme", "stale").orElseThrow();
        assertTrue(body.contains("FRESH_HEADER"), "the master's header was seeded in");
        assertTrue(body.contains("FRESH_FOOTER"), "the master's footer was seeded in");
        assertFalse(body.contains("STALE_HEADER"), "the stale header is gone");
        assertTrue(body.contains("STALE_CONTENT_KEEP"), "the author's content is preserved");
    }

    @Test
    void anOperatorWithoutEditPagesCannotReseed() throws Exception {
        mvc.perform(post("/__admin/pages/stale/reseed").session(sessionFor("op")).with(csrf()))
                .andExpect(status().isForbidden());
    }
}
