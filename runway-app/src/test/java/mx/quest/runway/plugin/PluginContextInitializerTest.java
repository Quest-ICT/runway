package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.head.HeadContributorRegistry;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The keystone: a real plugin jar, built at test time, loaded through the production register-before-refresh
 * path, wires its {@code @Configuration} beans in the <b>same</b> refresh pass as the kernel's — proven by the
 * plugin's {@code HeadContributor} being collected by type. ACTIVE mode (an app-master is pinned).
 */
@SpringBootTest
class PluginContextInitializerTest {

    private static final String PASSWORD = "Sup3r-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-8c-active");
        if (PluginFixture.compilerAvailable()) {
            PluginFixture.buildAlpha(external.resolve("plugins"));
        }
        // app-level plugin settings (UI-owned, outside the install dir) become a property source
        Files.createDirectories(external.resolve("plugin-settings"));
        Files.writeString(external.resolve("plugin-settings").resolve("alpha.yml"),
                "runway:\n  plugin:\n    alpha:\n      greeting: hi\n    beta:\n      stolen: yes\n"
                        + "  node:\n    name: hijacked\n"
                        + "server:\n  port: 1\n");
        // A plugin's own data file kept in the same folder, and a removed plugin's settings: neither is loaded.
        Files.writeString(external.resolve("plugin-settings").resolve("protection-app.yml"),
                "honeypotEnabled: true\ncaptcha:\n  secretKey: ENC(x)\n");
        Files.writeString(external.resolve("plugin-settings").resolve("ghost.yml"),
                "runway:\n  plugin:\n    ghost:\n      level: 3\n");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"),
                new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master"); // ACTIVE
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PluginManager pluginManager;

    @Autowired
    private HeadContributorRegistry headRegistry;

    @Test
    void thePluginsConfigurationBeansWireInTheSameRefreshPass() {
        assumeTrue(PluginFixture.compilerAvailable(), "no in-JVM compiler (JRE-only) — skipping");
        boolean alphaWired = context.getBeansOfType(HeadContributor.class).values().stream()
                .anyMatch(h -> "alpha.head".equals(h.key()));
        assertTrue(alphaWired, "the plugin's @Bean HeadContributor must be collected by type (parent-first SPI)");
        assertFalse(headRegistry.isEmpty(), "the kernel registry collected the plugin contributor");
    }

    @Test
    void theManagerReportsThePluginInstalled() {
        assumeTrue(PluginFixture.compilerAvailable(), "no in-JVM compiler (JRE-only) — skipping");
        assertTrue(pluginManager.installed().stream().anyMatch(p -> "alpha".equals(p.id())));
    }

    @Test
    void appLevelPluginSettingsBecomeAPropertySource() {
        // The UI-owned external/plugin-settings/<id>.yml is loaded into the Environment so a plugin binds it.
        assertEquals("hi", context.getEnvironment().getProperty("runway.plugin.alpha.greeting"));
    }

    @Test
    void aPluginSettingsFileSetsOnlyItsOwnPluginsKeys() {
        // alpha.yml also carries keys outside runway.plugin.alpha.*: none of them reaches the Environment.
        assertNotEquals("hijacked", context.getEnvironment().getProperty("runway.node.name"));
        assertNull(context.getEnvironment().getProperty("runway.plugin.beta.stolen"), "not another plugin's either");
        assertNotEquals("1", context.getEnvironment().getProperty("server.port"));
    }

    @Test
    void aFileNotNamedForAnInstalledPluginIsNotConfiguration() {
        assumeTrue(PluginFixture.compilerAvailable(), "no in-JVM compiler (JRE-only) — alpha is not installed");
        assertNull(context.getEnvironment().getProperty("honeypotEnabled"), "a plugin's own data file");
        assertNull(context.getEnvironment().getProperty("captcha.secretKey"), "its secret never becomes a property");
        assertNull(context.getEnvironment().getProperty("runway.plugin.ghost.level"), "a removed plugin's settings");
        assertEquals("hi", context.getEnvironment().getProperty("runway.plugin.alpha.greeting"), "an installed one loads");
    }
}
