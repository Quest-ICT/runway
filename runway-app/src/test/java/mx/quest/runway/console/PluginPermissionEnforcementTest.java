package mx.quest.runway.console;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/**
 * End-to-end enforcement of a <b>plugin-declared</b> permission on a gated endpoint: a plugin control point
 * lands in the catalog as {@code <pluginId>|<controlPoint>} and is enforced by the same
 * {@code @authz.may(...)} method-security expression the kernel uses. The fixture {@code widgets} plugin (loaded
 * from the temp external tree) declares the {@code manage} permission with no default roles, so an ordinary
 * operator is a non-holder and the application superadmin passes via the wildcard — proving both the deny (403)
 * and allow (200) branches over the real HTTP + security stack, not just the {@code mayAccess} unit.
 */
@SpringBootTest
class PluginPermissionEnforcementTest {

    private static final String PASSWORD = "Plugin-Perm-Secret-9";

    @RestController
    static class GatedPluginEndpoint {
        @GetMapping("/__app/widgets/secure")
        @PreAuthorize("@authz.may('widgets|manage')")
        public String secure() {
            return "ok";
        }

        // Gated on a permission key the plugin never declared — must FAIL CLOSED for everyone, wildcard included.
        @GetMapping("/__app/widgets/typo")
        @PreAuthorize("@authz.may('widgets|nonexistent')")
        public String typo() {
            return "ok";
        }
    }

    @TestConfiguration
    static class Beans {
        @Bean
        GatedPluginEndpoint gatedPluginEndpoint() {
            return new GatedPluginEndpoint();
        }
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-pluginperm");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
        UserStore users = new UserStore(new YamlStore());
        Path file = external.resolve("app-users.yml");
        users.save(file, new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(file, new User("op-user", "operator", hash, "Operator", "o@x", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        // A code-less plugin declaring a control point → catalog gets 'widgets|manage' (no default roles).
        Path plugin = Files.createDirectories(external.resolve("plugins").resolve("widgets"));
        Files.writeString(plugin.resolve("plugin.yml"), """
                id: widgets
                version: 1.0.0
                permissions:
                  - id: manage
                    realm: application
                """);
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

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PASSWORD)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void aNonHolderIsDeniedThePluginGatedEndpoint() throws Exception {
        mvc.perform(get("/__app/widgets/secure").session(sessionFor("operator")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aHolderReachesThePluginGatedEndpoint() throws Exception {
        mvc.perform(get("/__app/widgets/secure").session(sessionFor("admin")))
                .andExpect(status().isOk());
    }

    @Test
    void anUndeclaredPermissionFailsClosedEvenForTheSuperadmin() throws Exception {
        // A typo'd / never-declared control-point key must deny everyone — the wildcard superadmin included —
        // so a mistake can never accidentally open an endpoint.
        mvc.perform(get("/__app/widgets/typo").session(sessionFor("admin")))
                .andExpect(status().isForbidden());
    }
}
