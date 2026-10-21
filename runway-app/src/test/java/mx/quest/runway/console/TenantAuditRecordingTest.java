package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.SiteUserStore;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The tenant-side events that were not journaled before land in the bound site's audit log, through the real
 * console routes: sub-user create / password reset / delete, a per-site plugin settings save, a site-config save
 * (naming the changed parts, never the values), a tenant permissions change, and a tenant sign-out. A refused or
 * no-op change records nothing, and no password or setting value is ever written to the journal.
 */
@SpringBootTest
class TenantAuditRecordingTest {

    private static final String PW = "Tenant-Audit-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-audit-tenant");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        users.save(layout.siteDir("acme").resolve("users.yml"), new User("acme-admin", "admin", hash, "Admin", "",
                true, false, Realm.TENANT, Set.of(Roles.ADMIN), List.of()));
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true, true,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        Path plugin = Files.createDirectories(external.resolve("plugins").resolve("gadgets"));
        Files.writeString(plugin.resolve("plugin.yml"), """
                id: gadgets
                name: Gadgets
                version: 1.0.0
                siteSettings:
                  - key: greeting
                    type: string
                """);
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private SiteUserStore siteUsers;
    @MockitoSpyBean
    private SiteLogService siteLog;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private MockHttpSession admin() throws Exception {
        MvcResult r = mvc.perform(formLogin("/login").user("admin").password(PW)).andReturn();
        clearInvocations(siteLog); // forget the login line; each test asserts only what it caused
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    /** Every audit detail passed so far, for "never journals a secret" checks. */
    private List<String> allDetails() {
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(siteLog, org.mockito.Mockito.atLeast(0)).audit(anyString(), anyString(), anyString(), details.capture());
        return details.getAllValues();
    }

    @Test
    void subUserCreateResetAndDeleteAreJournaledWithoutPasswords() throws Exception {
        MockHttpSession admin = admin();
        mvc.perform(post("/__admin/users/create").session(admin).with(csrf())
                        .param("username", "audit-bob").param("password", "Bob-Secret-Pw-1").param("tier", "OPERATOR"))
                .andExpect(status().is3xxRedirection());
        verify(siteLog).audit(eq("acme"), eq("admin"), eq("user.created"), contains("audit-bob"));

        String id = siteUsers.findByUsername("acme", "audit-bob").orElseThrow().principalId();
        mvc.perform(post("/__admin/users/reset-password").session(admin).with(csrf())
                        .param("principalId", id).param("password", "Bob-New-Secret-2"))
                .andExpect(status().is3xxRedirection());
        verify(siteLog).audit("acme", "admin", "user.password-reset", "audit-bob");

        mvc.perform(post("/__admin/users/" + id + "/delete").session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(siteLog).audit("acme", "admin", "user.deleted", "audit-bob");

        mvc.perform(post("/__admin/users/no-such-id/delete").session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(siteLog, times(1)).audit(eq("acme"), anyString(), eq("user.deleted"), anyString());

        assertTrue(allDetails().stream().noneMatch(d -> d.contains("Secret")), "no password in the journal");
    }

    @Test
    void aPluginSettingsSaveIsJournaledWithoutItsValues() throws Exception {
        mvc.perform(post("/__admin/plugins/gadgets/settings").session(admin()).with(csrf())
                        .param("greeting", "TopSecretGreeting"))
                .andExpect(status().is3xxRedirection());
        verify(siteLog).audit("acme", "admin", "plugin.settings-changed", "gadgets");
        assertTrue(allDetails().stream().noneMatch(d -> d.contains("TopSecretGreeting")));
    }

    @Test
    void aSiteConfigSaveNamesTheChangedPartsAndANoOpSaveRecordsNothing() throws Exception {
        MockHttpSession admin = admin();
        mvc.perform(post("/__admin/config").session(admin).with(csrf())
                        .param("name", "Acme Audit").param("defaultLocale", "en").param("locale", "en", "es")
                        .param("contactEmail", "audit@acme.test")
                        .param("paramKey", "auditkey").param("paramValue", "PrivateParamValue"))
                .andExpect(status().is3xxRedirection());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(siteLog).audit(eq("acme"), eq("admin"), eq("site-config.changed"), detail.capture());
        assertEquals("name,languages,contact,params", detail.getValue());

        clearInvocations(siteLog);
        mvc.perform(post("/__admin/config").session(admin).with(csrf())
                        .param("name", "Acme Audit").param("defaultLocale", "en").param("locale", "en", "es")
                        .param("contactEmail", "audit@acme.test")
                        .param("paramKey", "auditkey").param("paramValue", "PrivateParamValue"))
                .andExpect(status().is3xxRedirection());
        verify(siteLog, never()).audit(anyString(), anyString(), eq("site-config.changed"), anyString());
        assertTrue(allDetails().stream().noneMatch(d -> d.contains("PrivateParamValue")));
    }

    @Test
    void aTenantPermissionsChangeIsJournaledButARefusedOneIsNot() throws Exception {
        MockHttpSession admin = admin();
        mvc.perform(post("/__admin/permissions/create-role").session(admin).with(csrf()).param("name", "AUDITEDITOR"))
                .andExpect(status().is3xxRedirection());
        verify(siteLog).audit("acme", "admin", "permissions.role-created", "role=AUDITEDITOR");

        mvc.perform(post("/__admin/permissions/create-role").session(admin).with(csrf()).param("name", "AUDITEDITOR"))
                .andExpect(status().is3xxRedirection()); // a duplicate — refused with a flash error
        verify(siteLog, times(1)).audit(eq("acme"), anyString(), eq("permissions.role-created"), anyString());
    }

    @Test
    void aTenantSignOutIsJournaled() throws Exception {
        mvc.perform(post("/logout").session(admin()).with(csrf())).andExpect(status().is3xxRedirection());
        verify(siteLog).audit("acme", "admin", "logout", "");
    }
}
