package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.TenantMasterStore;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.SiteStore;
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

/** The Sites screen: render, create (site + provisioned superadmin), suspend, and the master/wildcard gate. */
@SpringBootTest
class SitesScreenTest {

    private static final String PASSWORD = "Sites-Screen-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-fc");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
        UserStore users = new UserStore(new YamlStore());
        Path file = external.resolve("app-users.yml");
        users.save(file, new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(file, new User("op-user", "operator", hash, "Operator", "o@x", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteStore siteStore;
    @Autowired
    private TenantMasterStore masters;
    @Autowired
    private mx.quest.runway.page.PageStore pages;
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
    void theScreenRendersTheCreateFormAndList() throws Exception {
        mvc.perform(get("/__app/sites").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/__app/sites/create")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-listing-table=\"runway.app.sites\"")));
    }

    @Test
    void creatingASiteProvisionsItAndItsSuperadmin() throws Exception {
        mvc.perform(post("/__app/sites/create").session(sessionFor("admin")).with(csrf())
                        .param("siteId", "acme").param("name", "Acme").param("hosts", "acme.test")
                        .param("defaultLocale", "en").param("locales", "es")
                        .param("masterUsername", "acme-boss").param("masterPassword", "Tenant-Boss-9")
                        .param("masterEmail", "boss@acme.test"))
                .andExpect(status().is3xxRedirection());

        assertTrue(siteStore.find("acme").filter(s -> s.active()).isPresent(), "the site was created and active");
        assertTrue(masters.findByUsername("acme-boss").filter(m -> m.master() && m.assignedTo("acme")).isPresent(),
                "its tenant superadmin was provisioned and assigned to the tenancy");
        // The starter presentation is seeded (TenantSeeder on the CREATED event): a published home page exists,
        // so the new site renders at / immediately and born-from-master is available.
        assertTrue(pages.find("acme", "index").filter(mx.quest.runway.page.Page::published).isPresent(),
                "a published starter home page was seeded into the new tenant");
    }

    @Test
    void suspendTogglesTheStatus() throws Exception {
        MockHttpSession master = sessionFor("admin");
        mvc.perform(post("/__app/sites/create").session(master).with(csrf())
                .param("siteId", "beta").param("name", "Beta").param("defaultLocale", "en")
                .param("masterUsername", "beta-boss").param("masterPassword", "Tenant-Boss-9"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/__app/sites/beta/suspend").session(master).with(csrf())).andExpect(status().is3xxRedirection());
        assertTrue(siteStore.find("beta").filter(s -> s.suspended()).isPresent(), "the site is suspended");
    }

    @Test
    void aDuplicateSuperadminUsernameBlocksTheSecondSite() throws Exception {
        MockHttpSession master = sessionFor("admin");
        mvc.perform(post("/__app/sites/create").session(master).with(csrf())
                .param("siteId", "dup1").param("name", "Dup1").param("defaultLocale", "en")
                .param("masterUsername", "sharedboss").param("masterPassword", "Tenant-Boss-9"))
                .andExpect(status().is3xxRedirection());
        // A second site reusing the superadmin username is refused BEFORE the site is created (no rollback needed).
        mvc.perform(post("/__app/sites/create").session(master).with(csrf())
                .param("siteId", "dup2").param("name", "Dup2").param("defaultLocale", "en")
                .param("masterUsername", "sharedboss").param("masterPassword", "Tenant-Boss-9"))
                .andExpect(status().is3xxRedirection());
        assertTrue(siteStore.find("dup2").isEmpty(), "the second site was not created on a duplicate master username");
    }

    @Test
    void disableCaptchaIsMasterGatedAndRedirects() throws Exception {
        MockHttpSession master = sessionFor("admin");
        mvc.perform(post("/__app/sites/create").session(master).with(csrf())
                        .param("siteId", "cap").param("name", "Cap").param("defaultLocale", "en")
                        .param("masterUsername", "cap-boss").param("masterPassword", "Tenant-Boss-9"))
                .andExpect(status().is3xxRedirection());
        // The site-CAPTCHA rescue is master/wildcard-gated like the other mutations.
        mvc.perform(post("/__app/sites/cap/disable-captcha").session(sessionFor("operator")).with(csrf()))
                .andExpect(status().isForbidden());
        // The master may invoke it; with no protection plugin in this kernel-only context it is a no-op default
        // (NoSiteLoginProtectionControl) but the action still completes and redirects.
        mvc.perform(post("/__app/sites/cap/disable-captcha").session(master).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void aNonMasterOperatorCannotMutate() throws Exception {
        mvc.perform(post("/__app/sites/create").session(sessionFor("operator")).with(csrf())
                        .param("siteId", "x").param("name", "X").param("defaultLocale", "en")
                        .param("masterUsername", "u").param("masterPassword", "p"))
                .andExpect(status().isForbidden());
    }
}
