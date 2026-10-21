package mx.quest.runway.console;

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
import java.util.Set;
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

/** The generic app-level plugin settings form: render by schema, gated save, typed validation, secret encryption. */
@SpringBootTest
class PluginSettingsFormTest {

    private static final String PASSWORD = "Set-tings-Secret-9";
    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-9f");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
        UserStore users = new UserStore(new YamlStore());
        Path file = external.resolve("app-users.yml");
        users.save(file, new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(file, new User("op-user", "operator", hash, "Operator", "o@x", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        // An operator granted ONLY the delegated plugin's own app-realm settings permission.
        users.save(file, new User("delegate-user", "delegate", hash, "Delegate", "d@x", true, false,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        mx.quest.runway.storage.StorageProperties storage = new mx.quest.runway.storage.StorageProperties();
        storage.setExternal(external);
        new mx.quest.runway.authz.GrantStore(mx.quest.runway.storage.StorageLayout.from(storage), new YamlStore())
                .saveAppGrants(mx.quest.runway.authz.GrantFile.empty()
                        .withUserPermission("delegate-user", "delegated|settings"));
        Path delegated = Files.createDirectories(external.resolve("plugins").resolve("delegated"));
        Files.writeString(delegated.resolve("plugin.yml"), """
                id: delegated
                name: Delegated
                version: 1.0.0
                permissions:
                  - { id: settings, realm: application, label: delegated.perm, defaultRoles: [] }
                settings:
                  - { key: level, type: string, default: low }
                """);
        Path demo = Files.createDirectories(external.resolve("plugins").resolve("demo"));
        Files.writeString(demo.resolve("plugin.yml"), """
                id: demo
                name: Demo
                version: 1.0.0
                settings:
                  - key: greeting
                    type: string
                    default: hi
                  - key: max
                    type: int
                    range: 1..100
                  - key: enabled
                    type: boolean
                  - key: token
                    type: secret
                """);
        // An APP-scoped admin panel (admin-app.html, NOT the tenant-oriented admin.html) — the app plugin-settings
        // page renders it below the generic form; the tenant page never would (realm isolation).
        Path demoTemplates = Files.createDirectories(demo.resolve("templates"));
        Files.writeString(demoTemplates.resolve("admin-app.html"),
                "<div id=\"demo-app-panel\" class=\"card mt-3\">DEMO-APP-PANEL-MARKER</div>");
        Files.writeString(demoTemplates.resolve("admin-app.th.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<thlogic></thlogic>");
        // A plugin using every settings-form presentation key (groups, help, advanced, showWhen, inheritsFrom,
        // requiresPlatform, helpUrl/helpAnchor) — rendered by the same shared form.
        Path grouped = Files.createDirectories(external.resolve("plugins").resolve("grouped"));
        Files.writeString(grouped.resolve("plugin.yml"), """
                id: grouped
                name: Grouped
                version: 1.0.0
                helpUrl: /__plugin/grouped/static/help.html
                settingGroups:
                  - { id: main, label: grouped.g.main, help: grouped.g.main.help, helpAnchor: main-section }
                  - { id: nobody, label: grouped.g.nobody }
                settings:
                  - { key: loose, type: string }
                  - { key: feature, type: boolean, default: false, group: main, help: grouped.h.feature }
                  - { key: feature.detail, type: string, group: main, showWhen: feature, helpAnchor: detail }
                  - { key: policy, type: enum, default: allow, range: "allow,disallow", group: main }
                  - { key: mode, type: enum, default: inherit, range: "inherit,allow,disallow", group: main, inheritsFrom: policy }
                  - { key: custom, type: text, group: main, advanced: true }
                  - { key: gated, type: string, group: main, requiresPlatform: feature }
                  - { key: hosts, type: list, default: "a,b" }
                """);
        // Three titled sections (one more group stays empty): the form opens with an index of the three.
        Path sectioned = Files.createDirectories(external.resolve("plugins").resolve("sectioned"));
        Files.writeString(sectioned.resolve("plugin.yml"), """
                id: sectioned
                name: Sectioned
                version: 1.0.0
                settingGroups:
                  - { id: one, label: sectioned.g.one }
                  - { id: empty, label: sectioned.g.empty }
                  - { id: two, label: sectioned.g.two }
                  - { id: three, label: sectioned.g.three }
                settings:
                  - { key: loose, type: string }
                  - { key: a, type: string, group: one }
                  - { key: b, type: string, group: two }
                  - { key: c, type: string, group: three }
                """);
        // A per-site setting inheriting an app-level one; one site sets its own value, one inherits.
        Path inheriting = Files.createDirectories(external.resolve("plugins").resolve("inheriting"));
        Files.writeString(inheriting.resolve("plugin.yml"), """
                id: inheriting
                name: Inheriting
                version: 1.0.0
                settings:
                  - { key: policy, type: enum, default: allow, range: "allow,disallow" }
                siteSettings:
                  - { key: own, type: enum, default: inherit, range: "inherit,allow,disallow", label: op.nav.about,
                      inheritsFrom: "app:policy" }
                """);
        mx.quest.runway.storage.StorageProperties sp = new mx.quest.runway.storage.StorageProperties();
        sp.setExternal(external);
        mx.quest.runway.storage.StorageLayout layout = mx.quest.runway.storage.StorageLayout.from(sp);
        mx.quest.runway.site.SiteStore siteStore = new mx.quest.runway.site.SiteStore(layout, new YamlStore());
        for (String id : List.of("own1", "own2", "inh")) {
            siteStore.save(new mx.quest.runway.site.Site(id, id, List.of(id + ".test"), java.util.Locale.ENGLISH,
                    List.of(java.util.Locale.ENGLISH), null, mx.quest.runway.site.SiteStatus.ACTIVE));
            Files.createDirectories(layout.sitePluginConfigFile(id, "inheriting").getParent());
            Files.writeString(layout.sitePluginConfigFile(id, "inheriting"),
                    id.startsWith("own") ? "own: disallow\n" : "own: inherit\n");
        }
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.security.secret-key", () -> "a-test-master-key-please-change-32b");
    }

    @Autowired
    private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // The external dir is shared across this class's tests; start each from a clean settings file.
        Files.deleteIfExists(external.resolve("plugin-settings").resolve("demo.yml"));
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PASSWORD)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void theFormRendersAnInputPerSettingForTheMaster() throws Exception {
        String html = mvc.perform(get("/__app/plugins/demo/settings").session(sessionFor("admin")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("name=\"greeting\""), "the string setting renders an input");
        assertTrue(html.contains("name=\"max\""), "the int setting renders an input");
        assertTrue(html.contains("name=\"enabled\""), "the boolean setting renders a checkbox");
        assertTrue(html.contains("name=\"token\""), "the secret setting renders an input");
        assertTrue(html.contains("not set"), "the unset secret shows its placeholder, never a value");
    }

    @Test
    void aPluginsOwnSettingsPermissionOpensItsAppSettingsAndNothingElse() throws Exception {
        MockHttpSession delegate = sessionFor("delegate");
        mvc.perform(get("/__app/plugins/delegated/settings").session(delegate)).andExpect(status().isOk());
        try {
            mvc.perform(post("/__app/plugins/delegated/settings").session(delegate).with(csrf()).param("level", "high"))
                    .andExpect(status().is3xxRedirection());
            assertTrue(Files.readString(external.resolve("plugin-settings").resolve("delegated.yml")).contains("high"),
                    "the holder can save it");
        } finally {
            Files.deleteIfExists(external.resolve("plugin-settings").resolve("delegated.yml"));
        }
        // ...but not another plugin's form, which still needs the master or a wildcard admin.
        mvc.perform(get("/__app/plugins/grouped/settings").session(delegate)).andExpect(status().isForbidden());
        // ...and an operator without the grant cannot open the delegated one.
        mvc.perform(get("/__app/plugins/delegated/settings").session(sessionFor("operator")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/__app/plugins/delegated/settings").session(sessionFor("operator")).with(csrf())
                .param("level", "x")).andExpect(status().isForbidden());
        // The master keeps access.
        mvc.perform(get("/__app/plugins/delegated/settings").session(sessionFor("admin"))).andExpect(status().isOk());
    }

    @Test
    void theAppFormSaysHowManySitesKeepTheirOwnValueForAnInheritedDefault() throws Exception {
        String html = mvc.perform(get("/__app/plugins/inheriting/settings").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("2 sites set its own value for \u201cAbout Runway\u201d"), html);
        String plain = mvc.perform(get("/__app/plugins/grouped/settings").session(sessionFor("admin")))
                .andReturn().getResponse().getContentAsString();
        assertFalse(plain.contains("id=\"st-overrides\""), "no note where no site overrides anything");
    }

    @Test
    void theAppSettingsPageRendersThePluginsAppPanel() throws Exception {
        // A plugin that ships templates/admin-app.html gets it rendered BELOW the generic form on its app
        // settings page — the seam the protection plugin uses for its immediate-apply login-protection panel.
        String html = mvc.perform(get("/__app/plugins/demo/settings").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("DEMO-APP-PANEL-MARKER"), "the plugin's app admin-app panel renders on its page");
    }

    @Test
    void theAppPanelIsRealmScopedToTheAppConsole(@Autowired mx.quest.runway.plugin.PluginAdminService plugins) {
        // The APP panel keys off admin-app.html; the tenant-oriented admin.html is absent, so a per-tenant route
        // would render NO fragment for this plugin — app-level config can never leak into a tenant console.
        assertTrue(plugins.hasAppAdminFragment("demo"), "the app-scoped fragment is detected");
        assertFalse(plugins.hasAdminFragment("demo"), "no tenant-scoped fragment ⇒ the tenant page shows none");
    }

    @Test
    void aNonMasterOperatorIsDenied() throws Exception {
        MockHttpSession op = sessionFor("operator");
        mvc.perform(get("/__app/plugins/demo/settings").session(op)).andExpect(status().isForbidden());
        mvc.perform(post("/__app/plugins/demo/settings").session(op).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void savingWritesTypedValuesAndEncryptsTheSecret() throws Exception {
        mvc.perform(post("/__app/plugins/demo/settings").session(sessionFor("admin")).with(csrf())
                        .param("greeting", "hello").param("max", "50").param("enabled", "true").param("token", "s3cr3t"))
                .andExpect(status().is3xxRedirection());

        String saved = Files.readString(external.resolve("plugin-settings/demo.yml"));
        assertTrue(saved.contains("hello"), "the string value was written");
        assertTrue(saved.contains("50"), "the int value was written");
        assertTrue(saved.contains("ENC("), "the secret was stored encrypted");
        assertFalse(saved.contains("s3cr3t"), "the plaintext secret is never written");
    }

    @Test
    void anOutOfRangeIntIsAFieldErrorNotASave() throws Exception {
        mvc.perform(post("/__app/plugins/demo/settings").session(sessionFor("admin")).with(csrf())
                        .param("max", "500")) // range is 1..100
                .andExpect(status().is3xxRedirection());
        boolean wroteBadValue = Files.exists(external.resolve("plugin-settings/demo.yml"))
                && Files.readString(external.resolve("plugin-settings/demo.yml")).contains("500");
        assertFalse(wroteBadValue, "a rejected value is never persisted");
    }

    @Test
    void aBlankSecretOnResaveKeepsTheStoredOne() throws Exception {
        MockHttpSession master = sessionFor("admin");
        mvc.perform(post("/__app/plugins/demo/settings").session(master).with(csrf())
                .param("greeting", "hi").param("max", "10").param("enabled", "false").param("token", "first-secret"))
                .andExpect(status().is3xxRedirection());
        String afterFirst = Files.readString(external.resolve("plugin-settings/demo.yml"));

        // Re-save with a blank token: the stored (encrypted) secret must survive.
        mvc.perform(post("/__app/plugins/demo/settings").session(master).with(csrf())
                .param("greeting", "hi").param("max", "10").param("enabled", "false").param("token", ""))
                .andExpect(status().is3xxRedirection());
        assertTrue(Files.readString(external.resolve("plugin-settings/demo.yml")).contains("ENC("),
                "the blank-on-resave secret was kept");
        assertTrue(afterFirst.contains("ENC("));
    }

    @Test
    void thePresentationKeysShapeTheRenderedForm() throws Exception {
        String html = mvc.perform(get("/__app/plugins/grouped/settings").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("st-sec-title"), "a declared group renders a section heading");
        assertFalse(html.contains("grouped.g.nobody"), "a group with no fields renders nothing");
        assertTrue(html.contains("href=\"/__plugin/grouped/static/help.html#main-section\""), "section link");
        assertTrue(html.contains("href=\"/__plugin/grouped/static/help.html#detail\""), "field link");
        assertTrue(html.contains("sr-help"), "a field with help renders its help line");
        assertTrue(html.matches("(?s).*data-show-when=\"feature\"[^>]*hidden.*|.*hidden[^>]*data-show-when=\"feature\".*"),
                "the dependent row starts hidden while its switch is off");
        assertTrue(html.matches("(?s).*<select[^>]*name=\"policy\"[^>]*>\\s*<option[^>]*value=\"allow\".*"),
                "the ranged enum `policy` itself is a select, with its choices");
        assertTrue(html.contains("inherit (currently: allow)"), "inherit says what it resolves to");
        assertTrue(html.contains("<details"), "the advanced field sits in a disclosure");
        assertFalse(html.matches("(?s).*<details[^>]*\\sopen[\\s=>].*"), "closed while the advanced field is unset");
        assertTrue(html.contains("sr-platform-off"), "the field needing an off switch says so");
        assertTrue(html.contains("One per line"), "a list field explains its format, localized");
        assertTrue(html.contains("Default: a,b"), "the default is shown in words, not as a type summary");
        assertFalse(html.contains("boolean · default"), "no raw type summaries on this form");
        assertTrue(html.contains("name=\"loose\""), "an ungrouped field still renders");
    }

    @Test
    void eachSectionIsACardAndALongFormOpensWithAnIndexOfItsSections() throws Exception {
        String html = mvc.perform(get("/__app/plugins/sectioned/settings").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("id=\"st-index\""), "three titled sections get an index");
        assertTrue(html.contains("Jump to a section"), "the index is labelled, localized");
        // Section 0 is the untitled one (ungrouped `loose`); the empty group renders nothing and takes no index.
        for (int i = 1; i <= 3; i++) {
            assertTrue(html.contains("href=\"#st-section-" + i + "\""), "index link " + i);
            assertTrue(html.matches("(?s).*<section[^>]*id=\"st-section-" + i + "\".*"), "its target exists " + i);
        }
        assertFalse(html.contains("href=\"#st-section-0\""), "the untitled section is not indexed");
        assertFalse(html.contains("href=\"#st-section-4\""), "nothing beyond the rendered sections");
        assertFalse(html.contains("sectioned.g.empty"), "a group with no fields is neither shown nor indexed");
        assertTrue(html.matches("(?s).*<section[^>]*class=\"st-section card[^\"]*\".*"), "each section is a card");
        assertTrue(html.matches("(?s).*class=\"card-header st-sec-head\".*"), "its title sits in the card header");

        String short_ = mvc.perform(get("/__app/plugins/grouped/settings").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(short_.contains("id=\"st-index\""), "one titled section needs no index");
    }

    @Test
    void theAdvancedDisclosureRendersOpenOnceOneOfItsFieldsIsSet() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        try {
            mvc.perform(post("/__app/plugins/grouped/settings").session(admin).with(csrf())
                    .param("custom", "User-agent: *")).andExpect(status().is3xxRedirection());
            String html = mvc.perform(get("/__app/plugins/grouped/settings").session(admin))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(html.matches("(?s).*<details[^>]*\\sopen[\\s=>].*"),
                    "a set advanced field is never tucked out of sight");
        } finally {
            Files.deleteIfExists(external.resolve("plugin-settings").resolve("grouped.yml"));
        }
    }
}
