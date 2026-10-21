package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.wizard.Restarter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The plugin-management screen and its actions end to end: the screen renders through the operator shell, the
 * platform-authority gate ({@code isMaster}/{@code isWildcard}) denies a non-master operator every mutation and
 * the restart with {@code 403}, the master can install/disable/uninstall, the restart action runs the
 * (stubbed) restarter and returns the interstitial, the operator login renders on the shell, and the route
 * ordering keeps {@code /__assets} and the operator controllers from shadowing each other.
 */
@SpringBootTest
@Import(PluginAdminScreenTest.StubRestarter.class)
class PluginAdminScreenTest {

    private static final String PASSWORD = "Pl-ugin-Secret-9";
    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-9e");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
        UserStore users = new UserStore(new YamlStore());
        Path file = external.resolve("app-users.yml");
        users.save(file, new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(file, new User("op-user", "operator", hash, "Operator", "o@x", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        // A disabled plugin folder gives the screen a deterministic row (DISABLED, no drift).
        Path demo = Files.createDirectories(external.resolve("plugins").resolve("demo"));
        Files.writeString(demo.resolve("plugin.yml"), "id: demo\nname: Demo\nversion: 1.0.0\nenabled: false\n");
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    /** A no-op restarter that records calls, so the restart path never actually exits the test JVM. */
    @TestConfiguration
    static class StubRestarter {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Bean
        @Primary
        Restarter stubRestarter() {
            return CALLS::incrementAndGet;
        }
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        StubRestarter.CALLS.set(0);
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PASSWORD)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    // ---- the screen -------------------------------------------------------------------------------------

    @Test
    void theScreenRendersThroughTheShellWithItsRows() throws Exception {
        String html = mvc.perform(get("/__app/plugins").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("rw-topbar-app"), "rendered through the operator shell");
        assertTrue(html.contains(">demo<"), "the installed plugin id is listed");
        assertTrue(html.contains("Disabled"), "the disabled plugin shows the Disabled badge");
        assertTrue(html.contains("/__app/plugins/upload"), "the install/upload form is present");
    }

    // ---- the platform-authority gate --------------------------------------------------

    @Test
    void aNonMasterOperatorIsDeniedEveryMutationAndTheRestart() throws Exception {
        MockHttpSession op = sessionFor("operator");
        mvc.perform(post("/__app/plugins/restart").session(op).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/__app/plugins/demo/disable").session(op).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/__app/plugins/demo/uninstall").session(op).with(csrf())).andExpect(status().isForbidden());
        assertEquals(0, StubRestarter.CALLS.get(), "the restarter was never reached");
    }

    @Test
    void theMasterRestartRunsTheRestarterAndShowsTheInterstitial() throws Exception {
        mvc.perform(post("/__app/plugins/restart").session(sessionFor("admin")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Restarting")));
        assertEquals(1, StubRestarter.CALLS.get(), "the master's restart reached the restarter exactly once");
    }

    @Test
    void theMasterCanDisableThenUninstall() throws Exception {
        MockHttpSession master = sessionFor("admin");
        // 'demo' is already disabled; enable it, then uninstall it.
        mvc.perform(post("/__app/plugins/demo/enable").session(master).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertTrue(Files.readString(external.resolve("plugins/demo/plugin.yml")).contains("enabled: true"));

        mvc.perform(post("/__app/plugins/demo/uninstall").session(master).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertFalse(Files.exists(external.resolve("plugins/demo")), "the folder was removed");
    }

    @Test
    void theMasterCanUploadAPlugin() throws Exception {
        byte[] zip = zip("fresh/plugin.yml", "id: fresh\nversion: 2.0.0\n");
        MockMultipartFile file = new MockMultipartFile("file", "fresh.zip", "application/zip", zip);
        mvc.perform(multipart("/__app/plugins/upload").file(file).session(sessionFor("admin")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertTrue(Files.exists(external.resolve("plugins/fresh/plugin.yml")), "the uploaded plugin was installed");
    }

    /**
     * Through the real chain: the master's upload of an archive claiming the essential id is
     * refused with a message saying where the essential plugin comes from, and nothing lands on disk; a non-master
     * operator's attempt is a 403 before the archive is even read.
     */
    @Test
    void anUploadClaimingTheEssentialIdIsRefused() throws Exception {
        byte[] zip = zip("protection/plugin.yml", "id: protection\nversion: 9.9.9\nessential: true\n");
        MockMultipartFile file = new MockMultipartFile("file", "protection.zip", "application/zip", zip);
        mvc.perform(multipart("/__app/plugins/upload").file(file).session(sessionFor("operator")).with(csrf()))
                .andExpect(status().isForbidden());
        String error = (String) mvc.perform(multipart("/__app/plugins/upload").file(file)
                        .session(sessionFor("admin")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn().getFlashMap().get("error");
        assertTrue(error != null && error.contains("essential") && error.contains("installer"), String.valueOf(error));
        Path landed = external.resolve("plugins/protection/plugin.yml");
        assertFalse(Files.exists(landed) && Files.readString(landed).contains("9.9.9"), "the upload never landed");
    }

    // ---- the login + route specificity ------------------------------------------------------------------

    @Test
    void theOperatorLoginRendersOnTheShell() throws Exception {
        mvc.perform(get("/app/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Operator sign in")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/__assets/runway-chrome.js")));
    }

    @Test
    void assetsAndOperatorRoutesDoNotShadowEachOther() throws Exception {
        // The asset handler serves /__assets (not shadowed by a controller)...
        mvc.perform(get("/__assets/vendor/htmx.min.js")).andExpect(status().isOk());
        // ...and an operator route still reaches its controller (not shadowed by the asset handler).
        mvc.perform(get("/__app/plugins").session(sessionFor("admin"))).andExpect(status().isOk());
        // A public path is NOT an operator page (it falls through to the tenant chain / PageController).
        MvcResult publicResult = mvc.perform(get("/no-such-public-page")).andReturn();
        assertFalse(publicResult.getResponse().getStatus() >= 500, "public path handled, not a server error");
        assertFalse(publicResult.getResponse().getContentAsString().contains("rw-topbar-app"),
                "a public path never renders the operator chrome");
    }

    private static byte[] zip(String name, String content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }
}
