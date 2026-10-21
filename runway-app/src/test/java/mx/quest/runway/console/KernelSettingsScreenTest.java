package mx.quest.runway.console;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.quest.runway.backup.BackupProperties;
import mx.quest.runway.log.LogProperties;
import mx.quest.runway.platform.HostSettings;
import mx.quest.runway.platform.KernelSettings;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.RunwayCspProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The kernel's own settings in the console (Settings → Server settings and the policy cards): what the console saved
 * binds at startup like any other value; the host's value wins and shows locked; only differences are stored; a value
 * that would stop the server from starting is refused.
 */
@SpringBootTest
class KernelSettingsScreenTest {

    private static final String PASSWORD = "Kernel-Settings-Secret-9";
    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-kernel-settings");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
        UserStore users = new UserStore(new YamlStore());
        Path file = external.resolve("app-users.yml");
        users.save(file, new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(file, new User("op-user", "operator", hash, "Operator", "o@x", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        // What the console saved before this start: it must bind. One undeclared key must not.
        Files.writeString(external.resolve("platform.yml"), """
                settings:
                  runway.csp.mode: enforce
                  runway.logs.max-retention-days: "30"
                  runway.backups.retention.max-count: "5"
                  runway.security.app-master-id: hijacked
                  server.port: "1"
                  runway.http-cache.immutable-extensions: "png"
                """);
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.security.secret-key", () -> "kernel-settings-test-master-key-0123456789");
        // Set "on the host": ranks above the console's value, so it wins and locks the field.
        registry.add("runway.backups.retention.max-count", () -> "12");
        // A list the host sets as a YAML list: indexed keys only. The console's stale "png" above must not show.
        registry.add("runway.http-cache.immutable-extensions[0]", () -> "woff2");
        registry.add("runway.http-cache.immutable-extensions[1]", () -> "ttf");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private PlatformSettingsStore platform;
    @Autowired
    private Environment environment;
    @Autowired
    private HostSettings host;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PASSWORD)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void whatTheConsoleSavedBindsAtStartupAndOnlyDeclaredKeysDo(@Autowired RunwayCspProperties csp,
                                                                @Autowired LogProperties logs) {
        assertEquals(RunwayCspProperties.Mode.ENFORCE, csp.getMode());
        assertEquals(30, logs.getMaxRetentionDays());
        assertEquals("app-master", environment.getProperty("runway.security.app-master-id"),
                "an undeclared key in the file is ignored");
        assertFalse("1".equals(environment.getProperty("server.port")), "so is a framework key it does not declare");
    }

    @Test
    void theHostsValueWinsAndTheFieldIsLocked(@Autowired BackupProperties backups) throws Exception {
        assertEquals(12, backups.getRetention().getMaxCount(), "the host's 12 beats the console's 5");
        assertTrue(host.setOnHost("runway.backups.retention.max-count"));
        assertFalse(host.setOnHost("runway.csp.mode"), "a value only the console set is not the host's");
        String html = mvc.perform(get("/__app/config/backups").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.matches("(?s).*name=\"runway.backups.retention.max-count\"[^>]*disabled.*"), "disabled");
        assertTrue(html.contains("Set on the server in"), "and says where");
    }

    @Test
    void theServerSettingsPageHasItsSectionsAndAnIndex() throws Exception {
        String html = mvc.perform(get("/__app/config/server").session(sessionFor("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Server settings"), "named");
        assertTrue(html.contains("Content-Security-Policy") && html.contains("Request limits")
                && html.contains("Reverse proxy"), "its sections");
        assertTrue(html.contains("id=\"st-index\""), "with the section index");
        assertTrue(html.contains("name=\"runway.csp.mode\""), "and the fields");
    }

    @Test
    void aSaveStoresOnlyDifferencesAndSaysItAppliesAfterARestart() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        Map<String, String> before = platform.get().settings();
        try {
            mvc.perform(post("/__app/config/activity").session(admin).with(csrf())
                            .param("runway.app-activity.retention-days", "45"))
                    .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("flash"));
            assertEquals("45", platform.get().settings().get("runway.app-activity.retention-days"));
            String html = mvc.perform(get("/__app/config/activity").session(admin))
                    .andReturn().getResponse().getContentAsString();
            assertTrue(html.contains("runway.app-activity.retention-days"), "listed as pending a restart");

            mvc.perform(post("/__app/config/activity").session(admin).with(csrf())
                    .param("runway.app-activity.retention-days", "90")); // the default
            assertNull(platform.get().settings().get("runway.app-activity.retention-days"),
                    "a value equal to its default is not stored, so a later default is not pinned");
            assertEquals("enforce", platform.get().settings().get("runway.csp.mode"), "other screens' keys kept");
        } finally {
            platform.update(previous -> platform.get().withSettings(before));
        }
    }

    @Test
    void aValueThatWouldStopTheServerFromStartingIsRefused() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        Map<String, String> before = platform.get().settings();
        try {
            for (String[] bad : new String[][] {
                    {"backups", "runway.backups.schedule.cron", "every night"},
                    {"server", "server.servlet.session.timeout", "half an hour"},
                    {"server", "spring.servlet.multipart.max-file-size", "big"},
                    {"server", "runway.proxy.trusted-cidrs", "10.0.0.0/33"},
                    {"server", "runway.operator.ui-languages", "english"},
                    {"server", "server.jetty.threads.min", "500"}, // above the default max of 200
                    {"server", "runway.security.max-concurrent-hashes", "0"}}) {
                mvc.perform(post("/__app/config/" + bad[0]).session(admin).with(csrf()).param(bad[1], bad[2]))
                        .andExpect(flash().attributeExists("error"));
                assertEquals(before, platform.get().settings(), () -> "nothing written for " + bad[1]);
            }
        } finally {
            platform.update(previous -> platform.get().withSettings(before));
        }
    }

