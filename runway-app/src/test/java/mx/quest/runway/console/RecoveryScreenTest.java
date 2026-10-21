package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.platform.PlatformSettings;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.recovery.RecoveryTokenStore;
import mx.quest.runway.security.AppUserStore;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import org.hamcrest.Matchers;
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
 * Renders the application-realm password-recovery screens end-to-end against the real Thymeleaf engine — the
 * check that the decoupled-logic templates actually render (no silent 500 from a restricted-context binding, a
 * bad {@code #{'…' + ${error}}} dynamic key, or a missing model attribute). Covers the forgot form, the neutral
 * confirmation, the reset form (valid + invalid link), a password-mismatch re-render, a successful reset, and
 * the login page's "Forgot your password?" link gated on platform mail being configured.
 */
@SpringBootTest
class RecoveryScreenTest {

    private static final String APP_PASSWORD = "Recovery-Screen-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-recovery");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(APP_PASSWORD);
        UserStore users = new UserStore(new YamlStore());
        Path file = external.resolve("app-users.yml");
        users.save(file, new User("app-master", "admin", hash, "Admin", "admin@x", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(file, new User("op-user", "operator", hash, "Operator", "op@corp.com", true, false,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.security.secret-key", () -> "recovery-screen-test-master-key-0123456789");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private PlatformSettingsStore platform;
    @Autowired
    private RecoveryTokenStore tokens;
    @Autowired
    private AppUserStore appUsers;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        platform.update(previous -> PlatformSettings.EMPTY);
    }

    private void configureMail() {
        platform.update(previous -> PlatformSettings.EMPTY.withSmtp(new PlatformSettings.Smtp(
                "smtp.example.com", 587, false, null, null, "noreply@example.com", true, false)));
    }

    @Test
    void theForgotFormRenders() throws Exception {
        mvc.perform(get("/app/forgot-password"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("name=\"email\"")))
                .andExpect(content().string(Matchers.containsString("action=\"/app/forgot-password\"")))
                // The operator bundle must resolve here (the recovery paths are operator requests): a real label
                // is shown and NO key leaks as ??op.recovery.*?? (regression: recovery routed to the site bundle).
                .andExpect(content().string(Matchers.containsString("Reset your password")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("??op.recovery"))))
                // The bot deterrents are present on the request form: the honeypot trap field (always) and the
                // CAPTCHA slot (populated by the protection plugin when a challenge is configured).
                .andExpect(content().string(Matchers.containsString("name=\"website\"")));
    }

    @Test
    void aForgotPostRedirectsToTheNeutralConfirmation() throws Exception {
        mvc.perform(post("/app/forgot-password").with(csrf()).param("email", "op@corp.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/forgot-password?sent"));
    }

    @Test
    void theSentConfirmationRenders() throws Exception {
        mvc.perform(get("/app/forgot-password").param("sent", ""))
                .andExpect(status().isOk())
                // The email form is gone; the neutral confirmation heading is shown.
                .andExpect(content().string(Matchers.not(Matchers.containsString("name=\"email\""))));
    }

    @Test
    void anInvalidResetLinkRendersTheNotice() throws Exception {
        mvc.perform(get("/app/reset").param("token", "not-a-real-token"))
                .andExpect(status().isOk())
                // The reset form is not shown for an invalid link.
                .andExpect(content().string(Matchers.not(Matchers.containsString("name=\"password\""))));
    }

    @Test
    void aValidResetLinkRendersTheForm() throws Exception {
        String token = tokens.issue(Realm.APPLICATION, null, "op-user", "operator",
                java.time.Duration.ofHours(1), System.currentTimeMillis());
        mvc.perform(get("/app/reset").param("token", token))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("name=\"password\"")))
                .andExpect(content().string(Matchers.containsString("name=\"token\"")))
                .andExpect(content().string(Matchers.containsString("operator"))) // the account name is shown
                // Labels resolve against the operator bundle — no ??op.recovery.*?? leak (regression guard).
                .andExpect(content().string(Matchers.not(Matchers.containsString("??op.recovery"))));
    }

    @Test
    void aPasswordMismatchReRendersWithoutError500() throws Exception {
        String token = tokens.issue(Realm.APPLICATION, null, "op-user", "operator",
                java.time.Duration.ofHours(1), System.currentTimeMillis());
        // Exercises the dynamic message-key `#{'op.recovery.reset.err.' + ${error}}` render path.
        mvc.perform(post("/app/reset").with(csrf())
                        .param("token", token).param("password", "longenough1").param("confirm", "different2"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("name=\"password\"")));
    }

    @Test
    void aValidResetChangesThePasswordAndRedirectsToLogin() throws Exception {
        String before = appUsers.findByPrincipalId("op-user").orElseThrow().passwordHash();
        String token = tokens.issue(Realm.APPLICATION, null, "op-user", "operator",
                java.time.Duration.ofHours(1), System.currentTimeMillis());
        mvc.perform(post("/app/reset").with(csrf())
                        .param("token", token).param("password", "a-fresh-password").param("confirm", "a-fresh-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/login?reset"));
        String after = appUsers.findByPrincipalId("op-user").orElseThrow().passwordHash();
        assertNotEquals(before, after, "the password hash changed");
    }

    @Test
    void theLoginPageShowsForgotOnlyWhenMailIsConfigured() throws Exception {
        mvc.perform(get("/app/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("/app/forgot-password"))));

        configureMail();
        mvc.perform(get("/app/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/app/forgot-password")));
    }
}
