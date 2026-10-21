package mx.quest.runway.metrics;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** Observability — /actuator/prometheus is served to an app admin (with Runway meters) and denied anonymously. */
@SpringBootTest
class MetricsEndpointTest {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-metrics");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Sup3r-Secret-9");
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
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void prometheusIsServedToAnAppAdminWithRunwayMeters() throws Exception {
        mvc.perform(get("/actuator/prometheus").with(user("admin").roles(Roles.SYSTEM_SUPERADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("runway_sites")))
                .andExpect(content().string(Matchers.containsString("runway_sites_suspended")))
                .andExpect(content().string(Matchers.containsString("runway_sites_maintenance")))
                .andExpect(content().string(Matchers.containsString("runway_plugins_loaded")))
                .andExpect(content().string(Matchers.containsString("runway_logs_dropped")));
    }

    @Test
    void prometheusIsNotPublic() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().is3xxRedirection());
    }

    @Test
    void healthIsPublicAndReportsUp() throws Exception {
        // The public probe sees only the composite status (storage UP: the temp external dir is writable).
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("UP")));
    }

    @Test
    void healthShowsTheStorageIndicatorToAnAuthorizedOperator() throws Exception {
        mvc.perform(get("/actuator/health").with(user("admin").roles(Roles.SYSTEM_SUPERADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("storage")));
    }
}
