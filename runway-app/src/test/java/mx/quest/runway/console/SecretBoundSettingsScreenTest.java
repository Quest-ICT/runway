package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.crypto.SecretCipher;
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
 * End to end on the site console: a user granted only {@code <plugin>|settings} sees
 * no stored SMTP password, and repointing the host with the password left blank no longer keeps it — the save
 * drops it and the page says it must be entered again. The plugin is a fake descriptor shaped like a contact-form plugin's
 * SMTP settings, declaring {@code secretBoundTo: [smtpHost, smtpPort, smtpUsername]}.
 */
@SpringBootTest
class SecretBoundSettingsScreenTest {

    private static final String PW = "Bound-Secret-Screen-9";
    private static final String SMTP_PASSWORD = "TOPSECRET-123";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-secret-bound");
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
        users.save(tenantUsers, new User("acme-setter", "setter", hash, "Setter", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true, true,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        Path dir = Files.createDirectories(external.resolve("plugins").resolve("mailer"));
        Files.writeString(dir.resolve("plugin.yml"), """
                id: mailer
                name: Mailer
                version: 1.0.0
                permissions:
                  - { id: settings, realm: tenant, defaultRoles: [OPERATOR] }
                siteSettings:
                  - { key: smtpHost, type: string }
                  - { key: smtpPort, type: int, default: 587 }
                  - { key: smtpUsername, type: string }
                  - key: smtpPassword
                    type: secret
                    secretBoundTo: [smtpHost, smtpPort, smtpUsername]
                  - { key: fromAddress, type: string }
                """);
        registry.add("runway.storage.external", external::toString);
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.security.secret-key", () -> "secret-bound-test-master-key-0123456789");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SitePluginConfigService config;
    @Autowired
    private SecretCipher cipher;
    @Autowired
    private SiteResolver siteResolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void ownerSaves(String host) throws Exception {
        mvc.perform(post("/__admin/plugins/mailer/settings").session(sessionFor("admin")).with(csrf())
                .param("smtpHost", host).param("smtpPort", "587").param("smtpUsername", "u")
                .param("smtpPassword", SMTP_PASSWORD).param("fromAddress", "site@example.com"))
                .andExpect(status().is3xxRedirection());
        assertEquals(SMTP_PASSWORD, cipher.decrypt(String.valueOf(config.config("acme", "mailer").get("smtpPassword"))));
    }

    @Test
    void aSettingsHolderRepointingTheHostCannotKeepTheStoredPassword() throws Exception {
        ownerSaves("mail.example.com");
        MockHttpSession setter = sessionFor("setter");
        String form = mvc.perform(get("/__admin/plugins/mailer/settings").session(setter))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(form.contains(SMTP_PASSWORD), "the form never shows the secret");

        MvcResult saved = mvc.perform(post("/__admin/plugins/mailer/settings").session(setter).with(csrf())
                        .param("smtpHost", "127.0.0.1").param("smtpPort", "2525").param("smtpUsername", "u")
                        .param("smtpPassword", "").param("fromAddress", "site@example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("secretsDropped", "smtpPassword")).andReturn();
        assertNull(config.config("acme", "mailer").get("smtpPassword"),
                "no stored password is left for the plugin to send to 127.0.0.1");

        // The redirected page tells the user, in their language bundle, that it must be entered again.
        String page = mvc.perform(get("/__admin/plugins/mailer/settings").session(setter)
                        .flashAttrs(saved.getFlashMap()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("The stored value of smtpPassword was removed"), "the warning is shown");
    }

    @Test
    void anUnrelatedChangeByTheSameHolderKeepsThePassword() throws Exception {
        ownerSaves("mail.example.com");
        mvc.perform(post("/__admin/plugins/mailer/settings").session(sessionFor("setter")).with(csrf())
                        .param("smtpHost", "mail.example.com").param("smtpPort", "587").param("smtpUsername", "u")
                        .param("smtpPassword", "").param("fromAddress", "news@example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeCount(1));
        assertEquals(SMTP_PASSWORD, cipher.decrypt(String.valueOf(config.config("acme", "mailer").get("smtpPassword"))));
        assertEquals("news@example.com", config.config("acme", "mailer").get("fromAddress"));
    }
}
