package mx.quest.runway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The login hook end-to-end: auth events feed the gate, and a locked IP is blocked before authentication. */
@SpringBootTest
class LoginProtectionIntegrationTest {

    private static final String PASSWORD = "Sup3r-Secret-9";

    /** A counting gate that suppresses the kernel's permissive default (proves the seam is fed + consulted). */
    static final class CountingGate implements LoginAttemptGate {
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger successes = new AtomicInteger();
        volatile boolean locked = false;

        @Override
        public boolean isLocked(String clientIp) {
            return locked;
        }

        @Override
        public void recordFailure(String clientIp) {
            failures.incrementAndGet();
        }

        @Override
        public void recordSuccess(String clientIp) {
            successes.incrementAndGet();
        }
    }

    @TestConfiguration
    static class Beans {
        @Bean
        @Primary // wins by-type injection over the kernel's always-present permissive default (as the step-8 plugin will)
        LoginAttemptGate countingLoginAttemptGate() {
            return new CountingGate();
        }
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-7d");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"),
                new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private LoginAttemptGate gate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CountingGate counting = (CountingGate) gate;
        counting.failures.set(0);
        counting.successes.set(0);
        counting.locked = false;
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void aFailedLoginIsFedToTheGate() throws Exception {
        mvc.perform(formLogin("/app/login").user("admin").password("wrong")).andExpect(unauthenticated());
        assertTrue(((CountingGate) gate).failures.get() >= 1, "auth-failure event must reach the gate");
    }

    @Test
    void aSuccessfulLoginIsFedToTheGate() throws Exception {
        mvc.perform(formLogin("/app/login").user("admin").password(PASSWORD)).andExpect(authenticated());
        assertTrue(((CountingGate) gate).successes.get() >= 1, "auth-success event must reach the gate");
    }

    @Test
    void aLockedIpIsBlockedBeforeAuthenticationEvenWithCorrectCredentials() throws Exception {
        ((CountingGate) gate).locked = true;
        mvc.perform(formLogin("/app/login").user("admin").password(PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/login?locked"))
                .andExpect(unauthenticated());
        assertEquals(0, ((CountingGate) gate).successes.get(), "authentication must never run for a locked IP");
    }
}
