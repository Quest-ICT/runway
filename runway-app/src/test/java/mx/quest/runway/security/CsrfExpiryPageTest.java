package mx.quest.runway.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.hamcrest.Matchers;
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
 * A stale/missing CSRF token on an <em>authenticated</em> (real-session) request renders the friendly "your
 * session has expired — sign in again" page with the realm's login link, not a bare 403.
 */
@SpringBootTest
class CsrfExpiryPageTest {

    private static final String PW = "Csrf-Page-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-csrf-page");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(layout.siteDir("acme").resolve("users.yml"), new User("acme-admin", "mgr", hash, "Mgr", "",
                true, false, Realm.TENANT, Set.of(Roles.ADMIN), List.of()));
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
                .apply(springSecurity()).build();
    }

    private MockHttpSession loginAt(String loginUrl, String user) throws Exception {
        MvcResult r = mvc.perform(formLogin(loginUrl).user(user).password(PW)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void appRealmStaleTokenGetsTheFriendlySignInPage() throws Exception {
        MockHttpSession session = loginAt("/app/login", "root");
        // Authenticated session, but the POST carries NO csrf token → CsrfException → the friendly page.
        mvc.perform(post("/__app/plugins/anything/enable").session(session))
                .andExpect(status().isForbidden())
                .andExpect(content().string(Matchers.containsString("session has expired")))
                .andExpect(content().string(Matchers.containsString("Sign in again")))
                .andExpect(content().string(Matchers.containsString("/app/login")));
    }

    @Test
    void tenantRealmStaleTokenLinksToItsOwnLogin() throws Exception {
        MockHttpSession session = loginAt("/login", "mgr");
        mvc.perform(post("/__admin/seo").session(session))
                .andExpect(status().isForbidden())
                .andExpect(content().string(Matchers.containsString("session has expired")))
                .andExpect(content().string(Matchers.containsString("\"/login\"")));
    }
}
