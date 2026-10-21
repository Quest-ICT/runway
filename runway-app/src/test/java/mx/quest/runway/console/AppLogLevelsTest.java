package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.platform.PlatformSettingsStore;
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
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Log levels from the console: applied at once, kept in platform.yml and applied again at startup; Default restores
 * the configured level; the server's own logging.level wins and shows locked; platform administrators only; every
 * change is recorded in the activity log.
 */
@SpringBootTest
class AppLogLevelsTest {

    private static final String PW = "Log-Levels-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-loglevels");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(new YamlStore());
        users.save(external.resolve("app-users.yml"), new User("app-master", "admin", hash, "Admin", "", true, true,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(external.resolve("app-users.yml"), new User("app-op", "operator", hash, "Op", "", true, false,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        // A level stored by the console before this start: applied when the server is ready.
        Files.writeString(external.resolve("platform.yml"), "logLevels:\n  mx.quest.runway.page: TRACE\n");
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        // The server sets this one itself: the console must leave it alone.
        registry.add("logging.level.mx.quest.runway.cluster", () -> "WARN");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private LoggingSystem logging;
    @Autowired
    private PlatformSettingsStore platform;
    @Autowired
    private AppActivityLog activity;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpSession login(String user) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(user).password(PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    /** The logger's effective level, or null when the logging system has no configuration for it. */
    private LogLevel effective(String name) {
        var config = logging.getLoggerConfiguration(name);
        return config == null ? null : config.getEffectiveLevel();
    }

    @Test
    void aStoredLevelIsAppliedAtStartup() {
        assertEquals(LogLevel.TRACE, effective("mx.quest.runway.page"));
    }

    @Test
    void aLevelAppliesAtOnceIsKeptAndDefaultRestoresIt() throws Exception {
        MockHttpSession admin = login("admin");
        LogLevel before = effective("mx.quest.runway.backup");
        mvc.perform(post("/__app/logs/levels").session(admin).with(csrf())
                        .param("logger", "mx.quest.runway.backup").param("level", "debug"))
                .andExpect(flash().attribute("flash", "Log level of mx.quest.runway.backup set to DEBUG."));
        assertEquals(LogLevel.DEBUG, effective("mx.quest.runway.backup"));
        assertEquals("DEBUG", platform.get().logLevels().get("mx.quest.runway.backup"));
        assertTrue(activity.tail(10).stream().anyMatch(l -> l.contains("platform.log-level-changed")));

        String page = mvc.perform(get("/__app/logs/levels").session(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("value=\"DEBUG\" selected=\"selected\""), "the stored level is preselected");

        mvc.perform(post("/__app/logs/levels").session(admin).with(csrf())
                        .param("logger", "mx.quest.runway.backup").param("level", ""))
                .andExpect(flash().attributeExists("flash"));
        assertEquals(before, effective("mx.quest.runway.backup"), "Default restores the configured level");
        assertFalse(platform.get().logLevels().containsKey("mx.quest.runway.backup"));
    }

    @Test
    void rootBackToDefaultRestoresInfoInsteadOfFailing() throws Exception {
        MockHttpSession admin = login("admin");
        LogLevel before = effective(LoggingSystem.ROOT_LOGGER_NAME);
        try {
            mvc.perform(post("/__app/logs/levels").session(admin).with(csrf())
                            .param("logger", "ROOT").param("level", "WARN"))
                    .andExpect(flash().attribute("flash", "Log level of ROOT set to WARN."));
            assertEquals(LogLevel.WARN, effective(LoggingSystem.ROOT_LOGGER_NAME));
            assertEquals("WARN", platform.get().logLevels().get("ROOT"));

            mvc.perform(post("/__app/logs/levels").session(admin).with(csrf())
                            .param("logger", "ROOT").param("level", ""))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(flash().attribute("flash", "Log level of ROOT set to default."));
            assertEquals(LogLevel.INFO, effective(LoggingSystem.ROOT_LOGGER_NAME),
                    "with no logging.level.root configured, Default is Spring Boot's INFO");
            assertFalse(platform.get().logLevels().containsKey("ROOT"));
        } finally {
            logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, before);
        }
    }

    @Test
    void badNamesLevelsAndServerSetLoggersAreRefused() throws Exception {
        MockHttpSession admin = login("admin");
        mvc.perform(post("/__app/logs/levels").session(admin).with(csrf())
                        .param("logger", "../etc").param("level", "DEBUG"))
                .andExpect(flash().attributeExists("error"));
        mvc.perform(post("/__app/logs/levels").session(admin).with(csrf())
                        .param("logger", "mx.quest").param("level", "LOUD"))
                .andExpect(flash().attribute("error", "That is not a log level."));
        mvc.perform(post("/__app/logs/levels").session(admin).with(csrf())
                        .param("logger", "mx.quest.runway.cluster").param("level", "TRACE"))
                .andExpect(flash().attribute("error",
                        "That logger's level is set on the server, which wins over this page."));
        // (A test property is not applied by Spring's logging setup, so only "not changed by the console" is checked.)
        assertFalse(effective("mx.quest.runway.cluster") == LogLevel.TRACE, "the console did not change it");
        assertFalse(platform.get().logLevels().containsKey("mx.quest.runway.cluster"));
        String page = mvc.perform(get("/__app/logs/levels").session(admin)).andReturn().getResponse()
                .getContentAsString();
        assertTrue(page.contains("Set on the server in"), "the locked logger says where it is set");
    }

    @Test
    void onlyPlatformAdministratorsMayChangeLevels() throws Exception {
        MockHttpSession op = login("operator");
        mvc.perform(get("/__app/logs/levels").session(op)).andExpect(status().isForbidden());
        mvc.perform(post("/__app/logs/levels").session(op).with(csrf())
                        .param("logger", "ROOT").param("level", "TRACE"))
                .andExpect(status().isForbidden());
        assertFalse(platform.get().logLevels().containsKey("ROOT"));
    }
}
