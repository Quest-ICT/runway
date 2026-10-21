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
import mx.quest.runway.platform.PlatformSettings;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
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

/**
 * The app-realm Platform Settings screen: render, save the default site + password strength + node name +
 * compression toggles, validation, gating — and that the compression toggles reach the live filter.
 */
@SpringBootTest
class AppSettingsScreenTest {

    private static final String PASSWORD = "App-Settings-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-appsettings");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
        UserStore users = new UserStore(new YamlStore());
        Path file = external.resolve("app-users.yml");
        users.save(file, new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(file, new User("op-user", "operator", hash, "Operator", "o@x", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        // Arm the secret cipher so a saved SMTP password can be ENC(...)-encrypted at rest.
        registry.add("runway.security.secret-key", () -> "app-settings-test-master-key-0123456789");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private PlatformSettingsStore platform;
    @Autowired
    private SiteStore siteStore;
    @Autowired
    private mx.quest.runway.web.CompressionFilter compression;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        platform.update(previous -> PlatformSettings.EMPTY); // isolate each test from the shared external dir
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PASSWORD)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void saveSite(String id) {
        siteStore.save(new Site(id, id, List.of(), Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
    }

    @Test
    void theScreenRendersTheForm() throws Exception {
        mvc.perform(get("/__app/settings").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("action=\"/__app/settings\"")))
                .andExpect(content().string(Matchers.containsString("name=\"defaultSiteId\"")))
                .andExpect(content().string(Matchers.containsString("name=\"passwordStrength\"")));
    }

    @Test
    void savingADefaultSitePersistsItLive() throws Exception {
        saveSite("acme");
        mvc.perform(post("/__app/settings").session(sessionFor("admin")).with(csrf())
                        .param("defaultSiteId", "acme").param("passwordStrength", ""))
                .andExpect(status().is3xxRedirection());
        assertEquals("acme", platform.get().defaultSite().orElseThrow(), "the default site is saved");
    }

    @Test
    void anUnknownDefaultSiteIsRejectedAndNothingChanges() throws Exception {
        mvc.perform(post("/__app/settings").session(sessionFor("admin")).with(csrf())
                        .param("defaultSiteId", "ghost").param("passwordStrength", ""))
                .andExpect(status().is3xxRedirection());
        assertTrue(platform.get().defaultSite().isEmpty(), "an invalid site never becomes the default");
    }

    @Test
    void savingTheScreenKeepsTheSitesStorageCeilings() throws Exception {
        platform.update(current -> current.withSiteStorageCeiling("acme", 5L << 30));
        mvc.perform(post("/__app/settings").session(sessionFor("admin")).with(csrf())
                        .param("defaultSiteId", "").param("passwordStrength", ""))
                .andExpect(status().is3xxRedirection());
        assertEquals(5L << 30, platform.get().siteStorageCeilings().get("acme"),
                "a field this screen does not own is carried over, not dropped");
    }

    @Test
    void savingAPasswordStrengthPersistsIt() throws Exception {
        mvc.perform(post("/__app/settings").session(sessionFor("admin")).with(csrf())
                        .param("defaultSiteId", "").param("passwordStrength", "HIGH"))
                .andExpect(status().is3xxRedirection());
        assertEquals("HIGH", platform.get().strength().orElseThrow(), "the password strength is saved");
    }

    @Test
    void theFormExposesTheNodeNameAndCompressionFields() throws Exception {
        mvc.perform(get("/__app/settings").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("name=\"nodeName\"")))
                .andExpect(content().string(Matchers.containsString("name=\"compressionEnabled\"")))
                .andExpect(content().string(Matchers.containsString("name=\"brotliEnabled\"")))
                .andExpect(content().string(Matchers.containsString("name=\"gzipEnabled\"")));
    }

    @Test
    void savingANodeNamePersistsIt() throws Exception {
        mvc.perform(post("/__app/settings").session(sessionFor("admin")).with(csrf())
                        .param("defaultSiteId", "").param("passwordStrength", "")
                        .param("nodeName", " edge-01 ").param("compressionEnabled", "true").param("gzipEnabled", "true"))
                .andExpect(status().is3xxRedirection());
        assertEquals("edge-01", platform.get().node().orElseThrow(), "the node name is saved (trimmed)");
    }

    @Test
    void savingCompressionTogglesPersistsThemAndTheLiveFilterReflectsThem() throws Exception {
        // No checkbox params → all three flags submit false: compression fully off.
        mvc.perform(post("/__app/settings").session(sessionFor("admin")).with(csrf())
                        .param("defaultSiteId", "").param("passwordStrength", ""))
                .andExpect(status().is3xxRedirection());
        assertEquals(Boolean.FALSE, platform.get().compressionEnabled(), "compression override persisted off");
        assertTrue(!compression.enabled(), "the live filter reads compression off");

        // Compression on, brotli off, gzip on.
        mvc.perform(post("/__app/settings").session(sessionFor("admin")).with(csrf())
                        .param("defaultSiteId", "").param("passwordStrength", "")
                        .param("compressionEnabled", "true").param("gzipEnabled", "true"))
                .andExpect(status().is3xxRedirection());
        assertTrue(compression.enabled(), "the live filter reads compression on");
        assertTrue(compression.gzipOn(), "gzip on");
        assertTrue(!compression.brotliOn(), "brotli off (checkbox absent → false)");
    }

    @Test
    void aNonMasterOperatorIsForbidden() throws Exception {
        mvc.perform(get("/__app/settings").session(sessionFor("operator")))
                .andExpect(status().isForbidden());
    }

    // --- Platform mail (SMTP) --------------------------------------------------------------------------

    @Test
    void theFormExposesTheSmtpFieldsAndTheTestAction() throws Exception {
        mvc.perform(get("/__app/settings").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("name=\"smtpHost\"")))
                .andExpect(content().string(Matchers.containsString("name=\"smtpPort\"")))
                .andExpect(content().string(Matchers.containsString("name=\"smtpFrom\"")))
                .andExpect(content().string(Matchers.containsString("name=\"smtpPassword\"")))
                .andExpect(content().string(Matchers.containsString("action=\"/__app/settings/test-email\"")));
    }

    @Test
    void savingSmtpPersistsItAndEncryptsThePassword() throws Exception {
        mvc.perform(post("/__app/settings").session(sessionFor("admin")).with(csrf())
                        .param("defaultSiteId", "").param("passwordStrength", "")
                        .param("smtpHost", "smtp.example.com").param("smtpPort", "2525")
                        .param("smtpFrom", "noreply@example.com")
                        .param("smtpAuth", "true").param("smtpUsername", "postmaster")
                        .param("smtpPassword", "s3cr3t").param("smtpStarttls", "true"))
                .andExpect(status().is3xxRedirection());

        PlatformSettings.Smtp smtp = platform.get().smtp();
        assertEquals("smtp.example.com", smtp.host());
        assertEquals(2525, smtp.port());
        assertEquals("noreply@example.com", smtp.from());
        assertTrue(smtp.auth());
        assertEquals("postmaster", smtp.username());
        assertTrue(smtp.password().startsWith("ENC("), "the password is stored encrypted at rest");
        assertFalse(smtp.password().contains("s3cr3t"), "the plaintext password is never stored");
        assertTrue(smtp.isConfigured());
    }

    @Test
    void aBlankPasswordOnResavePreservesTheStoredSecret() throws Exception {
        mvc.perform(post("/__app/settings").session(sessionFor("admin")).with(csrf())
                        .param("defaultSiteId", "").param("passwordStrength", "")
                        .param("smtpHost", "smtp.example.com").param("smtpFrom", "noreply@example.com")
                        .param("smtpAuth", "true").param("smtpUsername", "postmaster")
                        .param("smtpPassword", "original"))
                .andExpect(status().is3xxRedirection());
        String stored = platform.get().smtp().password();

        // Re-save with the password field BLANK and a changed host: the stored secret must be kept.
        mvc.perform(post("/__app/settings").session(sessionFor("admin")).with(csrf())
                        .param("defaultSiteId", "").param("passwordStrength", "")
                        .param("smtpHost", "smtp2.example.com").param("smtpFrom", "noreply@example.com")
                        .param("smtpAuth", "true").param("smtpUsername", "postmaster")
                        .param("smtpPassword", ""))
                .andExpect(status().is3xxRedirection());
        PlatformSettings.Smtp after = platform.get().smtp();
        assertEquals(stored, after.password(), "a blank password field keeps the stored secret");
        assertEquals("smtp2.example.com", after.host(), "the other fields still update");
    }

    @Test
    void theGetMasksTheSmtpPassword() throws Exception {
        mvc.perform(post("/__app/settings").session(sessionFor("admin")).with(csrf())
                        .param("defaultSiteId", "").param("passwordStrength", "")
                        .param("smtpHost", "smtp.example.com").param("smtpFrom", "noreply@example.com")
                        .param("smtpAuth", "true").param("smtpUsername", "postmaster")
                        .param("smtpPassword", "top-secret-value"))
                .andExpect(status().is3xxRedirection());

        mvc.perform(get("/__app/settings").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("top-secret-value"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("ENC("))));
    }

    @Test
    void aTestEmailWithoutAConfiguredAccountRedirectsWithoutSending() throws Exception {
        // Nothing configured (EMPTY seeded in setUp): the test action must not 500 — it redirects with an error.
        mvc.perform(post("/__app/settings/test-email").session(sessionFor("admin")).with(csrf())
                        .param("testTo", "someone@example.com"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void aNonMasterOperatorCannotSendATestEmail() throws Exception {
        mvc.perform(post("/__app/settings/test-email").session(sessionFor("operator")).with(csrf())
                        .param("testTo", "someone@example.com"))
                .andExpect(status().isForbidden());
    }

    // --- Clear template cache -------------------------------------------------------------------------

    @Autowired
    private org.thymeleaf.TemplateEngine templateEngine;
    @Autowired
    private mx.quest.runway.log.AppActivityLog activityLog;

    private int cachedTemplates() {
        return templateEngine.getConfiguration().getCacheManager().getTemplateCache().keySet().size();
    }

    @Test
    void theScreenOffersTheClearTemplateCacheAction() throws Exception {
        mvc.perform(get("/__app/settings").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("action=\"/__app/settings/clear-template-cache\"")))
                .andExpect(content().string(Matchers.containsString("Clear template cache")));
    }

    @Test
    void clearingTheTemplateCacheEmptiesItRecordsItAndFlashes() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(get("/__app/settings").session(admin)).andExpect(status().isOk()); // warms the cache
        assertTrue(cachedTemplates() > 0, "rendering the screen cached its parsed templates");

        mvc.perform(post("/__app/settings/clear-template-cache").session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
                        "/__app/settings"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("flash", "Template cache cleared on this node."));
        assertEquals(0, cachedTemplates(), "the parsed-template cache is empty");
        assertTrue(activityLog.tail(20).stream().anyMatch(line -> line.contains("platform.template-cache-cleared")
                && line.contains("admin")), "the action is recorded in the activity log with its actor");
    }

    @Test
    void aNonMasterOperatorCannotClearTheTemplateCache() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(get("/__app/settings").session(admin)).andExpect(status().isOk()); // warms the cache
        int before = cachedTemplates();
        mvc.perform(post("/__app/settings/clear-template-cache").session(sessionFor("operator")).with(csrf()))
                .andExpect(status().isForbidden());
        assertEquals(before, cachedTemplates(), "a refused clear leaves the cache intact");
    }

    @Test
    void clearingTheTemplateCacheWithoutCsrfIsRefused() throws Exception {
        mvc.perform(post("/__app/settings/clear-template-cache").session(sessionFor("admin")))
                .andExpect(status().isForbidden());
    }
}
