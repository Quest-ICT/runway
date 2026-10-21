package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import mx.quest.runway.security.AppUserStore;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Your own profile on the Account screen, in both realms: the display name saves freely, the recovery email only
 * with the current password (it is where a reset link goes), invalid input changes nothing, and only the signed-in
 * user's own record is touched. Each test uses its own account, so the order never matters.
 */
@SpringBootTest
class AccountProfileTest {

    private static final String PW = "Profile-Edit-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-profile");
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
        for (String op : new String[] {"op1", "op2", "op3"}) {
            users.save(appFile, new User(op + "-id", op, hash, op, op + "@old.test", true, false, Realm.APPLICATION,
                    Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        }
        users.save(layout.siteDir("acme").resolve("users.yml"), new User("ed1-id", "ed1", hash, "Ed", "", true,
                false, Realm.TENANT, Set.of(Roles.OPERATOR), List.of()));
        users.save(external.resolve("site-users.yml"), new User("tm1-id", "tm1", hash, "TM1", "tm@old.test", true,
                true, Realm.TENANT, Set.of(Roles.SUPERADMIN), List.of("acme")));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private AppUserStore appUsers;
    @Autowired
    private SiteUserStore siteUsers;
    @Autowired
    private TenantMasterStore masters;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private static RequestPostProcessor localhost() {
        return request -> {
            request.setServerName("localhost");
            return request;
        };
    }

    private MockHttpSession appLogin(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PW))
                .andExpect(redirectedUrl("/__app")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession siteLogin(String username) throws Exception {
        MvcResult result = mvc.perform(post("/login").with(localhost()).with(csrf())
                        .param("username", username).param("password", PW))
                .andExpect(redirectedUrl("/__admin")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private User app(String id) {
        return appUsers.findByPrincipalId(id).orElseThrow();
    }

    @Test
    void anOperatorRenamesThemselvesWithoutTheirPassword() throws Exception {
        MockHttpSession session = appLogin("op1");
        String html = mvc.perform(get("/__app/account").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("action=\"/__app/account/profile\""), "the profile form posts to its endpoint");
        assertTrue(html.contains("value=\"op1@old.test\""), "the recovery email is prefilled");
        mvc.perform(post("/__app/account/profile").session(session).with(csrf())
                        .param("displayName", "  Olga Operator ").param("recoveryEmail", "op1@old.test"))
                .andExpect(flash().attribute("flash", "Your profile has been saved."));
        assertEquals("Olga Operator", app("op1-id").displayName());
        assertEquals("op1@old.test", app("op1-id").recoveryEmail(), "the email is unchanged");
        mvc.perform(get("/__app").session(session)).andExpect(status().isOk()); // still signed in
    }

    @Test
    void changingTheRecoveryEmailNeedsTheCurrentPassword() throws Exception {
        MockHttpSession session = appLogin("op2");
        mvc.perform(post("/__app/account/profile").session(session).with(csrf())
                        .param("displayName", "op2").param("recoveryEmail", "attacker@evil.test"))
                .andExpect(flash().attribute("error", "Enter your current password to change your recovery email."));
        mvc.perform(post("/__app/account/profile").session(session).with(csrf())
                        .param("displayName", "op2").param("recoveryEmail", "attacker@evil.test")
                        .param("currentPassword", "wrong"))
                .andExpect(flash().attributeExists("error"));
        assertEquals("op2@old.test", app("op2-id").recoveryEmail(), "unchanged without the right password");
        mvc.perform(post("/__app/account/profile").session(session).with(csrf())
                        .param("displayName", "op2").param("recoveryEmail", "op2@new.test")
                        .param("currentPassword", PW))
                .andExpect(flash().attribute("flash", "Your profile has been saved."));
        assertEquals("op2@new.test", app("op2-id").recoveryEmail());
    }

    @Test
    void anInvalidEmailOrOverlongNameChangesNothing() throws Exception {
        MockHttpSession session = appLogin("op3");
        mvc.perform(post("/__app/account/profile").session(session).with(csrf())
                        .param("displayName", "New").param("recoveryEmail", "not an address")
                        .param("currentPassword", PW))
                .andExpect(flash().attributeExists("error"));
        mvc.perform(post("/__app/account/profile").session(session).with(csrf())
                        .param("displayName", "x".repeat(129)).param("recoveryEmail", "op3@old.test"))
                .andExpect(flash().attributeExists("error"));
        assertEquals("op3", app("op3-id").displayName());
        assertEquals("op3@old.test", app("op3-id").recoveryEmail());
    }

    @Test
    void aSiteUserAndATenantSuperadminEditTheirOwnRecord() throws Exception {
        MockHttpSession editor = siteLogin("ed1");
        mvc.perform(post("/__admin/account/profile").with(localhost()).session(editor).with(csrf())
                        .param("displayName", "Edith").param("recoveryEmail", "ed@acme.test")
                        .param("currentPassword", PW))
                .andExpect(flash().attribute("flash", "Your profile has been saved."));
        User ed = siteUsers.list("acme").stream().filter(u -> u.username().equals("ed1")).findFirst().orElseThrow();
        assertEquals("Edith", ed.displayName());
        assertEquals("ed@acme.test", ed.recoveryEmail());

        MockHttpSession master = siteLogin("tm1");
        mvc.perform(post("/__admin/account/profile").with(localhost()).session(master).with(csrf())
                        .param("displayName", "Tina").param("recoveryEmail", "tm@old.test"))
                .andExpect(flash().attribute("flash", "Your profile has been saved."));
        User tm = masters.findByUsername("tm1").orElseThrow();
        assertEquals("Tina", tm.displayName());
        assertEquals(List.of("acme"), tm.sites(), "the assignment is untouched");
    }

    @Test
    void aProfileSaveWithoutCsrfIsRefused() throws Exception {
        mvc.perform(post("/__app/account/profile").session(appLogin("root")).param("displayName", "X"))
                .andExpect(status().isForbidden());
        assertEquals("Root", app("app-master").displayName());
    }
}
