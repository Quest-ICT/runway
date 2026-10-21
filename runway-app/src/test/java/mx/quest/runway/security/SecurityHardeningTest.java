package mx.quest.runway.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The reverse cross-realm vector and TCB-not-served — the remaining 5g negative assertions. */
@SpringBootTest
class SecurityHardeningTest {

    private static final String TENANT_PW = "Tenant-Secret-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-5g");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        UserStore users = new UserStore(yaml);
        users.save(layout.siteDir("acme").resolve("users.yml"),
                new User("acme-admin", "admin", PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(TENANT_PW),
                        "Acme Admin", "", true, false, Realm.TENANT, Set.of(Roles.ADMIN), List.of()));
        // ACTIVE mode (a pinned app master) so the realm chains apply rather than the first-run wizard.
        users.save(external.resolve("app-users.yml"),
                new User("app-master", "root", PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("App-Secret-7"),
                        "Root", "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
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
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity())
                .build();
    }

    @Test
    void aTenantSessionDoesNotGrantTheApplicationRealm() throws Exception {
        HttpSession tenantSession = mvc.perform(formLogin("/login").user("admin").password(TENANT_PW))
                .andReturn().getRequest().getSession(false);

        mvc.perform(get("/__app").session((MockHttpSession) tenantSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/login"));
    }

    @Test
    void credentialStoresAreNotReachableOverHttp() throws Exception {
        // The trusted-computing-base files live under the external root, never under a served path —
        // the catch-all page router 404s them and no static handler exposes them.
        for (String path : List.of("/app-users.yml", "/site-users.yml", "/users.yml", "/sites/acme/users.yml")) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }
}