    /**
     * The reason a value is refused was English on a Spanish console. Each refusal now names the field
     * by its label and says why in the operator's language.
     */
    @Test
    void aRefusedValueSaysWhyInTheOperatorsLanguage() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        Map<String, String> before = platform.get().settings();
        try {
            for (String[] bad : new String[][] {
                    {"backups", "runway.backups.schedule.cron", "every night", "no es una programación de seis campos"},
                    {"server", "server.servlet.session.timeout", "half an hour", "no es una duración"},
                    {"server", "spring.servlet.multipart.max-file-size", "big", "no es un tamaño"},
                    {"server", "runway.proxy.trusted-cidrs", "10.0.0.0/33", "No es una dirección IP ni un rango CIDR"},
                    {"server", "runway.proxy.trusted-cidrs", "0.0.0.0/1,128.0.0.0/1", "Indique solo la dirección"},
                    {"server", "runway.operator.ui-languages", "english", "no es un código de idioma"},
                    {"server", "server.jetty.threads.min", "500", "es mayor que el máximo"},
                    {"server", "runway.security.max-concurrent-hashes", "0", "está fuera del rango permitido"},
                    {"server", "runway.csp.mode", "sometimes", "no es uno de"},
                    {"server", "server.jetty.threads.max", "many", "no es un número entero"}}) {
                mvc.perform(post("/__app/config/" + bad[0]).session(admin).with(csrf())
                                .cookie(new jakarta.servlet.http.Cookie("runway-op-lang", "es"))
                                .locale(java.util.Locale.forLanguageTag("es"))
                                .param(bad[1], bad[2]))
                        .andExpect(flash().attribute("error", allOf(containsString(bad[3]),
                                not(containsString(bad[1] + ":")))));
                assertEquals(before, platform.get().settings(), () -> "nothing written for " + bad[1]);
            }
        } finally {
            platform.update(previous -> platform.get().withSettings(before));
        }
    }

    /** A list the host writes as a YAML list ({@code key[0]}, {@code key[1]}) locks the field too. */
    @Test
    void aListTheHostSetsAsAYamlListIsLockedAndShowsTheHostsItems() throws Exception {
        String key = "runway.http-cache.immutable-extensions";
        assertTrue(host.setOnHost(key), "indexed keys count as the host setting the list");
        MockHttpSession admin = sessionFor("admin");
        String html = mvc.perform(get("/__app/config/server").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher area = Pattern.compile("(?s)<textarea([^>]*name=\"" + Pattern.quote(key) + "\"[^>]*)>(.*?)</textarea>")
                .matcher(html);
        assertTrue(area.find(), "the field is on the page");
        assertTrue(area.group(1).contains("disabled"), "locked: " + area.group(1));
        String shown = area.group(2);
        assertTrue(shown.contains("woff2") && shown.contains("ttf"), "the running value, the host's: " + shown);
        assertFalse(shown.contains("png"), "not the console's stale stored value: " + shown);
        Map<String, String> before = platform.get().settings();
        try {
            mvc.perform(post("/__app/config/server").session(admin).with(csrf()).param(key, "gif"))
                    .andExpect(status().is3xxRedirection());
            assertEquals("png", platform.get().settings().get(key), "a save does not write a host-set list");
        } finally {
            platform.update(previous -> previous.withSettings(before));
        }
    }

    /** Spring would resolve a {@code ${...}}, and could send the master key out in a public header. */
    @Test
    void aValueWithAPlaceholderIsRefusedForEveryKindOfSetting() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        Map<String, String> before = platform.get().settings();
        try {
            for (String[] bad : new String[][] {
                    {"runway.csp.report-uri", "/__csp-report?k=${runway.security.secret-key}"}, // a string
                    {"runway.csp.extra-hosts", "https://a.test\nhttps://${runway.security.secret-key}.test"}, // a list
                    {"runway.health.token", "${runway.security.secret-key}"}}) { // a secret
                mvc.perform(post("/__app/config/server").session(admin).with(csrf())
                                .param("runway.health.enabled", "true").param(bad[0], bad[1]))
                        .andExpect(flash().attribute("error", allOf(containsString("cannot contain ${"),
                                not(containsString("secret-key")))));
                assertEquals(before, platform.get().settings(), () -> "nothing written for " + bad[0]);
            }
            mvc.perform(post("/__app/config/server").session(admin).with(csrf())
                            .cookie(new jakarta.servlet.http.Cookie("runway-op-lang", "es"))
                            .locale(java.util.Locale.forLanguageTag("es"))
                            .param("runway.csp.report-uri", "${user.name}"))
                    .andExpect(flash().attribute("error", containsString("no puede contener ${")));
            assertEquals(before, platform.get().settings());
        } finally {
            platform.update(previous -> previous.withSettings(before));
        }
    }

    /**
     * A catch-all trusted proxy lets any client fake its address; the form refuses it. So do
     * ranges that add up to one (two /1 halves, the IPv4-mapped form), which a looser check would accept.
     */
    @Test
    void aCatchAllTrustedProxyIsRefused() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        Map<String, String> before = platform.get().settings();
        try {
            for (String bad : List.of("0.0.0.0/0", "::/0", "10.0.0.5/32,0.0.0.0/0", "0.0.0.0/1,128.0.0.0/1",
                    "::/1,8000::/1", "::ffff:0.0.0.0/96")) {
                mvc.perform(post("/__app/config/server").session(admin).with(csrf())
                                .param("runway.proxy.trusted-cidrs", bad))
                        .andExpect(flash().attribute("error", allOf(containsString("Trusted proxy addresses: "),
                                containsString("cover every IPv4 or every IPv6 address"))));
                assertEquals(before, platform.get().settings(), () -> "nothing written for " + bad);
            }
            mvc.perform(post("/__app/config/server").session(admin).with(csrf())
                    .param("runway.proxy.trusted-cidrs", "10.0.0.5/32")).andExpect(flash().attributeExists("flash"));
            assertEquals("10.0.0.5/32", platform.get().settings().get("runway.proxy.trusted-cidrs"),
                    "a proxy's own address is still accepted");
        } finally {
            platform.update(previous -> previous.withSettings(before));
        }
    }

    @Test
    void loweringTheSitesLogCeilingSaysHowManySitesAreAboveIt(@Autowired mx.quest.runway.site.SiteStore sites,
            @Autowired mx.quest.runway.log.SiteLogConfigService siteLogs) throws Exception {
        sites.save(new mx.quest.runway.site.Site("longlog", "L", List.of("longlog.test"), java.util.Locale.ENGLISH,
                List.of(java.util.Locale.ENGLISH), null, mx.quest.runway.site.SiteStatus.ACTIVE));
        siteLogs.save("longlog", new mx.quest.runway.log.SiteLogConfig(true, 60));
        Map<String, String> before = platform.get().settings();
        try {
            mvc.perform(post("/__app/config/server").session(sessionFor("admin")).with(csrf())
                            .param("runway.csp.mode", "enforce").param("runway.logs.max-retention-days", "40"))
                    .andExpect(flash().attribute("flash", org.hamcrest.Matchers.containsString(
                            "1 site keeps logs longer than 40 days")));
            assertEquals(60, siteLogs.get("longlog").retentionDays(), "the site's own choice is kept, not deleted");
        } finally {
            platform.update(previous -> platform.get().withSettings(before));
        }
    }

    @Test
    void onlyPlatformAdminsAndOnlyKnownScreens() throws Exception {
        mvc.perform(get("/__app/config/server").session(sessionFor("operator"))).andExpect(status().isForbidden());
        mvc.perform(post("/__app/config/server").session(sessionFor("operator")).with(csrf())
                .param("runway.csp.mode", "off")).andExpect(status().isForbidden());
        mvc.perform(get("/__app/config/nope").session(sessionFor("admin"))).andExpect(status().isNotFound());
    }

    @Test
    void everyDeclaredSettingHasALabelAndHelpInBothLanguages() throws IOException {
        java.util.Properties en = new java.util.Properties();
        java.util.Properties es = new java.util.Properties();
        try (var in = getClass().getClassLoader().getResourceAsStream("i18n/operator-messages.properties");
             var ines = getClass().getClassLoader().getResourceAsStream("i18n/operator-messages_es.properties")) {
            en.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            es.load(new java.io.InputStreamReader(ines, java.nio.charset.StandardCharsets.UTF_8));
        }
        for (var def : KernelSettings.all().values()) {
            for (String key : List.of(def.label(), def.help())) {
                assertTrue(en.containsKey(key) && es.containsKey(key), "missing message " + key);
            }
        }
        for (var group : KernelSettings.descriptor().settingGroups()) {
            assertTrue(en.containsKey(group.label()) && es.containsKey(group.label()), group.label());
        }
    }
}
