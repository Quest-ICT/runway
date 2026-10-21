package mx.quest.runway.console;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.RunningPlugins;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteContextFilter;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.widget.WidgetInstanceConfigService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The tenant Content-widgets screen: it discovers placed anchors, renders a typed editor for a configurable
 * placement (nested field loop) and a "not configurable" note for the rest, saves and applies immediately, and
 * is CONFIGURE_SITE-gated. A test {@link RunningPlugins} supplies a {@code carousel} widget schema (no real
 * plugin loads in an integration test).
 */
@SpringBootTest
class WidgetInstancesScreenTest {

    private static final String PW = "Widget-Screen-9";

    @TestConfiguration
    static class Plugins {
        /** A stand-in loaded plugin declaring a per-instance schema for the {@code carousel} widget type. */
        @Bean
        @Primary
        RunningPlugins testRunningPlugins() {
            PluginDescriptor d = new PluginDescriptor("wtest", "Widget Test", "1.0.0", null, null, null, null, null,
                    List.of(), List.of(), List.of(),
                    Map.of("carousel", List.of(new PluginSettingDef("heading", "string", null, "carousel.heading", null))),
                    List.of(), List.of(), null);
            return () -> List.of(new LoadedPlugin(d, Path.of("/tmp/wtest")));
        }
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-widgets");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));

        // One configurable anchor (carousel/home) and one whose type no plugin declares (mystery/x).
        Path templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("home.html"),
                "<div id=\"hero\" data-widget=\"carousel/home\"></div>"
                        + "<div id=\"m\" data-widget=\"mystery/x\"></div>", StandardCharsets.UTF_8);

        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        Path siteUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(siteUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));      // wildcard → has CONFIGURE_SITE
        users.save(siteUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));    // no CONFIGURE_SITE
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private WidgetInstanceConfigService widgetConfig;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult r = mvc.perform(formLogin("/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void theScreenListsBothPlacementsAndRendersAFormForTheConfigurableOne() throws Exception {
        mvc.perform(get("/__admin/widgets").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("carousel")))
                .andExpect(content().string(Matchers.containsString("mystery")))
                // configurable: a form posting to the instance, with the declared field
                .andExpect(content().string(Matchers.containsString("action=\"/__admin/widgets/carousel/home\"")))
                .andExpect(content().string(Matchers.containsString("name=\"heading\"")))
                // not configurable: the note, no form for it
                .andExpect(content().string(Matchers.not(
                        Matchers.containsString("action=\"/__admin/widgets/mystery/x\""))));
    }

    @Test
    void savingAppliesImmediatelyAndPreFillsOnReload() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(post("/__admin/widgets/carousel/home").session(admin).with(csrf())
                        .param("heading", "Welcome aboard"))
                .andExpect(status().is3xxRedirection());

        org.junit.jupiter.api.Assertions.assertEquals("Welcome aboard",
                widgetConfig.config("acme", "carousel", "home").get("heading"));

        mvc.perform(get("/__admin/widgets").session(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Welcome aboard")));
    }

    @Test
    void savingANonConfigurableTypeIs404() throws Exception {
        mvc.perform(post("/__admin/widgets/mystery/x").session(sessionFor("admin")).with(csrf())
                        .param("anything", "value"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anOperatorWithoutConfigureSiteCannotSeeTheScreen() throws Exception {
        mvc.perform(get("/__admin/widgets").session(sessionFor("op")))
                .andExpect(status().isForbidden());
    }
}
