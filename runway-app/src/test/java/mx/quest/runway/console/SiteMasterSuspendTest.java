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
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Tenant superadmins can be <b>suspended and resumed</b> from the application console's Site masters screen: a
 * suspended superadmin cannot sign in to any of its sites and its open sessions end on their next request;
 * password and assignments survive, so Resume restores everything. Same gate as the rest of the screen's
 * mutations (master / wildcard admin), journaled, CSRF-protected.
 */
@SpringBootTest
class SiteMasterSuspendTest {

    private static final String PW = "Master-Suspend-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-master-suspend");
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
        users.save(appFile, new User("wild-id", "wild", hash, "Wild", "", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_ADMIN), List.of()));
        users.save(appFile, new User("op-id", "op", hash, "Op", "", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        Path masters = external.resolve("site-users.yml");
        users.save(masters, new User("tm-id", "tm", hash, "TM", "", true, true, Realm.TENANT,
                Set.of(Roles.SUPERADMIN), List.of("acme", "beta")));
        users.save(masters, new User("tm2-id", "tm2", hash, "TM2", "", true, true, Realm.TENANT,
                Set.of(Roles.SUPERADMIN), List.of("acme")));
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
    private TenantMasterStore masters;
    @Autowired
    private AppActivityLog activity;
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

    private MockHttpSession appLogin(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PW))
                .andExpect(redirectedUrl("/__app")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession siteLogin(String username, String host) throws Exception {
        MvcResult result = mvc.perform(post("/login").with(host(host)).with(csrf())
                        .param("username", username).param("password", PW))
                .andExpect(redirectedUrl("/__admin")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private User master(String id) {
        return masters.findByPrincipalId(id).orElseThrow();
    }

    private boolean journaled(String action, String detail) {
        return activity.tail(100).stream().anyMatch(l -> l.contains("\t" + action + "\t") && l.contains(detail));
    }

    @Test
    void aSuspendedSuperadminIsSignedOutOfEverySiteCannotSignInAndCanBeResumed() throws Exception {
        MockHttpSession acme = siteLogin("tm", "localhost");
        MockHttpSession beta = siteLogin("tm", "beta.test");
        MockHttpSession root = appLogin("root");

        mvc.perform(post("/__app/masters/tm-id/suspend").session(root).with(csrf()))
                .andExpect(redirectedUrl("/__app/masters"))
                .andExpect(flash().attribute("flash", "Suspended tenant superadmin \"tm\"."));
        User suspended = master("tm-id");
        assertFalse(suspended.enabled(), "the record is disabled");
        assertEquals(List.of("acme", "beta"), suspended.sites(), "assignments kept");
        assertTrue(journaled("master.suspended", "tm"), "journaled with the username");

        mvc.perform(get("/__admin").session(acme)).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/__admin").with(host("beta.test")).session(beta)).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/login").with(csrf()).param("username", "tm").param("password", PW))
                .andExpect(redirectedUrl("/login?error"));

        String html = mvc.perform(get("/__app/masters").session(root)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("action=\"/__app/masters/tm-id/resume\""), "a suspended superadmin offers Resume");
        assertFalse(html.contains("action=\"/__app/masters/tm-id/suspend\""), "and no second Suspend");
        assertTrue(html.contains("action=\"/__app/masters/tm3-id/suspend\""), "an active one offers Suspend");
        assertTrue(html.contains(">suspended</span>"), "the row carries the suspended badge");

        mvc.perform(post("/__app/masters/tm-id/resume").session(root).with(csrf()))
                .andExpect(flash().attribute("flash", "Resumed tenant superadmin \"tm\"."));
        assertTrue(master("tm-id").enabled());
        assertTrue(journaled("master.resumed", "tm"));
        siteLogin("tm", "localhost"); // signs in again with the unchanged password
    }

    @Test
    void aWildcardAdminMaySuspendASuperadmin() throws Exception {
        mvc.perform(post("/__app/masters/tm2-id/suspend").session(appLogin("wild")).with(csrf()))
                .andExpect(flash().attribute("flash", "Suspended tenant superadmin \"tm2\"."));
        assertFalse(master("tm2-id").enabled());
    }

    @Test
    void anOperatorIsForbidden() throws Exception {
        MockHttpSession op = appLogin("op");
        mvc.perform(post("/__app/masters/tm3-id/suspend").session(op).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/__app/masters/tm3-id/resume").session(op).with(csrf())).andExpect(status().isForbidden());
        assertTrue(master("tm3-id").enabled());
    }

    @Test
    void aTenantSessionCannotReachTheAppRoute() throws Exception {
        mvc.perform(post("/__app/masters/tm3-id/suspend").session(siteLogin("tm3", "localhost")).with(csrf()))
                .andExpect(redirectedUrl("/app/login"));
        assertTrue(master("tm3-id").enabled(), "a tenant sign-in is not an application sign-in");
    }

    @Test
    void withoutCsrfNothingChanges() throws Exception {
        mvc.perform(post("/__app/masters/tm3-id/suspend").session(appLogin("root"))).andExpect(status().isForbidden());
        assertTrue(master("tm3-id").enabled());
    }

    @Test
    void anUnknownSuperadminIsAFlashError() throws Exception {
        mvc.perform(post("/__app/masters/nobody/suspend").session(appLogin("root")).with(csrf()))
                .andExpect(flash().attribute("error", "No such user."));
    }

    @Test
    void anAppUserIdIsNotASuperadmin() throws Exception {
        mvc.perform(post("/__app/masters/op-id/suspend").session(appLogin("root")).with(csrf()))
                .andExpect(flash().attribute("error", "No such user."));
    }
}
