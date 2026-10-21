package mx.quest.runway.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** End-to-end tenant realm + dual-session isolation: tenant login is site-scoped; the realms never cross. */
@SpringBootTest
class TenantRealmSecurityTest {

    private static final String TENANT_PW = "Tenant-Secret-7";
    private static final String APP_PW = "App-Secret-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-5f");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        // Site bound from the MockMvc default host "localhost", pre-seeded so the default-site seed skips.
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        UserStore users = new UserStore(yaml);
        String tenantHash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(TENANT_PW);
        users.save(layout.siteDir("acme").resolve("users.yml"),
                new User("acme-admin", "admin", tenantHash, "Acme Admin", "", true, false, Realm.TENANT,
                        Set.of(Roles.ADMIN), List.of()));
        String appHash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(APP_PW);
        users.save(external.resolve("app-users.yml"),
                new User("app-master", "root", appHash, "Root", "", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
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
        // The site filter must run before the security chain so the tenant chain sees the bound site.
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity())
                .build();
    }

    @Test
    void theTenantLoginPageIsPublic() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk());
    }

    @Test
    void theAdminSurfaceRedirectsAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/__admin")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    @Test
    void aTenantUserAuthenticatesAgainstItsOwnSite() throws Exception {
        mvc.perform(formLogin("/login").user("admin").password(TENANT_PW))
                .andExpect(authenticated())
                .andExpect(redirectedUrl("/__admin"));
    }

    @Test
    void wrongTenantCredentialsFail() throws Exception {
        mvc.perform(formLogin("/login").user("admin").password("nope"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void anApplicationSessionDoesNotGrantTheTenantRealm() throws Exception {
        // Sign in to the application realm, then reuse that session against the tenant admin surface:
        // the tenant chain reads its own (empty) session key, so the application session is not a tenant.
        HttpSession appSession = mvc.perform(formLogin("/app/login").user("root").password(APP_PW))
                .andExpect(authenticated())
                .andReturn().getRequest().getSession(false);

        mvc.perform(get("/__admin").session((org.springframework.mock.web.MockHttpSession) appSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void bothRealmsCoexistInOneSession() throws Exception {
        // One browser session, signed into BOTH realms (distinct context keys) — each surface authenticated.
        HttpSession session = mvc.perform(formLogin("/app/login").user("root").password(APP_PW))
                .andReturn().getRequest().getSession(false);
        org.springframework.mock.web.MockHttpSession s = (org.springframework.mock.web.MockHttpSession) session;

        mvc.perform(post("/login").param("username", "admin").param("password", TENANT_PW).session(s).with(csrf()))
                .andExpect(authenticated());

        mvc.perform(get("/__app").session(s)).andExpect(status().isOk());
        mvc.perform(get("/__admin").session(s)).andExpect(status().isOk());
    }
}
