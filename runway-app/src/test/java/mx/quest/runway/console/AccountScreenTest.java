package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import mx.quest.runway.security.AppUserStore;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The application Account screen: change-your-own-password with current-verify + confirm rules. */
@SpringBootTest
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class AccountScreenTest {

    private static final String PASSWORD = "Account-Old-Secret-9";
    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-fe");
        seedMaster(); // the context validates the app identity at startup — the master must exist before boot
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    private static void seedMaster() {
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"),
                new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private AppUserStore appUsers;
    @Autowired
    private PasswordEncoder passwordEncoder;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // Re-seed the master at the known password so each test starts from a clean credential (the store
        // reads fresh from disk, so a prior test's change does not leak).
        seedMaster();
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user("admin").password(PASSWORD)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String currentHash() {
        return appUsers.findByPrincipalId("app-master").orElseThrow().passwordHash();
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void theAccountFormRenders() throws Exception {
        mvc.perform(get("/__app/account").session(login()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"currentPassword\"")));
    }

    @Test
    @org.junit.jupiter.api.Order(4) // last: it changes the login credential
    void changingWithTheCorrectCurrentPasswordSucceeds() throws Exception {
        mvc.perform(post("/__app/account").session(login()).with(csrf())
                        .param("currentPassword", PASSWORD)
                        .param("newPassword", "Account-New-Secret-9").param("confirmPassword", "Account-New-Secret-9"))
                .andExpect(status().is3xxRedirection());
        assertTrue(passwordEncoder.matches("Account-New-Secret-9", currentHash()), "the password was changed");
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void aWrongCurrentPasswordIsRejected() throws Exception {
        mvc.perform(post("/__app/account").session(login()).with(csrf())
                        .param("currentPassword", "not-the-password")
                        .param("newPassword", "whatever-9").param("confirmPassword", "whatever-9"))
                .andExpect(status().is3xxRedirection());
        assertTrue(passwordEncoder.matches(PASSWORD, currentHash()), "the password is unchanged");
        assertFalse(passwordEncoder.matches("whatever-9", currentHash()));
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void aMismatchedConfirmationIsRejected() throws Exception {
        mvc.perform(post("/__app/account").session(login()).with(csrf())
                        .param("currentPassword", PASSWORD)
                        .param("newPassword", "one-9").param("confirmPassword", "two-9"))
                .andExpect(status().is3xxRedirection());
        assertTrue(passwordEncoder.matches(PASSWORD, currentHash()), "the password is unchanged on a mismatch");
    }
}
