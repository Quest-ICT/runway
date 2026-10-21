package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import org.hamcrest.Matchers;
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

/** The tenant Maintenance screen: renders + toggles maintenance, and is MAINTENANCE_MODE-gated. */
@SpringBootTest
class MaintenanceScreenTest {

    private static final String PW = "Maint-Screen-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-l3");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        Path siteUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(siteUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));      // wildcard → has MAINTENANCE_MODE
        users.save(siteUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));    // no MAINTENANCE_MODE
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
    private SiteStore sites;
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
    void theFormRenders() throws Exception {
        mvc.perform(get("/__admin/maintenance").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("action=\"/__admin/maintenance\"")))
                .andExpect(content().string(Matchers.containsString("name=\"enabled\"")))
                .andExpect(content().string(Matchers.containsString("name=\"message\"")));
    }

    @Test
    void toggleOnThenOffFlipsTheStoredFlag() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(post("/__admin/maintenance").session(admin).with(csrf())
                        .param("enabled", "true")
                        .param("message", "Back at 3pm"))
                .andExpect(status().is3xxRedirection());
        assertTrue(sites.find("acme").orElseThrow().maintenance(), "maintenance should be on");
        assertEquals("Back at 3pm", sites.find("acme").orElseThrow().maintenanceMessage());

        // The stored state pre-fills the switch on reload (proves the .th.xml checked-binding works).
        mvc.perform(get("/__admin/maintenance").session(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Back at 3pm")));

        // Turning it off clears the message (unchecked box submits no `enabled` param → false).
        mvc.perform(post("/__admin/maintenance").session(admin).with(csrf())
                        .param("message", "ignored when off"))
                .andExpect(status().is3xxRedirection());
        assertFalse(sites.find("acme").orElseThrow().maintenance(), "maintenance should be off");
        assertEquals(null, sites.find("acme").orElseThrow().maintenanceMessage(), "message cleared when off");
    }

    @Test
    void anOperatorWithoutMaintenanceModeCannotSeeTheScreen() throws Exception {
        mvc.perform(get("/__admin/maintenance").session(sessionFor("op")))
                .andExpect(status().isForbidden());
    }
}
