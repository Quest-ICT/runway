package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * The plugin template seam: a loaded (library-only) plugin's dashboard card renders on the app home, and its
 * admin fragment renders below the per-tenant settings form — both pulled from {@code __plugin__/<id>/…} via
 * {@link PluginTemplateResolver}, proving a plugin surfaces UI from its own namespace with no host edit.
 */
@SpringBootTest
class PluginFragmentTest {

    private static final String PW = "Fragment-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-fg");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        UserStore users = new UserStore(yaml);
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        users.save(external.resolve("app-users.yml"),
                new User("app-master", "admin", hash, "Admin", "", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(layout.siteDir("acme").resolve("users.yml"),
                new User("acme-admin", "boss", hash, "Boss", "", true, false, Realm.TENANT,
                        Set.of(Roles.ADMIN), List.of()));

        // A library-only plugin that ships a dashboard card and an admin fragment from its own templates/.
        Path plugin = Files.createDirectories(external.resolve("plugins").resolve("widgets"));
        Files.writeString(plugin.resolve("plugin.yml"), """
                id: widgets
                version: 1.0.0
                siteSettings:
                  - key: enabled
                    type: boolean
                dashboardCards:
                  - realm: application
                    order: 1
                    fragment: card
                """);
        Path templates = Files.createDirectories(plugin.resolve("templates"));
        Files.writeString(templates.resolve("card.html"),
                "<div class=\"card\"><div class=\"card-body\" id=\"wcard\">WIDGET-CARD-MARKER</div></div>");
        Files.writeString(templates.resolve("card.th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>");
        Files.writeString(templates.resolve("admin.html"),
                "<div class=\"card\"><div class=\"card-body\" id=\"wadmin\">WIDGET-ADMIN-MARKER</div></div>");
        Files.writeString(templates.resolve("admin.th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>");

        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new SiteContextFilter(siteResolver)).apply(springSecurity()).build();
    }

    @Test
    void aPluginDashboardCardRendersOnTheAppHome() throws Exception {
        MvcResult login = mvc.perform(formLogin("/app/login").user("admin").password(PW)).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        String html = mvc.perform(get("/__app").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("WIDGET-CARD-MARKER"), "the plugin's dashboard card was pulled from its namespace");
    }

    @Test
    void aPluginAdminFragmentRendersBelowThePerTenantSettings() throws Exception {
        MvcResult login = mvc.perform(formLogin("/login").user("boss").password(PW)).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        String html = mvc.perform(get("/__admin/plugins/widgets/settings").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("WIDGET-ADMIN-MARKER"), "the plugin's admin fragment was pulled below the form");
    }
}
