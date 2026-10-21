package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** Tenant-console pages: the styled /login, the grouped dropdown topbar, the removed standalone Site-settings
 *  landing, and plugin per-tenant settings surfaced under the Settings menu. */
@SpringBootTest
class TenantConsolePagesTest {

    private static final String PW = "Tenant-Pages-Secret-9";
    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-tenantpages");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        users.save(layout.siteDir("acme").resolve("users.yml"), new User("acme-admin", "admin", hash, "Admin", "",
                true, false, Realm.TENANT, Set.of(Roles.ADMIN), List.of())); // wildcard tenant admin
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true, true,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        // A code-less plugin carrying a per-tenant settings schema → it surfaces under the console's Settings
        // dropdown (the replacement for the old standalone Site-settings page).
        Path plugin = Files.createDirectories(external.resolve("plugins").resolve("gadgets"));
        Files.writeString(plugin.resolve("plugin.yml"), """
                id: gadgets
                name: Gadgets
                version: 1.0.0
                permissions:
                  - id: settings
                    realm: tenant
                    defaultRoles: [OPERATOR]
                settings:
                  - { key: ai-default, type: enum, default: disallow, range: "allow,disallow" }
                  - { key: outbound, type: boolean, default: false }
                settingGroups:
                  - { id: ai, label: gadgets.g.ai }
                siteSettings:
                  - key: greeting
                    type: string
                  - { key: ai-policy, type: enum, default: inherit, range: "inherit,allow,disallow", group: ai, inheritsFrom: "app:ai-default" }
                  - { key: ping, type: boolean, default: false, group: ai, requiresPlatform: outbound }
                """);
        // A plugin with app-level settings only: its per-site page must not claim it has "no app-level settings".
        Path appOnly = Files.createDirectories(external.resolve("plugins").resolve("apponly"));
        Files.writeString(appOnly.resolve("plugin.yml"), """
                id: apponly
                name: App only
                version: 1.0.0
                settings:
                  - { key: x, type: string }
                """);
        // The same shape, but with RUNNING app-level values that differ from the declared defaults — the site form
        // must show these (what the plugin is actually using), not the defaults.
        Path running = Files.createDirectories(external.resolve("plugins").resolve("runners"));
        Files.writeString(running.resolve("plugin.yml"), """
                id: runners
                name: Runners
                version: 1.0.0
                settings:
                  - { key: ai-default, type: enum, default: disallow, range: "allow,disallow" }
                  - { key: outbound, type: boolean, default: false }
                siteSettings:
                  - { key: ai-policy, type: enum, default: inherit, range: "inherit,allow,disallow", inheritsFrom: "app:ai-default" }
                  - { key: ping, type: boolean, default: false, requiresPlatform: outbound }
                """);
        registry.add("runway.plugin.runners.ai-default", () -> "allow");
        registry.add("runway.plugin.runners.outbound", () -> "true");
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
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
    void theTenantLoginRendersStyledLikeTheAppLogin() throws Exception {
        mvc.perform(get("/login")) // unauthenticated; the site is bound by host (localhost -> acme)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/__assets/vendor/tabler.min.css")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("border-success"))) // tenant lime accent
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Acme"))); // the bound site's name as brand
    }

    @Test
    void theOldSiteSettingsLandingIsGoneAndSettingsIsNowADropdown() throws Exception {
        // The standalone /__admin/settings landing was removed; each plugin's per-tenant config now hangs off
        // the console's Settings dropdown instead.
        mvc.perform(get("/__admin/settings").session(sessionFor("admin")))
                .andExpect(status().isNotFound());
        String body = mvc.perform(get("/__admin").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("grp-settings"), "the Settings dropdown group is present");
        assertTrue(body.contains("/__admin/manifest"), "the split-out Web app manifest entry is present");
    }

    @Test
    void theAdminTopbarHasADynamicBadgeGroupedNavAndNoBrand() throws Exception {
        String body = mvc.perform(get("/__admin").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // the badge reads "<site id> site management"
        assertTrue(body.contains("acme site management"), "the realm badge is dynamic per site");
        // the "Runway" brand label span is gone
        assertFalse(body.contains("id=\"tb-brand-text\""), "the brand label was removed");
        // the flat list was reorganized into dropdown groups
        assertTrue(body.contains("grp-resources") && body.contains("grp-access")
                && body.contains("grp-activity") && body.contains("grp-settings"),
                "the nav is organized into Resources/Access/Activity/Settings dropdown groups");
        // the Access group (Permissions) precedes the Settings group (Backup & restore) in document order
        assertTrue(body.indexOf("/__admin/permissions") < body.indexOf("/__admin/backups"),
                "the Access group appears before the Settings group");
    }

    @Test
    void aPluginWithASiteSettingsSchemaAppearsUnderTheSettingsMenu() throws Exception {
        String body = mvc.perform(get("/__admin").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // no standalone menu link — the plugin's config is reached through Settings
        assertTrue(body.contains("/__admin/plugins/gadgets/settings"),
                "the plugin's per-tenant settings link is an entry in the Settings dropdown");
        assertTrue(body.contains(">Gadgets<"), "the entry is labelled with the plugin's name");
    }

    @Test
    void theSiteSettingsFormShowsWhatInheritMeansAndWhenThePlatformHasAFeatureOff() throws Exception {
        String body = mvc.perform(get("/__admin/plugins/gadgets/settings").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("name=\"greeting\""), "the ungrouped field renders");
        assertTrue(body.contains("st-sec-title"), "the declared group renders a heading");
        assertTrue(body.contains("inherit (currently: disallow)"),
                "inherit resolves to the plugin's running app-level value (its default here)");
        assertTrue(body.contains("sr-platform-off"), "the field whose platform switch is off says so");
    }

    @Test
    void aSitePageForAPluginWithNoSiteSettingsSaysPerSiteNotAppLevel() throws Exception {
        String body = mvc.perform(get("/__admin/plugins/apponly/settings").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("no per-site settings"), "the empty state names the right scope");
        assertFalse(body.contains("no app-level settings"));
    }

    @Test
    void theSiteFormShowsTheRunningPlatformValuesNotTheDeclaredDefaults() throws Exception {
        String body = mvc.perform(get("/__admin/plugins/runners/settings").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("inherit (currently: allow)"), "the running value, not the default 'disallow'");
        assertFalse(body.contains("sr-platform-off\""), "the platform switch is on, so no notice");
        assertFalse(body.matches("(?s).*class=\"[^\"]*sr-platform-off.*"), "no notice element at all");
    }
}
