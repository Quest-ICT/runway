package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.authz.GrantFile;
import mx.quest.runway.authz.GrantStore;
import mx.quest.runway.authz.Role;
import mx.quest.runway.authz.RoleStore;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
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
 * {@code VIEW_APP_DASHBOARD} and {@code VIEW_TENANCIES} on the application console. The dashboard's content (counts
 * and plugin cards) needs the first, but {@code /__app} never answers {@code 403} — it is where sign-in lands, so an
 * operator without it gets a minimal landing. The Sites list needs the second (or {@code MANAGE_SITE_HOSTS}) and is
 * read-only for a holder: no lifecycle affordance renders and every lifecycle POST stays platform-only.
 */
@SpringBootTest
class AppConsolePermissionsTest {

    private static final String PW = "App-Permissions-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-app-permissions");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme Ltd", List.of("acme.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        UserStore users = new UserStore(yaml);
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        Path app = external.resolve("app-users.yml");
        users.save(app, new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(app, appUser("sysadmin-id", "sysadmin", hash, Roles.SYSTEM_ADMIN));
        users.save(app, appUser("op-id", "op", hash, Roles.SYSTEM_OPERATOR));
        users.save(app, appUser("dash-id", "dash", hash, Roles.SYSTEM_OPERATOR));
        users.save(app, appUser("viewer-id", "viewer", hash, Roles.SYSTEM_OPERATOR));
        users.save(app, appUser("roleviewer-id", "roleviewer", hash, Roles.SYSTEM_OPERATOR));
        new GrantStore(layout, yaml).saveAppGrants(GrantFile.empty()
                .withUserPermission("dash-id", "VIEW_APP_DASHBOARD")
                .withUserPermission("viewer-id", "VIEW_TENANCIES")
                // Through a custom ROLE this time: a role carrying the permission opens the list like a direct grant.
                .withUserRole("roleviewer-id", "TENANCY_VIEWERS"));
        new RoleStore(layout, yaml).saveAppRoles(List.of(new Role("TENANCY_VIEWERS", Realm.APPLICATION,
                Set.of("VIEW_TENANCIES"), Set.of())));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    private static User appUser(String id, String username, String hash, String tier) {
        return new User(id, username, hash, username, "", true, false, Realm.APPLICATION, Set.of(tier), List.of());
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteStore siteStore;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PW))
                .andExpect(redirectedUrl("/__app")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String okPage(String path, MockHttpSession session) throws Exception {
        return mvc.perform(get(path).session(session)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    // --- VIEW_APP_DASHBOARD -----------------------------------------------------------------------------

    @Test
    void anOperatorWithoutViewAppDashboardLandsOnAMinimalPageNotA403() throws Exception {
        String html = okPage("/__app", login("op"));
        assertTrue(html.contains("id=\"home-minimal\""), "the minimal landing renders");
        assertFalse(html.contains("id=\"home-dashboard\"") || html.contains("id=\"home-count-sites\""),
                "no dashboard counts without VIEW_APP_DASHBOARD");
    }

    @Test
    void anOperatorGrantedViewAppDashboardSeesTheDashboard() throws Exception {
        String html = okPage("/__app", login("dash"));
        assertTrue(html.contains("id=\"home-count-sites\""), "the counts render");
        assertFalse(html.contains("id=\"home-minimal\""), "and the minimal landing does not");
        assertFalse(html.contains("href=\"/__app/sites\" class=\"card"),
                "the Sites tile does not link to a list this operator may not open");
    }

    @Test
    void theSuperadminAndSystemAdminSeeTheDashboardWithItsLinks() throws Exception {
        for (String username : List.of("root", "sysadmin")) {
            String html = okPage("/__app", login(username));
            assertTrue(html.contains("id=\"home-count-sites\""), username + " sees the counts");
            assertTrue(html.contains("href=\"/__app/sites\" class=\"card card-link\" id=\"home-link-sites\""),
                    username + ": the Sites tile links to the list");
        }
    }

    // --- VIEW_TENANCIES ---------------------------------------------------------------------------------

    @Test
    void anOperatorWithoutViewTenanciesCannotOpenTheSitesList() throws Exception {
        mvc.perform(get("/__app/sites").session(login("op"))).andExpect(status().isForbidden());
        mvc.perform(get("/__app/sites").session(login("dash"))).andExpect(status().isForbidden());
    }

    @Test
    void viewTenanciesOpensAReadOnlyList() throws Exception {
        for (String username : List.of("viewer", "roleviewer")) {
            MockHttpSession session = login(username);
            String html = okPage("/__app/sites", session);
            assertTrue(html.contains("Acme Ltd"), username + " sees the sites");
            assertFalse(html.contains("/__app/sites/create") || html.contains("/__app/sites/install-demo")
                            || html.contains("/__app/sites/acme/suspend") || html.contains("/__app/sites/acme/delete")
                            || html.contains("/__app/sites/acme/edit") || html.contains("/__app/sites/acme/hosts"),
                    username + ": no create, demo, lifecycle, edit or host affordance");
            mvc.perform(post("/__app/sites/acme/suspend").session(session).with(csrf()))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/__app/sites/acme/hosts").session(session).with(csrf()).param("hosts", "x.test"))
                    .andExpect(status().isForbidden());
        }
        assertTrue(siteStore.find("acme").orElseThrow().active(), "nothing changed");
    }

    @Test
    void theSuperadminAndSystemAdminStillManageSites() throws Exception {
        for (String username : List.of("root", "sysadmin")) {
            String html = okPage("/__app/sites", login(username));
            assertTrue(html.contains("/__app/sites/create") && html.contains("/__app/sites/acme/suspend"),
                    username + " keeps the lifecycle affordances");
        }
    }
}
