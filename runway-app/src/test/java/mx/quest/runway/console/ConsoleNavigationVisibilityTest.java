package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.authz.GrantFile;
import mx.quest.runway.authz.GrantStore;
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
 * The consoles' topbars offer only what the operator may open. For every kernel entry of both topbars, the
 * entry is shown exactly when its route does not answer {@code 403} — checked by requesting each route as an
 * under-privileged operator, a partially granted one, and an administrator — and a dropdown with nothing left
 * in it is hidden. This is the test {@link ConsoleNavigation} points at: a route whose gate changes without its
 * menu row fails here.
 */
@SpringBootTest
class ConsoleNavigationVisibilityTest {

    private static final String PW = "Nav-Visibility-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-nav-visibility");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        UserStore users = new UserStore(yaml);
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        Path appFile = external.resolve("app-users.yml");
        users.save(appFile, new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(appFile, new User("op-id", "op", hash, "Op", "", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        users.save(appFile, new User("auditor-id", "auditor", hash, "Auditor", "", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        users.save(appFile, new User("viewer-id", "viewer", hash, "Viewer", "", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        GrantStore grants = new GrantStore(layout, yaml);
        grants.saveAppGrants(GrantFile.empty().withUserPermission("auditor-id", "VIEW_AUDIT")
                .withUserPermission("viewer-id", "VIEW_TENANCIES"));
        Path acmeUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(acmeUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));
        users.save(acmeUsers, new User("acme-op", "siteop", hash, "Site Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));
        users.save(acmeUsers, new User("acme-analyst", "analyst", hash, "Analyst", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));
        users.save(acmeUsers, new User("acme-usermgr", "usermgr", hash, "User manager", "", true, false,
                Realm.TENANT, Set.of(Roles.OPERATOR), List.of()));
        users.save(acmeUsers, new User("acme-deleter", "deleter", hash, "Deleter", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));
        grants.saveSiteGrants("acme", GrantFile.empty().withUserPermission("acme-analyst", "VIEW_SITE_INSIGHTS")
                .withUserPermission("acme-usermgr", "MANAGE_USERS")
                .withUserPermission("acme-deleter", "DELETE_USERS"));
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
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private MockHttpSession appLogin(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PW))
                .andExpect(redirectedUrl("/__app")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession siteLogin(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/login").user(username).password(PW))
                .andExpect(redirectedUrl("/__admin")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String page(String path, MockHttpSession session) throws Exception {
        return mvc.perform(get(path).session(session)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    /** The topbar's menu only — the part between the nav collapse and the end of the header. */
    private static String menu(String html) {
        int start = html.indexOf("id=\"navbar-menu\"");
        int end = html.indexOf("</header>", start);
        assertTrue(start >= 0 && end > start, "the page renders the topbar menu");
        return html.substring(start, end);
    }

    private static boolean offers(String menu, String path) {
        return menu.contains("href=\"" + path + "\"");
    }

    /**
     * For each entry: visible in the topbar ⇔ its route does not answer 403. Returns the hidden paths, so callers
     * can also assert WHICH were hidden (a gate that hides everything would satisfy the equivalence alone).
     */
    private List<String> assertMenuMatchesRoutes(List<ConsoleNavigation.Entry> entries, String home,
                                                 MockHttpSession session) throws Exception {
        String menu = menu(page(home, session));
        List<String> hidden = new ArrayList<>();
        for (ConsoleNavigation.Entry entry : entries) {
            int status = mvc.perform(get(entry.path()).session(session)).andReturn().getResponse().getStatus();
            boolean shown = offers(menu, entry.path());
            assertEquals(status != 403, shown, entry.path() + " answered " + status + " but the topbar "
                    + (shown ? "offers" : "hides") + " it");
            if (!shown) {
                hidden.add(entry.path());
            }
        }
        return hidden;
    }

    // --- application console ---------------------------------------------------------------------------

    @Test
    void anAppOperatorIsOfferedOnlyWhatTheyMayOpenAndAnEmptyGroupDisappears() throws Exception {
        MockHttpSession op = appLogin("op");
        List<String> hidden = assertMenuMatchesRoutes(ConsoleNavigation.APP, "/__app", op);
        assertEquals(List.of("/__app/sites", "/__app/backups", "/__app/site-templates", "/__app/permissions",
                "/__app/bandwidth", "/__app/logs", "/__app/settings", "/__app/config/server",
                "/__app/embed-hosts"), hidden);

        String menu = menu(page("/__app", op));
        assertFalse(menu.contains("id=\"grp-activity\""), "Activity has nothing left for this operator: no dropdown");
        assertTrue(menu.contains("id=\"grp-sites\"") && menu.contains("id=\"grp-access\"")
                && menu.contains("id=\"grp-settings\""), "groups that still hold an entry stay");
    }

    @Test
    void anAppOperatorGrantedViewAuditSeesLogsAndTheActivityGroup() throws Exception {
        MockHttpSession auditor = appLogin("auditor");
        List<String> hidden = assertMenuMatchesRoutes(ConsoleNavigation.APP, "/__app", auditor);
        assertFalse(hidden.contains("/__app/logs"), "the grant opens the route, so the entry is offered");
        String menu = menu(page("/__app", auditor));
        assertTrue(menu.contains("id=\"grp-activity\""));
        assertFalse(offers(menu, "/__app/bandwidth"), "but not what was not granted");
    }

    @Test
    void anAppOperatorGrantedViewTenanciesSeesTheSitesList() throws Exception {
        MockHttpSession viewer = appLogin("viewer");
        List<String> hidden = assertMenuMatchesRoutes(ConsoleNavigation.APP, "/__app", viewer);
        assertFalse(hidden.contains("/__app/sites"), "VIEW_TENANCIES opens the list, so the entry is offered");
        assertTrue(hidden.contains("/__app/backups"), "but no platform screen comes with it");
    }

    @Test
    void theApplicationSuperadminSeesEverything() throws Exception {
        assertEquals(List.of(), assertMenuMatchesRoutes(ConsoleNavigation.APP, "/__app", appLogin("root")));
    }

    // --- site console ----------------------------------------------------------------------------------

    @Test
    void aSiteOperatorWithNoGrantsSeesOnlyTheDashboard() throws Exception {
        MockHttpSession op = siteLogin("siteop");
        List<String> hidden = assertMenuMatchesRoutes(ConsoleNavigation.TENANT, "/__admin", op);
        assertEquals(ConsoleNavigation.TENANT.size() - 1, hidden.size(), "everything but the dashboard: " + hidden);
        String menu = menu(page("/__admin", op));
        for (String group : List.of("grp-resources", "grp-access", "grp-activity", "grp-settings")) {
            assertFalse(menu.contains("id=\"" + group + "\""), group + " is empty, so it is not rendered");
        }
    }

    @Test
    void aSiteOperatorGrantedInsightsSeesInsightsButNotLogsOrBandwidth() throws Exception {
        MockHttpSession analyst = siteLogin("analyst");
        List<String> hidden = assertMenuMatchesRoutes(ConsoleNavigation.TENANT, "/__admin", analyst);
        assertFalse(hidden.contains("/__admin/insights"));
        assertTrue(hidden.contains("/__admin/logs") && hidden.contains("/__admin/bandwidth"));
        assertTrue(menu(page("/__admin", analyst)).contains("id=\"grp-activity\""));
    }

    @Test
    void aSiteOperatorGrantedManageUsersOrDeleteUsersSeesUsersButNotPermissions() throws Exception {
        for (String username : List.of("usermgr", "deleter")) {
            MockHttpSession session = siteLogin(username);
            List<String> hidden = assertMenuMatchesRoutes(ConsoleNavigation.TENANT, "/__admin", session);
            assertFalse(hidden.contains("/__admin/users"), username + ": the grant opens the Users screen");
            assertTrue(hidden.contains("/__admin/permissions"), username + ": the permissions matrix stays admin-only");
            assertTrue(menu(page("/__admin", session)).contains("id=\"grp-access\""), "the Access group is shown");
        }
    }

    @Test
    void aSiteAdministratorSeesEverything() throws Exception {
        assertEquals(List.of(), assertMenuMatchesRoutes(ConsoleNavigation.TENANT, "/__admin", siteLogin("admin")));
    }
}
