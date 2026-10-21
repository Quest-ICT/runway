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
import mx.quest.runway.log.LogKind;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.SiteUserStore;
import mx.quest.runway.security.TenantMasterStore;
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
 * Tenant-user <b>edit</b> (profile + tier) and <b>suspend / resume</b> on {@code /__admin/users}: tenant scope
 * (another site's user and the tenant superadmin are out of reach), the self guard, an operator's 403, sign-in
 * refused while suspended, an open session dropped on its next request — and the drop touching only the tenant
 * realm, so an application sign-in in the same browser session survives.
 */
@SpringBootTest
class TenantUserEditSuspendTest {

    private static final String PW = "Tenant-Edit-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-tenantuser-edit");
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
        Path acmeUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(acmeUsers, new User("acme-admin", "admin", hash, "Acme Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));
        for (String ed : new String[] {"ed1", "ed2", "ed3", "ed4", "ed5"}) {
            users.save(acmeUsers, new User(ed, ed, hash, ed, "", true, false, Realm.TENANT,
                    Set.of(Roles.OPERATOR), List.of()));
        }
        users.save(layout.siteDir("beta").resolve("users.yml"), new User("beta-op", "betaop", hash, "Beta Op", "",
                true, false, Realm.TENANT, Set.of(Roles.OPERATOR), List.of()));
        users.save(external.resolve("site-users.yml"), new User("acme-boss", "boss", hash, "Boss", "", true, true,
                Realm.TENANT, Set.of(Roles.SUPERADMIN), List.of("acme")));
        users.save(external.resolve("site-users.yml"), new User("acme-boss2", "boss2", hash, "Boss 2", "", true,
                true, Realm.TENANT, Set.of(Roles.SUPERADMIN), List.of("acme", "beta")));
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true, true,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteUserStore siteUsers;
    @Autowired
    private TenantMasterStore masters;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private SiteLogService siteLog;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // The SiteContextFilter binds the site BEFORE the tenant chain (tenant logins are scoped to it).
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

    private boolean audited(String action, String detail) throws InterruptedException {
        for (int i = 0; i < 50; i++) { // the site log is written asynchronously
            if (siteLog.tail("acme", LogKind.AUDIT, 100).stream()
                    .anyMatch(l -> l.contains(action) && l.contains(detail))) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    @Test
    void theAdminEditsAProfileAndPromotesAUser() throws Exception {
        mvc.perform(post("/__admin/users/ed1/edit").session(login("admin")).with(csrf())
                        .param("displayName", "Editor One").param("email", "ed1@example.com").param("tier", "ADMIN"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flash", "Updated user \"ed1\"."));
        User edited = acme("ed1");
        assertEquals("Editor One", edited.displayName());
        assertEquals("ed1@example.com", edited.recoveryEmail());
        assertEquals(Set.of(Roles.ADMIN), edited.roles(), "OPERATOR replaced by ADMIN");
        assertTrue(audited("user.updated", "ed1"), "the edit is in the site's audit log");
    }

    @Test
    void aSuspendedUserIsSignedOutCannotSignInAndCanBeResumed() throws Exception {
        MockHttpSession ed2 = login("ed2");
        mvc.perform(get("/__admin").session(ed2)).andExpect(status().isOk());

        MockHttpSession admin = login("admin");
        mvc.perform(post("/__admin/users/ed2/suspend").session(admin).with(csrf()))
                .andExpect(flash().attribute("flash", "Suspended user \"ed2\"."));
        assertFalse(acme("ed2").enabled());
        assertTrue(audited("user.suspended", "ed2"));

        mvc.perform(get("/__admin").session(ed2)).andExpect(redirectedUrl("/login"));
        mvc.perform(formLogin("/login").user("ed2").password(PW)).andExpect(redirectedUrl("/login?error"));

        mvc.perform(post("/__admin/users/ed2/resume").session(admin).with(csrf()))
                .andExpect(flash().attribute("flash", "Resumed user \"ed2\"."));
        assertTrue(acme("ed2").enabled());
        login("ed2"); // signs in again
    }

    @Test
    void droppingATenantSignInLeavesTheApplicationSignInOfTheSameSessionIntact() throws Exception {
        MockHttpSession shared = login("ed3");
        // Sign the same browser session into the application realm too.
        mvc.perform(post("/app/login").session(shared).with(csrf()).param("username", "root").param("password", PW))
                .andExpect(redirectedUrl("/__app"));
        mvc.perform(get("/__app").session(shared)).andExpect(status().isOk());

        mvc.perform(post("/__admin/users/ed3/suspend").session(login("admin")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/__admin").session(shared)).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/__app").session(shared)).andExpect(status().isOk()); // the other realm is untouched
    }

    @Test
    void anotherSitesUserAndTheTenantSuperadminAreOutOfReach() throws Exception {
        MockHttpSession admin = login("admin");
        mvc.perform(post("/__admin/users/beta-op/suspend").session(admin).with(csrf()))
                .andExpect(flash().attribute("error", "No such user."));
        assertTrue(siteUsers.findByPrincipalId("beta", "beta-op").orElseThrow().enabled(), "beta is untouched");
        mvc.perform(post("/__admin/users/acme-boss/suspend").session(admin).with(csrf()))
                .andExpect(flash().attribute("error", "No such user."));
        mvc.perform(post("/__admin/users/acme-boss/edit").session(admin).with(csrf()).param("tier", "OPERATOR"))
                .andExpect(flash().attribute("error", "No such user."));
        User boss = masters.findByPrincipalId("acme-boss").orElseThrow();
        assertTrue(boss.enabled());
        assertEquals(Set.of(Roles.SUPERADMIN), boss.roles());
    }

    @Test
    void anAdminCannotSuspendOrReTierThemselves() throws Exception {
        MockHttpSession admin = login("admin");
        mvc.perform(post("/__admin/users/acme-admin/suspend").session(admin).with(csrf()))
                .andExpect(flash().attribute("error", "You cannot suspend your own account."));
        mvc.perform(post("/__admin/users/acme-admin/edit").session(admin).with(csrf()).param("tier", "OPERATOR"))
                .andExpect(flash().attribute("error", "You cannot change your own access tier."));
        assertTrue(acme("acme-admin").enabled());
        assertEquals(Set.of(Roles.ADMIN), acme("acme-admin").roles());
    }

    @Test
    void anInvalidTierIsRefused() throws Exception {
        mvc.perform(post("/__admin/users/ed4/edit").session(login("admin")).with(csrf()).param("tier", "SUPERADMIN"))
                .andExpect(flash().attribute("error", "Invalid access tier."));
        assertEquals(Set.of(Roles.OPERATOR), acme("ed4").roles());
    }

    @Test
    void anOperatorIsForbiddenFromEveryNewAction() throws Exception {
        MockHttpSession ed5 = login("ed5");
        mvc.perform(post("/__admin/users/ed4/edit").session(ed5).with(csrf()).param("displayName", "pwned"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/__admin/users/ed4/suspend").session(ed5).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/__admin/users/ed4/resume").session(ed5).with(csrf())).andExpect(status().isForbidden());
        assertEquals("ed4", acme("ed4").displayName());
        assertTrue(acme("ed4").enabled());
    }

    @Test
    void theTenantSuperadminCanSuspendASubUser() throws Exception {
        mvc.perform(post("/__admin/users/ed5/suspend").session(login("boss")).with(csrf()))
                .andExpect(flash().attribute("flash", "Suspended user \"ed5\"."));
        assertFalse(acme("ed5").enabled());
        mvc.perform(post("/__admin/users/ed5/resume").session(login("boss")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertTrue(acme("ed5").enabled());
    }

    @Test
    void aTenantSuperadminUnassignedFromTheSiteLosesItsOpenSessionThere() throws Exception {
        MockHttpSession boss2 = login("boss2");
        mvc.perform(get("/__admin").session(boss2)).andExpect(status().isOk());
        User assigned = masters.findByPrincipalId("acme-boss2").orElseThrow();
        masters.save(new User(assigned.principalId(), assigned.username(), assigned.passwordHash(),
                assigned.displayName(), assigned.recoveryEmail(), true, true, Realm.TENANT, assigned.roles(),
                List.of("beta"))); // what an operator's unassign writes
        mvc.perform(get("/__admin").session(boss2)).andExpect(redirectedUrl("/login"));
    }

    @Test
    void theListRendersEditModalsAndHidesTheSelfSuspend() throws Exception {
        String html = mvc.perform(get("/__admin/users").session(login("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("action=\"/__admin/users/ed1/edit\""));
        assertTrue(html.contains("tu-edit-modal-0"));
        assertFalse(html.contains("action=\"/__admin/users/acme-admin/suspend\""), "no Suspend on your own row");
    }
}
