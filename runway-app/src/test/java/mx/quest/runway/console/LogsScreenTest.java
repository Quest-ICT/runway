package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.log.SiteLogConfigService;
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
import org.awaitility.Awaitility;
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
 * The tenant Logs screen: render, save settings, and site-admin gating; plus the parsed,
 * filtered audit journal, its CSV export (same gate), and the journaling of a log-settings change.
 */
@SpringBootTest
class LogsScreenTest {

    private static final String PW = "Logs-Screen-7";
    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-6b");
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
                Set.of(Roles.ADMIN), List.of()));      // a tenant admin
        users.save(siteUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));    // not a tenant admin
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
    private SiteLogConfigService logConfigs;
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
    void theScreenRendersAccessAuditAndSettings() throws Exception {
        mvc.perform(get("/__admin/logs").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("id=\"lg-access\"")))
                .andExpect(content().string(Matchers.containsString("id=\"aj-card\"")))
                .andExpect(content().string(Matchers.containsString("data-listing-table=\"runway.site.audit\"")))
                .andExpect(content().string(Matchers.containsString("name=\"retentionDays\"")));
    }

    @Test
    void savingSettingsPersistsAnonymizeAndRetention() throws Exception {
        // Post with the anonymize checkbox unchecked (absent) and a retention value.
        mvc.perform(post("/__admin/logs").session(sessionFor("admin")).with(csrf())
                        .param("retentionDays", "14"))
                .andExpect(status().is3xxRedirection());
        assertFalse(logConfigs.get("acme").anonymize(), "unchecked box turned anonymization off");
        assertEquals(14, logConfigs.get("acme").effectiveRetentionDays(90));
    }

    @Test
    void aRetentionAboveThePlatformsMaximumIsRefusedNotClampedSilently() throws Exception {
        mvc.perform(post("/__admin/logs").session(sessionFor("admin")).with(csrf()).param("retentionDays", "14"));
        mvc.perform(post("/__admin/logs").session(sessionFor("admin")).with(csrf()).param("retentionDays", "200"))
                .andExpect(flash().attributeExists("error"));
        assertEquals(14, logConfigs.get("acme").retentionDays(), "nothing saved");
    }

    @Test
    void aSiteAboveALoweredMaximumIsToldSo() throws Exception {
        // As when the operator lowered the maximum after this site chose 200 days.
        logConfigs.save("acme", new mx.quest.runway.log.SiteLogConfig(true, 200));
        try {
            String html = mvc.perform(get("/__admin/logs").session(sessionFor("admin")))
                    .andReturn().getResponse().getContentAsString();
            assertTrue(html.contains("set to keep logs 200 days") && html.contains("at most 90"), html);
        } finally {
            logConfigs.save("acme", new mx.quest.runway.log.SiteLogConfig(true, 30));
        }
    }

    @Test
    void anOperatorWhoIsNotASiteAdminCannotSeeTheLogs() throws Exception {
        mvc.perform(get("/__admin/logs").session(sessionFor("op")))
                .andExpect(status().isForbidden());
    }

    private static Path auditDay(LocalDate day) throws IOException {
        Path dir = Files.createDirectories(external.resolve("sites").resolve("acme").resolve("logs"));
        return dir.resolve("audit-" + day + ".log");
    }

    private static void appendAudit(LocalDate day, String text) throws IOException {
        Files.writeString(auditDay(day), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    @Test
    void theAuditJournalIsParsedLabelledAndFilterable() throws Exception {
        LocalDate day = LocalDate.now().minusDays(1);
        appendAudit(day, day + "T09:00:00.000-06:00\tjournal-ed\tpage.published\thome-page\n"
                + day + " 09:05:00\tjournal-other\tuser.created\tcarol (OPERATOR)\n");
        String html = mvc.perform(get("/__admin/logs").param("actor", "journal-ed").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Page published") && html.contains("home-page"), "parsed + labelled");
        assertFalse(html.contains("carol (OPERATOR)"), "the other actor is filtered out");
    }

    @Test
    void theAuditCsvIsGatedLikeTheScreen() throws Exception {
        LocalDate day = LocalDate.now().minusDays(1);
        appendAudit(day, day + "T09:00:00.000-06:00\tcsv-ed\tpage.deleted\t@evil\n");
        String body = mvc.perform(get("/__admin/logs/audit.csv").param("actor", "csv-ed").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"csv-ed\",\"page.deleted\",\"Page deleted\",\"'@evil\""), body);
        mvc.perform(get("/__admin/logs/audit.csv").session(sessionFor("op"))).andExpect(status().isForbidden());
    }

    @Test
    void aLogSettingsChangeIsJournaled() throws Exception {
        mvc.perform(post("/__admin/logs").session(sessionFor("admin")).with(csrf())
                        .param("anonymize", "true").param("retentionDays", "21"))
                .andExpect(status().is3xxRedirection());
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(auditDay(LocalDate.now()))
                && Files.readString(auditDay(LocalDate.now())).contains("logs.settings-changed"));
        String journal = Files.readString(auditDay(LocalDate.now()));
        assertTrue(journal.contains("admin\tlogs.settings-changed\tanonymize=true retentionDays=21"), journal);
    }
}
