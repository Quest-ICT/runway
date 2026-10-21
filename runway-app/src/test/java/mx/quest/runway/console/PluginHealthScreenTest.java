package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.plugin.ContributionGuard;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginQuarantine;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The plugins screen's health surface: contained failures with the last error, refused contributions, Retry for a
 * quarantined plugin, and (SPI 1.0) blocked plugins with their reason and the "uses host internals" badge — rendered
 * for the master, Retry refused ({@code 403}) to an operator who is not the platform authority.
 */
@SpringBootTest
class PluginHealthScreenTest {

    private static final String PW = "Health-Screen-Secret-9";
    private static Path external;

    /** SPI-only and compliant: loads, then has one contained failure and one refused (colliding) contribution. */
    private static final String GAMMA = """
            package fixtures.health.gamma;
            import mx.quest.runway.spi.HeadContributor;
            import mx.quest.runway.spi.model.HeadContext;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            @Configuration
            public class GammaConfig {
                @Bean public HeadContributor gammaHead() { return new Head("shared.key"); }
                public static class Head implements HeadContributor {
                    private final String key;
                    public Head(String key) { this.key = key; }
                    public String key() { return key; }
                    public String render(HeadContext c) { return ""; }
                }
            }
            """;

    private static final String DELTA = """
            package fixtures.health.delta;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            @Configuration
            public class DeltaConfig {
                @Bean public fixtures.health.delta.DeltaConfig.Head deltaHead() { return new Head(); }
                public static class Head implements mx.quest.runway.spi.HeadContributor {
                    public String key() { return "shared.key"; }
                    public String render(mx.quest.runway.spi.model.HeadContext c) { return ""; }
                }
            }
            """;

    /** Links a kernel class: blocked by the linkage scanner (SPI 1.0), with the badge and the reason. */
    private static final String HOSTY = """
            package fixtures.health.hosty;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            @Configuration
            public class HostyConfig {
                @Bean public Object hostyThing(mx.quest.runway.site.SiteStore sites) { return new Object(); }
            }
            """;

    /** SPI-only, but an unprefixed bean name: blocked by the before-refresh surface check. */
    private static final String SLOPPY = """
            package fixtures.health.sloppy;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            @Configuration
            public class SloppyConfig {
                @Bean public Object unprefixedThing() { return new Object(); }
            }
            """;

