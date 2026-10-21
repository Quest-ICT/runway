package mx.quest.runway.wizard;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
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

/**
 * The ACTIVE side of the wizard lifecycle (mirror of {@link WizardModeTest}): once the application master is
 * pinned (the derived-mode fact that flips WIZARD→ACTIVE), the whole wizard layer is gone — the open
 * {@code /setup} page no longer serves and the application realm enforces {@code /app/login}. This asserts the
 * security posture after the transition; the restart mechanics that reach it are a separate (supervised-relaunch)
 * concern, not a security boundary.
 */
@SpringBootTest
class WizardActiveModeTest {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        // Seeding an app-master AND pinning its id ⇒ ACTIVE mode (RealmActivation validates it at startup).
        Path external = Files.createTempDirectory("runway-active");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Active-Mode-Secret-9");
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"),
                new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void theOpenSetupPageIsGoneInActiveMode() throws Exception {
        // In WIZARD mode this returns 200 with the setup form; in ACTIVE mode the wizard layer is absent, so it
        // is anything but a served-open page (a redirect to login or a 404).
        int statusCode = mvc.perform(get("/setup")).andReturn().getResponse().getStatus();
        assertNotEquals(200, statusCode, "the open /setup wizard page must not serve once the platform is ACTIVE");
    }

    @Test
    void theApplicationRealmEnforcesLoginInActiveMode() throws Exception {
        // WIZARD mode funnels everything to /setup; ACTIVE mode instead enforces authentication on the console.
        mvc.perform(get("/__app"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/login"));
    }
}
