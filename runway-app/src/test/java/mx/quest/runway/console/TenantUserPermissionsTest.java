package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
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
import mx.quest.runway.security.SiteUserStore;
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
 * {@code MANAGE_USERS} and {@code DELETE_USERS} on the site console's Users screen ({@code /__admin/users}): the
 * verbs each permission opens (and the {@code 403} without it), the target bound that keeps a delegated manager
 * away from accounts holding more than they do, the create-time limits (no {@code ADMIN} tier, no custom roles),
 * the affordances the screen renders for each, and tenant scope. Administrators keep everything — covered in
 * {@link TenantUserEditSuspendTest} and the other Users-screen tests.
 */
@SpringBootTest
class TenantUserPermissionsTest {

    private static final String PW = "Delegated-Users-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-delegated-users");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        sites.save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE));
        sites.save(new Site("beta", "Beta", List.of("beta.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE));
        UserStore users = new UserStore(yaml);
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        Path acme = layout.siteDir("acme").resolve("users.yml");
        users.save(acme, tenantUser("acme-admin", "admin", hash, Roles.ADMIN));
        users.save(acme, tenantUser("acme-admin2", "admin2", hash, Roles.ADMIN));
        users.save(acme, tenantUser("acme-op", "op", hash, Roles.OPERATOR));          // no grant at all
        users.save(acme, tenantUser("acme-mgr", "mgr", hash, Roles.OPERATOR));        // MANAGE_USERS
        users.save(acme, tenantUser("acme-del", "del", hash, Roles.OPERATOR));        // DELETE_USERS
        users.save(acme, tenantUser("acme-power", "power", hash, Roles.OPERATOR));    // EDIT_PAGES: more than mgr
        for (String t : new String[] {"t1", "t2", "t3", "t4", "t5", "t6"}) {
            users.save(acme, tenantUser("acme-" + t, t, hash, Roles.OPERATOR));
        }
        users.save(layout.siteDir("beta").resolve("users.yml"), tenantUser("beta-op", "betaop", hash, Roles.OPERATOR));
        new RoleStore(layout, yaml).saveSiteRoles("acme",
                List.of(new Role("helper", Realm.TENANT, Set.of(), Set.of())));
        new GrantStore(layout, yaml).saveSiteGrants("acme", GrantFile.empty()
                .withUserPermission("acme-mgr", "MANAGE_USERS")
                .withUserPermission("acme-del", "DELETE_USERS")
                .withUserPermission("acme-power", "EDIT_PAGES"));
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true, true,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        // The site's superadmin (a global identity assigned to acme), and one assigned to beta only.
        users.save(external.resolve("site-users.yml"), new User("owner-id", "Owner", hash, "Owner", "", true, true,
                Realm.TENANT, Set.of(Roles.SUPERADMIN), List.of("acme")));
        users.save(external.resolve("site-users.yml"), new User("elsewhere-id", "elsewhere", hash, "Elsewhere", "",
                true, true, Realm.TENANT, Set.of(Roles.SUPERADMIN), List.of("beta")));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    private static User tenantUser(String id, String username, String hash, String tier) {
        return new User(id, username, hash, username, "", true, false, Realm.TENANT, Set.of(tier), List.of());
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteUserStore siteUsers;
    @Autowired
    private SiteResolver siteResolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/login").user(username).password(PW))
                .andExpect(redirectedUrl("/__admin")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private User acme(String id) {
        return siteUsers.findByPrincipalId("acme", id).orElseThrow();
    }

    private boolean exists(String username) {
        return siteUsers.findByUsername("acme", username).isPresent();
    }

    private String screen(MockHttpSession session) throws Exception {
        return mvc.perform(get("/__admin/users").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static final String BEYOND = "This account holds more than you do. Only the site's administrators can "
            + "change it.";

    // --- without either permission --------------------------------------------------------------------

    @Test
    void anOperatorWithoutEitherPermissionIsRefusedEveryUsersRoute() throws Exception {
        MockHttpSession op = login("op");
        mvc.perform(get("/__admin/users").session(op)).andExpect(status().isForbidden());
        mvc.perform(post("/__admin/users/create").session(op).with(csrf()).param("username", "sneak")
                .param("password", "Sneak-Secret-123").param("tier", Roles.OPERATOR)).andExpect(status().isForbidden());
        mvc.perform(post("/__admin/users/acme-t1/edit").session(op).with(csrf()).param("displayName", "X"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/__admin/users/acme-t1/suspend").session(op).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/__admin/users/acme-t1/resume").session(op).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/__admin/users/reset-password").session(op).with(csrf()).param("principalId", "acme-t1")
                .param("password", "Taken-Over-123")).andExpect(status().isForbidden());
        mvc.perform(post("/__admin/users/acme-t1/delete").session(op).with(csrf())).andExpect(status().isForbidden());
        assertFalse(exists("sneak"));
        assertEquals("t1", acme("acme-t1").displayName());
        assertTrue(acme("acme-t1").enabled());
    }

    // --- MANAGE_USERS -----------------------------------------------------------------------------------

    @Test
    void manageUsersCreatesAnOperatorButNeverAnAdministratorNorCustomRoles() throws Exception {
        MockHttpSession mgr = login("mgr");
        mvc.perform(post("/__admin/users/create").session(mgr).with(csrf()).param("username", "newbie")
                        .param("password", "Newbie-Secret-123").param("tier", Roles.OPERATOR))
                .andExpect(flash().attribute("flash", "Created user 'newbie'."));
        assertEquals(Set.of(Roles.OPERATOR), siteUsers.findByUsername("acme", "newbie").orElseThrow().roles());

        mvc.perform(post("/__admin/users/create").session(mgr).with(csrf()).param("username", "boss")
                        .param("password", "Boss-Secret-1234").param("tier", Roles.ADMIN))
                .andExpect(flash().attribute("error", "You are not allowed to grant or remove that access tier."));
        assertFalse(exists("boss"), "a delegated manager cannot mint an administrator");

        mvc.perform(post("/__admin/users/create").session(mgr).with(csrf()).param("username", "helped")
                        .param("password", "Helped-Secret-123").param("tier", Roles.OPERATOR).param("roles", "helper"))
                .andExpect(flash().attribute("error", "Only the site's administrators can assign custom roles."));
        assertFalse(exists("helped"), "a refused role assignment creates nothing");
    }

    /**
     * A delegated {@code MANAGE_USERS} holder created a site user named like the site's
     * superadmin; sign-in then resolved the site user first, locking the superadmin out and letting the delegate
     * sign in under the superadmin's name. The name must be refused in every case/blank variant, and an existing
     * site user's name too.
     */
    @Test
    void manageUsersCannotTakeTheSuperadminsNameOrAnExistingUsersName() throws Exception {
        MockHttpSession mgr = login("mgr");
        for (String squat : new String[] {"Owner", "owner", "OWNER", "  owner  "}) {
            mvc.perform(post("/__admin/users/create").session(mgr).with(csrf()).param("username", squat)
                            .param("password", "Shadow-Secret-123").param("tier", Roles.OPERATOR))
                    .andExpect(flash().attribute("error",
                            "The username \"" + squat.trim() + "\" is already in use. Choose another one."));
        }
        assertFalse(exists("owner"), "no site user shadows the superadmin");
        mvc.perform(post("/__admin/users/create").session(mgr).with(csrf()).param("username", "T1")
                        .param("password", "Shadow-Secret-123").param("tier", Roles.OPERATOR))
                .andExpect(flash().attribute("error", "The username \"T1\" is already in use. Choose another one."));
        assertEquals(1, siteUsers.list("acme").stream().filter(u -> u.username().equalsIgnoreCase("t1")).count());

        // The superadmin still signs in with its own password, as itself.
        mvc.perform(formLogin("/login").user("owner").password(PW)).andExpect(redirectedUrl("/__admin"))
                .andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers
                        .authenticated().withUsername("Owner"));

        // A superadmin of ANOTHER site does not sign in here, so its name is free on this site.
        mvc.perform(post("/__admin/users/create").session(mgr).with(csrf()).param("username", "elsewhere")
                        .param("password", "Elsewhere-Secret-123").param("tier", Roles.OPERATOR))
                .andExpect(flash().attribute("flash", "Created user 'elsewhere'."));
    }

    @Test
    void manageUsersEditsSuspendsResumesAndResetsAnOperatorWithinItsAuthority() throws Exception {
        MockHttpSession mgr = login("mgr");
        mvc.perform(post("/__admin/users/acme-t2/edit").session(mgr).with(csrf()).param("displayName", "Tee Two")
                .param("email", "t2@example.com")).andExpect(flash().attributeExists("flash"));
        assertEquals("Tee Two", acme("acme-t2").displayName());

        mvc.perform(post("/__admin/users/acme-t2/suspend").session(mgr).with(csrf()))
                .andExpect(flash().attributeExists("flash"));
        assertFalse(acme("acme-t2").enabled());
        mvc.perform(post("/__admin/users/acme-t2/resume").session(mgr).with(csrf()))
                .andExpect(flash().attributeExists("flash"));
        assertTrue(acme("acme-t2").enabled());

        String before = acme("acme-t2").passwordHash();
        mvc.perform(post("/__admin/users/reset-password").session(mgr).with(csrf()).param("principalId", "acme-t2")
                .param("password", "Fresh-Secret-1234")).andExpect(flash().attributeExists("flash"));
        assertFalse(before.equals(acme("acme-t2").passwordHash()), "the password was reset");
    }

    @Test
    void manageUsersCannotPromoteAnOperatorToAdministrator() throws Exception {
        mvc.perform(post("/__admin/users/acme-t3/edit").session(login("mgr")).with(csrf())
                        .param("displayName", "t3").param("tier", Roles.ADMIN))
                .andExpect(flash().attribute("error", "You are not allowed to grant or remove that access tier."));
        assertEquals(Set.of(Roles.OPERATOR), acme("acme-t3").roles());
    }

    @Test
    void manageUsersNeverReachesAnAdministrator() throws Exception {
        MockHttpSession mgr = login("mgr");
        String hash = acme("acme-admin2").passwordHash();
        mvc.perform(post("/__admin/users/reset-password").session(mgr).with(csrf()).param("principalId", "acme-admin2")
                .param("password", "Taken-Over-1234")).andExpect(flash().attribute("error", BEYOND));
        assertEquals(hash, acme("acme-admin2").passwordHash(), "no takeover of an administrator's account");
        mvc.perform(post("/__admin/users/acme-admin2/suspend").session(mgr).with(csrf()))
                .andExpect(flash().attribute("error", BEYOND));
        assertTrue(acme("acme-admin2").enabled());
        mvc.perform(post("/__admin/users/acme-admin2/edit").session(mgr).with(csrf()).param("displayName", "Pwned")
                .param("tier", Roles.OPERATOR)).andExpect(flash().attribute("error", BEYOND));
        assertEquals("admin2", acme("acme-admin2").displayName());
        assertEquals(Set.of(Roles.ADMIN), acme("acme-admin2").roles(), "no demotion either");
    }

    @Test
    void manageUsersNeverReachesAUserHoldingAPermissionTheManagerLacks() throws Exception {
        MockHttpSession mgr = login("mgr");
        String hash = acme("acme-power").passwordHash();
        mvc.perform(post("/__admin/users/reset-password").session(mgr).with(csrf()).param("principalId", "acme-power")
                .param("password", "Taken-Over-1234")).andExpect(flash().attribute("error", BEYOND));
        assertEquals(hash, acme("acme-power").passwordHash(), "EDIT_PAGES cannot be reached through MANAGE_USERS");
        mvc.perform(post("/__admin/users/acme-power/suspend").session(mgr).with(csrf()))
                .andExpect(flash().attribute("error", BEYOND));
        assertTrue(acme("acme-power").enabled());
    }

    @Test
    void manageUsersAloneDoesNotDelete() throws Exception {
        mvc.perform(post("/__admin/users/acme-t4/delete").session(login("mgr")).with(csrf()))
                .andExpect(status().isForbidden());
        assertTrue(exists("t4"));
    }

    @Test
    void theScreenOffersAManagerOnlyWhatTheyMayDo() throws Exception {
        String html = screen(login("mgr"));
        assertTrue(html.contains("action=\"/__admin/users/create\""), "the create form is offered");
        assertFalse(html.contains("value=\"ADMIN\""), "no Administrator tier anywhere (create form or edit modal)");
        assertFalse(html.contains("tu-roles-group"), "no custom-role picker");
        assertTrue(html.contains("/__admin/users/acme-t5/edit") && html.contains("/__admin/users/acme-t5/suspend"),
                "an operator within the manager's authority can be edited and suspended");
        assertFalse(html.contains("/__admin/users/acme-admin2/edit")
                        || html.contains("/__admin/users/acme-admin2/suspend"),
                "an administrator's row offers nothing");
        assertFalse(html.contains("/__admin/users/acme-power/edit"), "nor does a more powerful operator's row");
        assertFalse(html.contains("value=\"acme-admin2\"") || html.contains("value=\"acme-power\""),
                "the reset-password picker lists only reachable users");
        assertFalse(html.contains("/delete\""), "no delete without DELETE_USERS");
    }

    // --- DELETE_USERS -----------------------------------------------------------------------------------

    @Test
    void deleteUsersDeletesAnOperatorButNotAnAdministratorNorAMorePowerfulUser() throws Exception {
        MockHttpSession del = login("del");
        mvc.perform(post("/__admin/users/acme-t6/delete").session(del).with(csrf()))
                .andExpect(flash().attribute("flash", "Deleted the user."));
        assertFalse(exists("t6"));
        mvc.perform(post("/__admin/users/acme-admin2/delete").session(del).with(csrf()))
                .andExpect(flash().attribute("error", BEYOND));
        assertTrue(exists("admin2"));
        mvc.perform(post("/__admin/users/acme-mgr/delete").session(del).with(csrf()))
                .andExpect(flash().attribute("error", BEYOND));
        assertTrue(exists("mgr"), "MANAGE_USERS is more than DELETE_USERS holds");
    }

    @Test
    void deleteUsersAloneShowsTheListWithDeleteOnlyAndManagesNothing() throws Exception {
        MockHttpSession del = login("del");
        String html = screen(del);
        assertFalse(html.contains("action=\"/__admin/users/create\""), "no create form without MANAGE_USERS");
        assertFalse(html.contains("/__admin/users/acme-t1/edit"), "no edit without MANAGE_USERS");
        assertTrue(html.contains("/__admin/users/acme-t1/delete"), "delete is offered on a reachable row");
        assertFalse(html.contains("/__admin/users/acme-admin2/delete"), "but not on an administrator's");
        mvc.perform(post("/__admin/users/create").session(del).with(csrf()).param("username", "nope")
                .param("password", "Nope-Secret-1234").param("tier", Roles.OPERATOR)).andExpect(status().isForbidden());
        mvc.perform(post("/__admin/users/acme-t1/suspend").session(del).with(csrf())).andExpect(status().isForbidden());
        assertFalse(exists("nope"));
        assertTrue(acme("acme-t1").enabled());
    }

    // --- tenant scope -----------------------------------------------------------------------------------

    @Test
    void aDelegatedManagerCannotReachAnotherSitesUser() throws Exception {
        MockHttpSession mgr = login("mgr");
        mvc.perform(post("/__admin/users/beta-op/suspend").session(mgr).with(csrf()))
                .andExpect(flash().attribute("error", "No such user."));
        mvc.perform(post("/__admin/users/reset-password").session(mgr).with(csrf()).param("principalId", "beta-op")
                .param("password", "Cross-Site-1234")).andExpect(flash().attributeExists("error"));
        assertTrue(siteUsers.findByPrincipalId("beta", "beta-op").orElseThrow().enabled());
    }

    // --- administrators are unchanged -------------------------------------------------------------------

    @Test
    void anAdministratorStillSeesAndReachesEveryone() throws Exception {
        String html = screen(login("admin"));
        assertTrue(html.contains("value=\"ADMIN\""), "the Administrator tier is offered to an administrator");
        assertTrue(html.contains("tu-f-roles"), "and the custom-role picker");
        assertTrue(html.contains("/__admin/users/acme-admin2/edit") && html.contains("/__admin/users/acme-power/delete"),
                "every row is reachable");
    }
}
