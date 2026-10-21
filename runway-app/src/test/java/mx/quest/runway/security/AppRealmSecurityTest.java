package mx.quest.runway.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.spi.model.Realm;
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

/** End-to-end application realm: the chain protects {@code /__app}, the login flow works, health is open. */
@SpringBootTest
class AppRealmSecurityTest {

    private static final String PASSWORD = "Sup3r-Secret-9";
    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-5c");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"),
                new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Boot 4 modularized @AutoConfigureMockMvc out of starter-test; build MockMvc directly,
        // applying the Spring Security filter via the security-test configurer.
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void theLoginPageIsPublic() throws Exception {
        mvc.perform(get("/app/login"))
                .andExpect(status().isOk())
                // the shell-rendered operator login (op.login.app.title resolved via the operator bundle)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Operator sign in")));
    }

    @Test
    void theConsoleRedirectsAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/__app"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/login"));
    }

    @Test
    void theHealthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void validCredentialsAuthenticateAndLandOnTheConsole() throws Exception {
        mvc.perform(formLogin("/app/login").user("admin").password(PASSWORD))
                .andExpect(authenticated())
                .andExpect(redirectedUrl("/__app"));
    }

    @Test
    void wrongCredentialsFailToTheErrorPage() throws Exception {
        mvc.perform(formLogin("/app/login").user("admin").password("nope"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/app/login?error"));
    }

    @Test
    void anAuthenticatedOperatorReachesTheConsole() throws Exception {
        mvc.perform(get("/__app").with(user("admin").roles(Roles.SYSTEM_SUPERADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Operator console")));
    }

    @Test
    void aPublicPathIsNotCapturedByTheApplicationChain() throws Exception {
        // The default chain permits it (reaching the page controller), so it is NOT redirected to login.
        mvc.perform(get("/about")).andExpect(status().isNotFound());
    }

    @Test
    void theWizardSurfaceIsAbsentInActiveMode() throws Exception {
        // A pin is set (ACTIVE), so the whole wizard layer is gone — /setup is just an unknown path.
        mvc.perform(get("/setup")).andExpect(status().isNotFound());
    }
}
