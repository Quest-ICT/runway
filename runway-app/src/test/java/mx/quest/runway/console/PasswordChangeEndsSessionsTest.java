package mx.quest.runway.console;

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
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.recovery.RecoveryTokenStore;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A password reset or change <b>ends the account's other open sessions</b>, in both realms, on every path that
 * sets a password: an administrator's reset (app users, site users, tenant superadmins), the self-service Account
 * screen, and the recovery link. The session that changed its own password stays signed in. Each drop is
 * realm-scoped: a sign-in to the other realm in the same browser session survives.
 *
 * <p>Each test uses its own accounts, so the order the tests run in never matters.
 */
@SpringBootTest
class PasswordChangeEndsSessionsTest {

    private static final String PW = "Session-Ending-Secret-9";
    private static final String NEW_PW = "A-brand-new-Secret-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-pw-sessions");
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
        Path appFile = external.resolve("app-users.yml");
        users.save(appFile, new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        for (String op : new String[] {"op1", "op2", "op3", "op4", "op5"}) {
            users.save(appFile, new User(op + "-id", op, hash, op, "", true, false, Realm.APPLICATION,
                    Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        }
        // A wildcard app admin who resets their OWN password through the users screen.
        users.save(appFile, new User("boss-id", "boss", hash, "Boss", "", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_ADMIN), List.of()));
        Path acmeUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(acmeUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));
        for (String ed : new String[] {"ed1", "ed2", "ed3", "ed4", "ed5"}) {
            users.save(acmeUsers, new User(ed + "-id", ed, hash, ed, "", true, false, Realm.TENANT,
                    Set.of(Roles.OPERATOR), List.of()));
        }
        Path masters = external.resolve("site-users.yml");
        users.save(masters, new User("tm1-id", "tm1", hash, "TM1", "", true, true, Realm.TENANT,
                Set.of(Roles.SUPERADMIN), List.of("acme")));
        users.save(masters, new User("tm2-id", "tm2", hash, "TM2", "", true, true, Realm.TENANT,
                Set.of(Roles.SUPERADMIN), List.of("acme", "beta")));
        users.save(masters, new User("tm3-id", "tm3", hash, "TM3", "", true, true, Realm.TENANT,
                Set.of(Roles.SUPERADMIN), List.of("acme")));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private RecoveryTokenStore tokens;
    @Autowired
    private mx.quest.runway.log.SiteLogService siteLog;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private static RequestPostProcessor host(String host) {
        return request -> {
            request.setServerName(host);
            return request;
        };
    }

    private MockHttpSession appLogin(String username, String password) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(password))
                .andExpect(redirectedUrl("/__app")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession appLogin(String username) throws Exception {
        return appLogin(username, PW);
    }

    private MockHttpSession siteLogin(String username, String host) throws Exception {
        MvcResult result = mvc.perform(post("/login").with(host(host)).with(csrf())
                        .param("username", username).param("password", PW))
                .andExpect(redirectedUrl("/__admin")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession siteLogin(String username) throws Exception {
        return siteLogin(username, "localhost");
    }

    private void appSignedIn(MockHttpSession session) throws Exception {
        mvc.perform(get("/__app").session(session)).andExpect(status().isOk());
    }

    private void appSignedOut(MockHttpSession session) throws Exception {
        mvc.perform(get("/__app").session(session)).andExpect(redirectedUrl("/app/login"));
    }

    private void siteSignedIn(MockHttpSession session, String host) throws Exception {
        mvc.perform(get("/__admin").with(host(host)).session(session)).andExpect(status().isOk());
    }

    private void siteSignedOut(MockHttpSession session, String host) throws Exception {
        mvc.perform(get("/__admin").with(host(host)).session(session)).andExpect(redirectedUrl("/login"));
    }

    // --- application realm --------------------------------------------------------------------------------

    @Test
    void anAdministratorsResetEndsTheTargetsSessionsButNotTheAdministrators() throws Exception {
        MockHttpSession target = appLogin("op1");
        MockHttpSession admin = appLogin("root");
        mvc.perform(post("/__app/users/reset-password").session(admin).with(csrf())
                        .param("principalId", "op1-id").param("password", NEW_PW))
                .andExpect(flash().attribute("flash", "Reset the password for 'op1'."));

        appSignedOut(target);
        appSignedIn(admin);
        appLogin("op1", NEW_PW); // the new password works
    }

    @Test
    void anAdministratorResettingTheirOwnPasswordStaysSignedInWhileTheirOtherSessionEnds() throws Exception {
        MockHttpSession other = appLogin("boss");
        MockHttpSession acting = appLogin("boss");
        mvc.perform(post("/__app/users/reset-password").session(acting).with(csrf())
                        .param("principalId", "boss-id").param("password", NEW_PW))
                .andExpect(status().is3xxRedirection());
        appSignedIn(acting);
        appSignedOut(other);
    }

    @Test
    void aSelfServiceChangeKeepsThisSessionAndEndsTheOthers() throws Exception {
        MockHttpSession laptop = appLogin("op2");
        MockHttpSession phone = appLogin("op2");
        mvc.perform(post("/__app/account").session(laptop).with(csrf()).param("currentPassword", PW)
                        .param("newPassword", NEW_PW).param("confirmPassword", NEW_PW))
                .andExpect(flash().attribute("flash",
                        "Your password has been changed. Any other session signed in as you has been signed out."));

        appSignedIn(laptop);
        mvc.perform(get("/__app/account").session(laptop)).andExpect(status().isOk()); // and keeps working
        appSignedOut(phone);
    }

    @Test
    void aRefusedSelfServiceChangeEndsNothing() throws Exception {
        MockHttpSession laptop = appLogin("op3");
        MockHttpSession phone = appLogin("op3");
        mvc.perform(post("/__app/account").session(laptop).with(csrf()).param("currentPassword", "wrong")
                        .param("newPassword", NEW_PW).param("confirmPassword", NEW_PW))
                .andExpect(flash().attribute("error", "The current password is incorrect."));
        appSignedIn(laptop);
        appSignedIn(phone);
    }

    @Test
    void aRecoveryLinkEndsEverySessionOfTheAccount() throws Exception {
        MockHttpSession open = appLogin("op4");
        String token = tokens.issue(Realm.APPLICATION, null, "op4-id", "op4", Duration.ofHours(1),
                System.currentTimeMillis());
        mvc.perform(post("/app/reset").with(csrf()).param("token", token).param("password", NEW_PW)
                        .param("confirm", NEW_PW))
                .andExpect(redirectedUrl("/app/login?reset"));
        appSignedOut(open);
        appLogin("op4", NEW_PW);
    }

    @Test
    void anAppPasswordResetLeavesTheTenantSignInOfTheSameBrowserIntact() throws Exception {
        MockHttpSession shared = siteLogin("ed4");
        mvc.perform(post("/app/login").session(shared).with(csrf()).param("username", "op5").param("password", PW))
                .andExpect(redirectedUrl("/__app"));
        appSignedIn(shared);

        mvc.perform(post("/__app/users/reset-password").session(appLogin("root")).with(csrf())
                        .param("principalId", "op5-id").param("password", NEW_PW))
                .andExpect(status().is3xxRedirection());
        appSignedOut(shared);
        siteSignedIn(shared, "localhost"); // the tenant realm is untouched
    }

    // --- tenant realm -------------------------------------------------------------------------------------

    @Test
    void aSiteAdministratorsResetEndsTheUsersSessionsButNotTheAppSignInBesideIt() throws Exception {
        MockHttpSession shared = siteLogin("ed1");
        mvc.perform(post("/app/login").session(shared).with(csrf()).param("username", "root").param("password", PW))
                .andExpect(redirectedUrl("/__app"));
        MockHttpSession appOnly = appLogin("root");

        MockHttpSession admin = siteLogin("admin");
        mvc.perform(post("/__admin/users/reset-password").session(admin).with(csrf())
                        .param("principalId", "ed1-id").param("password", NEW_PW))
                .andExpect(flash().attribute("flash", "Reset the password for 'ed1'."));

        siteSignedOut(shared, "localhost");
        appSignedIn(shared); // the application realm is untouched
        siteSignedIn(admin, "localhost");
        appSignedIn(appOnly);
    }

    @Test
    void aSiteUsersSelfServiceChangeKeepsThisSessionAndEndsTheOthers() throws Exception {
        MockHttpSession laptop = siteLogin("ed2");
        MockHttpSession phone = siteLogin("ed2");
        mvc.perform(post("/__admin/account").session(laptop).with(csrf()).param("currentPassword", PW)
                        .param("newPassword", NEW_PW).param("confirmPassword", NEW_PW))
                .andExpect(status().is3xxRedirection());
        siteSignedIn(laptop, "localhost");
        siteSignedOut(phone, "localhost");
    }

    @Test
    void aSiteUsersSelfServiceChangeIsJournaledInTheSiteAuditLogAndARefusedOneIsNot() throws Exception {
        MockHttpSession laptop = siteLogin("ed5");
        mvc.perform(post("/__admin/account").session(laptop).with(csrf()).param("currentPassword", "wrong")
                        .param("newPassword", NEW_PW).param("confirmPassword", NEW_PW))
                .andExpect(flash().attribute("error", "The current password is incorrect."));
        mvc.perform(post("/__admin/account").session(laptop).with(csrf()).param("currentPassword", PW)
                        .param("newPassword", NEW_PW).param("confirmPassword", NEW_PW))
                .andExpect(status().is3xxRedirection());
        // The site log writer is asynchronous: wait (bounded) for the line to land.
        java.util.List<String> lines = java.util.List.of();
        for (int i = 0; i < 100; i++) {
            lines = siteLog.tail("acme", mx.quest.runway.log.LogKind.AUDIT, 500).stream()
                    .filter(l -> l.contains("\ted5\t") && l.contains("account.password-changed")).toList();
            if (!lines.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, lines.size(),
                "exactly one entry: the accepted change, not the refused one");
    }

    @Test
    void aTenantSuperadminsSelfChangeKeepsThisSitesSessionAndEndsTheirOtherSignIns() throws Exception {
        MockHttpSession acmeLaptop = siteLogin("tm2");
        MockHttpSession acmePhone = siteLogin("tm2");
        MockHttpSession beta = siteLogin("tm2", "beta.test");
        mvc.perform(post("/__admin/account").session(acmeLaptop).with(csrf()).param("currentPassword", PW)
                        .param("newPassword", NEW_PW).param("confirmPassword", NEW_PW))
                .andExpect(status().is3xxRedirection());
        siteSignedIn(acmeLaptop, "localhost");
        siteSignedOut(acmePhone, "localhost");
        siteSignedOut(beta, "beta.test"); // a sign-in to another site is another session of the account
    }

    @Test
    void anAppResetOfATenantSuperadminEndsItsSessionsButNotTheResettingOperators() throws Exception {
        MockHttpSession shared = siteLogin("tm1");
        mvc.perform(post("/app/login").session(shared).with(csrf()).param("username", "root").param("password", PW))
                .andExpect(redirectedUrl("/__app"));
        mvc.perform(post("/__app/masters/reset-password").session(shared).with(csrf())
                        .param("principalId", "tm1-id").param("password", NEW_PW))
                .andExpect(flash().attribute("flash", "Password reset."));
        siteSignedOut(shared, "localhost");
        appSignedIn(shared); // the operator who reset it, in the same browser, stays signed in
    }

    @Test
    void aTenantRecoveryLinkEndsTheSuperadminsSessions() throws Exception {
        MockHttpSession open = siteLogin("tm3");
        String token = tokens.issue(Realm.TENANT, "acme", "tm3-id", "tm3", Duration.ofHours(1),
                System.currentTimeMillis());
        mvc.perform(post("/reset").with(csrf()).param("token", token).param("password", NEW_PW)
                        .param("confirm", NEW_PW))
                .andExpect(redirectedUrl("/login?reset"));
        siteSignedOut(open, "localhost");
    }

    @Test
    void aTenantRecoveryLinkEndsASiteUsersSessions() throws Exception {
        MockHttpSession open = siteLogin("ed3");
        String token = tokens.issue(Realm.TENANT, "acme", "ed3-id", "ed3", Duration.ofHours(1),
                System.currentTimeMillis());
        mvc.perform(post("/reset").with(csrf()).param("token", token).param("password", NEW_PW)
                        .param("confirm", NEW_PW))
                .andExpect(redirectedUrl("/login?reset"));
        siteSignedOut(open, "localhost");
    }
}
