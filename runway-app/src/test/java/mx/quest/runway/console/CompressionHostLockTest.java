package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.platform.PlatformSettings;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.CompressionFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A compression switch the server sets wins over the one Platform settings stored, and the form shows it locked.
 * Until 1.0.0 the console's switches always won, so a later change in the host's application.yml was ignored.
 */
@SpringBootTest
class CompressionHostLockTest {

    private static final String PASSWORD = "Compression-Lock-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-comp-lock");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"), new User("app-master", "admin", hash,
                "Admin", "a@x", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.compression.gzip.enabled", () -> "false"); // set on the host
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private PlatformSettingsStore platform;
    @Autowired
    private CompressionFilter compression;

    @Test
    void theHostsSwitchWinsIsShownLockedAndIsNotStored() throws Exception {
        platform.update(previous -> new PlatformSettings(null, null, null, true, true, true)); // an old console override
        assertFalse(compression.gzipOn(), "the host's false beats the stored true");
        assertTrue(compression.brotliOn(), "a switch the host does not set still follows the console");

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        MockHttpSession admin = (MockHttpSession) mvc.perform(formLogin("/app/login").user("admin")
                .password(PASSWORD)).andReturn().getRequest().getSession(false);
        String html = mvc.perform(get("/__app/settings").session(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.matches("(?s).*id=\"ps-gzip\"[^>]*disabled.*"), "the gzip switch is disabled");
        assertTrue(html.contains("Set on the server in"), "and says why");

        mvc.perform(post("/__app/settings").session(admin).with(csrf()).param("compressionEnabled", "true")
                .param("gzipEnabled", "true").param("brotliEnabled", "true"));
        assertNull(platform.get().gzipEnabled(), "the console no longer stores a value for it");
        assertTrue(platform.get().brotliEnabled(), "but still for the others");
    }
}