    /** SPI-only and compliant, but declares no requiresSpi: built for the pre-1.0 contract, blocked. */
    private static final String OLDIE = """
            package fixtures.health.oldie;
            import org.springframework.context.annotation.Configuration;
            @Configuration
            public class OldieConfig {
            }
            """;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-plugin-health");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(new YamlStore());
        Path file = external.resolve("app-users.yml");
        users.save(file, new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(file, new User("op-user", "operator", hash, "Operator", "o@x", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        Path plugins = external.resolve("plugins");
        if (mx.quest.runway.plugin.PluginFixture.compilerAvailable()) {
            mx.quest.runway.plugin.PluginFixture.build(plugins, "gamma", "id: gamma\nversion: 1.0.0\n"
                    + "requiresSpi: \">=0.6.0\"\nmainClass: fixtures.health.gamma.GammaConfig\n",
                    Map.of("fixtures.health.gamma.GammaConfig", GAMMA));
            mx.quest.runway.plugin.PluginFixture.build(plugins, "delta", "id: delta\nversion: 1.0.0\n"
                    + "requiresSpi: \">=1.0.0\"\nmainClass: fixtures.health.delta.DeltaConfig\n",
                    Map.of("fixtures.health.delta.DeltaConfig", DELTA));
            mx.quest.runway.plugin.PluginFixture.build(plugins, "hosty", "id: hosty\nversion: 1.0.0\n"
                    + "requiresSpi: \">=0.4.0\"\nmainClass: fixtures.health.hosty.HostyConfig\n",
                    Map.of("fixtures.health.hosty.HostyConfig", HOSTY));
            mx.quest.runway.plugin.PluginFixture.build(plugins, "sloppy", "id: sloppy\nversion: 1.0.0\n"
                    + "requiresSpi: \">=1.0.0\"\nmainClass: fixtures.health.sloppy.SloppyConfig\n",
                    Map.of("fixtures.health.sloppy.SloppyConfig", SLOPPY));
            // Requires the surface-blocked plugin: blocked by the cascade.
            Path needy = Files.createDirectories(plugins.resolve("needy"));
            Files.writeString(needy.resolve("plugin.yml"), "id: needy\nversion: 1.0.0\nrequires: [sloppy]\n");
            mx.quest.runway.plugin.PluginFixture.build(plugins, "oldie", "id: oldie\nversion: 1.4.0\n"
                    + "mainClass: fixtures.health.oldie.OldieConfig\n",
                    Map.of("fixtures.health.oldie.OldieConfig", OLDIE));
        }
        // A plugin that broke a previous boot: its marker matches its version and (absent) jars.
        Path eps = Files.createDirectories(plugins.resolve("eps"));
        Files.writeString(eps.resolve("plugin.yml"), "id: eps\nname: Eps\nversion: 3.0.0\n");
        LoadedPlugin epsPlugin = new LoadedPlugin(PluginDescriptor.read(new ByteArrayInputStream(
                "id: eps\nversion: 3.0.0\n".getBytes(StandardCharsets.UTF_8))), eps);
        PluginQuarantine.quarantine(external, epsPlugin, "BeanCreationException: epsThing failed");
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ContributionGuard guard;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void theScreenShowsHealthBadgesFailuresRefusalsWarningsAndRetry() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(mx.quest.runway.plugin.PluginFixture.compilerAvailable());
        guard.run(context.getBean("gammaHead"), ContributionGuard.Axis.HEAD, "shared.key", () -> {
            throw new IllegalStateException("gamma exploded");
        });
        String html = mvc.perform(get("/__app/plugins").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Failures: 1"), "the contained failure is counted");
        assertTrue(html.contains("gamma exploded"), "the last error is shown");
        assertTrue(html.contains("Refused contribution"), "the key collision refused one side");
        assertTrue(html.contains("/__app/plugins/eps/retry"), "a quarantined plugin offers Retry");
        assertFalse(html.contains("/__app/plugins/gamma/retry"), "a healthy plugin does not");
    }

    /**
     * SPI 1.0: a plugin built for the pre-1.0 contract, one that links kernel classes and one that breaks the
     * Spring-context rules are each <b>Blocked</b> — the platform is up and serving this very screen — and the reason
     * is printed in the row (not only in the badge's tooltip).
     */
    @Test
    void blockedPluginsShowTheirReasonInTheRow() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(mx.quest.runway.plugin.PluginFixture.compilerAvailable());
        mx.quest.runway.plugin.PluginRuntime runtime = context.getBean(mx.quest.runway.plugin.PluginRuntime.class);
        assertEquals(java.util.Set.of("hosty", "sloppy", "needy", "oldie", "eps"), runtime.blocked().keySet(),
                runtime.blocked().toString());
        assertTrue(runtime.loaded().stream().anyMatch(p -> p.id().equals("gamma")), "compliant plugins still load");
        String html = mvc.perform(get("/__app/plugins").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String text = html.replace("&#39;", "'");
        assertTrue(text.contains("class=\"c-blocked small text-danger mt-1\">uses Runway's internal classes"),
                "hosty's linkage reason is in the row");
        assertTrue(text.contains("mx.quest.runway.site.SiteStore"), "naming the kernel class");
        assertTrue(html.contains("Uses host internals"), "hosty keeps the badge");
        assertTrue(text.contains("breaks the plugin rules for Runway's shared Spring context")
                && text.contains("bean name 'unprefixedThing' does not start with 'sloppy'"), "sloppy's reason");
        assertTrue(text.contains("was built for the pre-1.0 plugin contract (declares no requiresSpi"),
                "oldie's reason");
        assertTrue(text.contains("requires 'sloppy', which is itself blocked"), "the surface block cascades");
        assertFalse(html.contains("Will be refused by Runway 1.0"), "no warn-mode wording is left");
    }

    @Test
    void retryIsAPlatformOperation() throws Exception {
        mvc.perform(post("/__app/plugins/eps/retry").session(sessionFor("operator")).with(csrf()))
                .andExpect(status().isForbidden());
        assertTrue(Files.exists(external.resolve("plugin-quarantine/eps.yml")));
        mvc.perform(post("/__app/plugins/eps/retry").session(sessionFor("admin")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertFalse(Files.exists(external.resolve("plugin-quarantine/eps.yml")), "the marker is gone");
    }
}
