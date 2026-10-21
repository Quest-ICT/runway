package mx.quest.runway.console;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.authz.GrantFile;
import mx.quest.runway.authz.GrantStore;
import mx.quest.runway.log.SiteLogService;
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

/**
 * The tenant <b>Site insights</b> screen end to end: it renders the site's own 404s and page views from its
 * access day-files, escapes visitor-controlled paths, never shows another site's data, tolerates a bad period
 * parameter, and is gated by {@code VIEW_SITE_INSIGHTS} (admin yes, plain operator 403, operator with the grant
 * yes).
 */
@SpringBootTest
class SiteInsightsScreenTest {

    private static final String PW = "Insights-Screen-7";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-insights");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        sites.save(new Site("acme", "Acme", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        sites.save(new Site("beta", "Beta", List.of("beta.test"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        Path siteUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(siteUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));      // wildcard tier: passes every tenant permission
        users.save(siteUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));    // no VIEW_SITE_INSIGHTS by default
        users.save(siteUsers, new User("acme-analyst", "analyst", hash, "Analyst", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));    // granted VIEW_SITE_INSIGHTS directly below
        new GrantStore(layout, yaml).saveSiteGrants("acme",
                GrantFile.empty().withUserPermission("acme-analyst", "VIEW_SITE_INSIGHTS"));
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));

        LocalDate today = LocalDate.now(SiteLogService.zone());
        writeLog(layout, "acme", today,
                today + " 08:00:00\t203.0.113.0\tGET\t/pricing\t200",
                today + " 08:01:00\t203.0.113.0\tGET\t/pricing\t200",
                today + " 08:02:00\t203.0.113.0\tGET\t/old-landing\t404",
                today + " 08:03:00\t203.0.113.0\tGET\t/<script>alert(1)</script>\t404",
                today + " 08:04:00\t203.0.113.0\tGET\t/" + "z".repeat(300) + "\t404");
        writeLog(layout, "beta", today, today + " 08:00:00\t198.51.100.0\tGET\t/beta-only-marker\t404");
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    private static void writeLog(StorageLayout layout, String siteId, LocalDate day, String... lines)
            throws IOException {
        Path dir = layout.siteDir(siteId).resolve("logs");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("access-" + day + ".log"), String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
    }

    @Autowired
    private WebApplicationContext context;
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
        MvcResult r = mvc.perform(formLogin("/login").user(username).password(PW)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void theScreenShowsThisSitesTopPagesAnd404s() throws Exception {
        mvc.perform(get("/__admin/insights").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Site insights")))
                .andExpect(content().string(Matchers.containsString("/pricing")))
                .andExpect(content().string(Matchers.containsString("/old-landing")))
                .andExpect(content().string(Matchers.containsString(
                        "data-listing-table=\"runway.admin.insights.pages\"")))
                .andExpect(content().string(Matchers.containsString(
                        "data-listing-table=\"runway.admin.insights.notfound\"")))
                .andExpect(content().string(Matchers.containsString("href=\"/__admin/insights\"")))
                .andExpect(content().string(Matchers.containsString("href=\"?days=30\"")));
    }

    @Test
    void aVisitorControlledPathIsEscapedNeverRendered() throws Exception {
        mvc.perform(get("/__admin/insights").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(Matchers.containsString("&lt;script&gt;alert(1)&lt;/script&gt;")));
    }

    @Test
    void aLongPathIsAbbreviatedForDisplay() throws Exception {
        mvc.perform(get("/__admin/insights").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString(">/" + "z".repeat(300) + "<"))))
                .andExpect(content().string(Matchers.containsString("/" + "z".repeat(96) + "...")));
    }

    @Test
    void anotherSitesLogsNeverAppear() throws Exception {
        mvc.perform(get("/__admin/insights").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("beta-only-marker"))));
        // A request parameter cannot redirect the report to another site.
        mvc.perform(get("/__admin/insights").param("site", "beta").param("siteId", "beta")
                        .session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("beta-only-marker"))));
    }

    @Test
    void aBadPeriodFallsBackToTheDefaultInsteadOfFailing() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        for (String days : List.of("abc", "-1", "99999", "365", "")) {
            mvc.perform(get("/__admin/insights").param("days", days).session(admin))
                    .andExpect(status().isOk())
                    .andExpect(content().string(Matchers.containsString("Last 7 days")));
        }
        mvc.perform(get("/__admin/insights").param("days", "30").session(admin))
                .andExpect(status().isOk());
    }

    @Test
    void anOperatorWithoutThePermissionIsForbidden() throws Exception {
        mvc.perform(get("/__admin/insights").session(sessionFor("op")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOperatorGrantedThePermissionCanSeeTheReport() throws Exception {
        mvc.perform(get("/__admin/insights").session(sessionFor("analyst")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/old-landing")));
    }

    @Test
    void anAnonymousVisitorIsSentToLogin() throws Exception {
        mvc.perform(get("/__admin/insights"))
                .andExpect(status().is3xxRedirection());
    }
}
