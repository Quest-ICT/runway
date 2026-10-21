package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
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
 * The plugin menu-contribution surface + the dashboard: a (library-only) plugin declares menu links and a
 * permission; the app home renders as a dashboard with counts, and the topbar shows the plugin's links —
 * permission-gated ones only to a principal who passes {@code mayAccess}.
 */
@SpringBootTest
class MenuAndDashboardTest {

    private static final String PASSWORD = "Menu-Dash-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-fb");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
        UserStore users = new UserStore(new YamlStore());
        Path file = external.resolve("app-users.yml");
        users.save(file, new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(file, new User("op-user", "operator", hash, "Operator", "o@x", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        // A code-less (library-only) plugin: it loads, so its menu + permission are contributed.
        Path plugin = Files.createDirectories(external.resolve("plugins").resolve("widgets"));
        Files.writeString(plugin.resolve("plugin.yml"), """
                id: widgets
                version: 1.0.0
                permissions:
                  - id: manage
                    realm: application
                menu:
                  - realm: application
                    order: 10
                    label: Widgets
                    path: /__app/widgets
                    icon: fa-solid fa-flask
                  - realm: application
                    order: 5
                    label: Gated
                    path: /__app/widgets/admin
                    permission: widgets|manage
                  - realm: application
                    region: settings
                    order: 20
                    label: WidgetCfg
                    path: /__app/widgets/cfg
                  - realm: application
                    region: activity
                    order: 20
                    label: WidgetAct
                    path: /__app/widgets/act
                """);
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PASSWORD)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void theAppHomeIsADashboardWithCounts() throws Exception {
        String html = mvc.perform(get("/__app").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains(">Sites<"), "the sites count card is present");
        assertTrue(html.contains(">Plugins<") || html.contains("Plugins loaded"), "the plugins count card is present");
        assertTrue(html.contains(">2<"), "the application-users count (master + operator) shows");
    }

    @Test
    void theAppMenuIsOrganizedIntoDropdownGroups() throws Exception {
        String html = mvc.perform(get("/__app").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // the flat bar is now Sites / Access / Activity / Settings dropdowns; Dashboard and Plugins stay top-level
        assertTrue(html.contains("grp-sites") && html.contains("grp-access") && html.contains("grp-activity"),
                "the app nav is grouped into Sites/Access/Activity dropdowns");
        // the old "Sites" entry is relabeled and nested under the Sites group; its siblings moved in too
        assertTrue(html.contains(">Manage sites<"), "the Sites entry is relabeled 'Manage sites'");
        assertTrue(html.contains(">Templates<"), "'Site templates' is shortened to 'Templates' in the menu");
        assertTrue(html.contains(">Site master users<"), "'Site masters' is renamed 'Site master users'");
        // the Sites group precedes the Access group; App users lives under Access
        assertTrue(html.indexOf("/__app/site-templates") < html.indexOf("/__app/users"),
                "the Sites group appears before the Access group");
        // Settings is a dropdown too, holding Platform settings + About Runway
        assertTrue(html.contains("grp-settings"), "Settings is a dropdown group");
        assertTrue(html.contains("href=\"/__app/settings\"") && html.contains("href=\"/__app/about\""),
                "the Settings dropdown links to Platform settings and About Runway");
    }

    @Test
    void theMasterSeesBothPluginMenuLinks() throws Exception {
        String html = mvc.perform(get("/__app").session(sessionFor("admin")))
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("/__app/widgets\"") || html.contains("href=\"/__app/widgets\""),
                "the ungated plugin menu link is shown");
        assertTrue(html.contains(">Widgets<"), "its label renders");
        assertTrue(html.contains(">Gated<"), "the master passes the gated entry's permission");
        // A declared icon renders as the entry's Font Awesome class; an entry with no icon falls back to the
        // generic plugin glyph (proves the descriptor icon → MenuRegistry → topbar path, and the default).
        assertTrue(html.contains("fa-flask"), "the declared menu icon renders");
        assertTrue(html.contains("fa-puzzle-piece"), "an icon-less entry falls back to the generic plugin glyph");
    }

    @Test
    void anOperatorSeesOnlyTheUngatedLink() throws Exception {
        String html = mvc.perform(get("/__app").session(sessionFor("operator")))
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains(">Widgets<"), "the ungated link is visible to any operator");
        assertFalse(html.contains(">Gated<"), "the permission-gated link is hidden from a non-holder");
    }

    /**
     * Region targeting: a plugin's {@code region: settings} / {@code region: activity} menu entries render INSIDE
     * the matching topbar dropdown (not as a top-level item), while its region-less entry stays top-level. This
     * exercises the whole path — the loader, {@code MenuRegistry.menuFor(realm, region, …)}, {@code OperatorShellModel}
     * and the {@code app-topbar} {@code .th.xml} loops — so a broken descendant selector would fail here.
     */
    @Test
    void pluginRegionEntriesRenderInsideTheirGroups() throws Exception {
        String html = mvc.perform(get("/__app").session(sessionFor("admin")))
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("href=\"/__app/widgets/cfg\""), "the region:settings screen link is rendered");
        assertTrue(html.contains("href=\"/__app/widgets/act\""), "the region:activity screen link is rendered");
        // The Activity entry sits between the Activity group marker and the Settings group; the Settings entry
        // sits after the Settings group marker — i.e. each is nested inside its own dropdown, not top-level.
        assertTrue(html.indexOf("grp-activity") < html.indexOf("/__app/widgets/act")
                && html.indexOf("/__app/widgets/act") < html.indexOf("grp-settings"),
                "the region:activity entry renders inside the Activity dropdown");
        assertTrue(html.indexOf("grp-settings") < html.indexOf("/__app/widgets/cfg"),
                "the region:settings entry renders inside the Settings dropdown");
        // The region-less entry is still a top-level nav item (default region = menu).
        assertTrue(html.contains(">Widgets<"), "the region-less entry stays top-level");
    }
}
