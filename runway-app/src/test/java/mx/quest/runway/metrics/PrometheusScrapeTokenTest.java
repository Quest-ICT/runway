package mx.quest.runway.metrics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
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

/** The Prometheus scrape token: a correct bearer scrapes without a session; a wrong/absent one is still denied. */
@SpringBootTest
class PrometheusScrapeTokenTest {

    private static final String TOKEN = "s3cr3t-scrape-token-abc123";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-scrape");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Sup3r-Secret-9");
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"),
                new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.metrics.scrape-token", () -> TOKEN);
    }

    @Autowired
    private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void aCorrectBearerTokenScrapesWithoutASession() throws Exception {
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("runway_sites")));
    }

    @Test
    void aWrongBearerTokenIsDenied() throws Exception {
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer not-the-token"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void noTokenIsStillDenied() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().is3xxRedirection());
    }

    @Test
    void theTokenDoesNotOpenOtherActuatorEndpoints() throws Exception {
        // The scrape role is reserved to /actuator/prometheus; it must not reach e.g. /actuator/health details
        // or any other endpoint. (health is public anyway, so probe a non-prometheus actuator path.)
        mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().is3xxRedirection());
    }
}
