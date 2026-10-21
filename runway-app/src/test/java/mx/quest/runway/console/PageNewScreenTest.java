package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.page.Page;
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
 * Creating pages: the master picker, born-from-master (which previews finished through the pulled
 * master chrome), standalone bare pages, duplicate-slug rejection, and EDIT_PAGES gating.
 */
@SpringBootTest
class PageNewScreenTest {

    private static final String PW = "New-Screen-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-e5");
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
                <body><header id="site-header"><span id="brand">PH</span></header>
                <main id="content">m</main><footer id="site-footer">Foot</footer></body></html>""");
        write(templates.resolve("_master.th.xml"), """
                <?xml version="1.0"?>
                <thlogic><attr sel="#brand" th:text="${site.displayName()}"/></thlogic>""");
        // An error page shares the folder — it must NOT show up in the master picker.
        write(templates.resolve("_404.html"), "<!DOCTYPE html><html><body>not found</body></html>");

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
    void theNewFormListsTheMastersAndStandaloneButNotErrorPages() throws Exception {
        String html = mvc.perform(get("/__admin/pages/new").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("value=\"_master\""), "the master is offered");
        assertTrue(html.contains("value=\"\""), "the standalone option is offered");
        assertTrue(!html.contains("_404"), "an error page is not a master option");
    }

    @Test
    void bornFromMasterCreatesADraftThatPreviewsFinishedAndOpensTheEditor() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(post("/__admin/pages/new").session(admin).with(csrf())
                        .param("slug", "about").param("title", "About Us").param("master", "_master"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/__admin/pages/about/edit"));

        Page about = pages.find("acme", "about").orElseThrow();
        assertTrue(about.draft(), "a new page is a draft");
        assertEquals("About Us", about.title().get("en"));
        assertTrue(writer.readLogic("acme", "about").orElseThrow().contains("${master}"),
                "the generated .th.xml pulls the master via the model variable");

        // It previews finished — the pulled master chrome renders.
        mvc.perform(get("/__admin/pages/about/preview").session(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Acme Corp")));
    }

    @Test
    void standaloneCreatesABarePageWithNoLogic() throws Exception {
        mvc.perform(post("/__admin/pages/new").session(sessionFor("admin")).with(csrf())
                        .param("slug", "solo").param("title", "Solo").param("master", ""))
                .andExpect(redirectedUrl("/__admin/pages/solo/edit"));
        assertTrue(pages.find("acme", "solo").isPresent());
        assertTrue(writer.readLogic("acme", "solo").isEmpty(), "a standalone page has no .th.xml");
        assertTrue(writer.readBody("acme", "solo").orElseThrow().contains("<!DOCTYPE html>"));
    }

    @Test
    void creatingADuplicateSlugIsRejected() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(post("/__admin/pages/new").session(admin).with(csrf())
                .param("slug", "dup").param("master", "")).andExpect(status().is3xxRedirection());
        // A second create with the same slug returns to the form (error), not the editor.
        mvc.perform(post("/__admin/pages/new").session(admin).with(csrf())
                        .param("slug", "dup").param("master", ""))
                .andExpect(redirectedUrl("/__admin/pages/new"));
    }

    @Test
    void anOperatorWithoutEditPagesCannotCreate() throws Exception {
        mvc.perform(post("/__admin/pages/new").session(sessionFor("op")).with(csrf())
                        .param("slug", "x").param("master", ""))
                .andExpect(status().isForbidden());
    }

    @Test
    void theNewPageButtonAppearsOnThePagesList() throws Exception {
        mvc.perform(get("/__admin/pages").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/__admin/pages/new")));
    }
}
