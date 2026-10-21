package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.plugin.SitePluginConfigService;
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
 * The plugin settings gate: {@code /__admin/plugins/<id>/settings} opens to the holders of the plugin's
 * declared {@code <id>|settings} permission — the rule the Settings menu already used to show the link — and keeps
 * the historical "tenancy administrators only" gate for a plugin that declares no such point.
 */
@SpringBootTest
class TenantPluginSettingsGateTest {

    private static final String PW = "Settings-Gate-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-settings-gate");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        Path tenantUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(tenantUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));
        users.save(tenantUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true, true,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        plugin(external, "gizmo", """
                permissions:
                  - { id: settings, realm: tenant, defaultRoles: [OPERATOR] }
                """);
        plugin(external, "locked", """
                permissions:
                  - { id: settings, realm: tenant, defaultRoles: [] }
                """);
        plugin(external, "plain", "");
        registry.add("runway.storage.external", external::toString);
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    private static void plugin(Path external, String id, String permissions) throws IOException {
        Path dir = Files.createDirectories(external.resolve("plugins").resolve(id));
        Files.writeString(dir.resolve("plugin.yml"), "id: " + id + "\nname: " + id + "\nversion: 1.0.0\n"
                + permissions + "siteSettings:\n  - { key: greeting, type: string, default: hi }\n");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SitePluginConfigService config;
    @Autowired
    private SiteResolver siteResolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void aHolderOfTheDeclaredSettingsPermissionMayOpenAndSaveTheForm() throws Exception {
        MockHttpSession op = sessionFor("op");
        mvc.perform(get("/__admin/plugins/gizmo/settings").session(op)).andExpect(status().isOk());
        mvc.perform(post("/__admin/plugins/gizmo/settings").session(op).with(csrf()).param("greeting", "hello"))
                .andExpect(status().is3xxRedirection());
        assertEquals("hello", config.config("acme", "gizmo").get("greeting"));
    }

    @Test
    void aNonHolderIsRefusedEvenThoughThePluginDeclaresThePoint() throws Exception {
        MockHttpSession op = sessionFor("op");
        mvc.perform(get("/__admin/plugins/locked/settings").session(op)).andExpect(status().isForbidden());
        mvc.perform(post("/__admin/plugins/locked/settings").session(op).with(csrf()).param("greeting", "x"))
                .andExpect(status().isForbidden());
    }

    @Test
    void withoutADeclaredPointOnlyTheTenancyAdministratorsGetIn() throws Exception {
        mvc.perform(get("/__admin/plugins/plain/settings").session(sessionFor("op")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/__admin/plugins/plain/settings").session(sessionFor("admin")))
                .andExpect(status().isOk());
    }

    @Test
    void theTenancyAdministratorKeepsAccessToEveryPluginsSettings() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(get("/__admin/plugins/gizmo/settings").session(admin)).andExpect(status().isOk());
        mvc.perform(get("/__admin/plugins/locked/settings").session(admin)).andExpect(status().isOk());
    }

    @Test
    void anUnsafePluginIdIsNotFound() throws Exception {
        mvc.perform(get("/__admin/plugins/a|b/settings").session(sessionFor("admin")))
                .andExpect(status().isNotFound());
    }
}
