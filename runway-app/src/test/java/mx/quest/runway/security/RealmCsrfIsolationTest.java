package mx.quest.runway.security;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The two realms coexist in one browser (one {@code HttpSession}) via distinct {@code SecurityContext} keys.
 * This proves their CSRF tokens are <b>also</b> realm-private — stored under distinct session attributes — so a
 * login/logout in one realm (which rotates that realm's CSRF token as a session-fixation defense) can never
 * invalidate an open form in the other realm's tab. Regression guard for the cross-realm 403 found during beta
 * soak-testing.
 */
@SpringBootTest
class RealmCsrfIsolationTest {

    private static final String DEFAULT_CSRF_ATTR =
            "org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-csrf");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Csrf-Test-9");
        new UserStore(yaml).save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root",
                "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
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

    @Test
    void eachRealmStoresItsOwnCsrfTokenSoTheyDoNotCollide() throws Exception {
        MockHttpSession session = new MockHttpSession();

        // Rendering each realm's login form materializes THAT realm's CSRF token into the shared session.
        mvc.perform(get("/app/login").session(session)).andExpect(status().isOk());   // application chain
        mvc.perform(get("/login").session(session)).andExpect(status().isOk());       // tenant chain

        CsrfToken appToken = (CsrfToken) session.getAttribute(AppSecurityConfig.APP_CSRF_TOKEN_KEY);
        // The tenant token is keyed per site: RUNWAY_SITE_CSRF_TOKEN::<siteId> (localhost → the "acme" site).
        CsrfToken siteToken = (CsrfToken) session.getAttribute(TenantSecurityConfig.SITE_CSRF_TOKEN_KEY + "::acme");

        assertNotNull(appToken, "the application realm stores its CSRF token under its own attribute");
        assertNotNull(siteToken, "the tenant realm stores its CSRF token under its own per-site attribute");
        assertNotEquals(appToken.getToken(), siteToken.getToken(),
                "the two realms hold INDEPENDENT CSRF tokens (a login in one never invalidates the other's form)");
        // The default single shared attribute must NOT be used by either chain (that was the collision).
        assertNull(session.getAttribute(DEFAULT_CSRF_ATTR),
                "neither realm uses the default shared CSRF session attribute");
    }
}
