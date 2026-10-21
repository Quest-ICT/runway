package mx.quest.runway.console;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.platform.KernelSettingsInitializer;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.RunwayCspProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * "Use the file's value" on a locked Server settings field: moving a key from the host's
 * {@code application.yml} to the console used to cost two restarts and a start on the default, because the console
 * could not hold a value while the host's line was there. The action copies the value the server runs with into the
 * console's store, through the checks a save applies, so the line can be removed and the server restarted once with
 * nothing changing. The locked row says what removing the line would do, before and after.
 */
@SpringBootTest(properties = "spring.config.additional-location=classpath:/kernel-adopt-host/")
class KernelSettingsAdoptTest {

    private static final String PASSWORD = "Kernel-Adopt-Secret-9";
    private static final String SECRET_KEY = "kernel-adopt-test-master-key-0123456789";
    private static final String HEALTH_TOKEN = "host-health-token-7f3a";
    private static final String METRICS_TOKEN = "host-metrics-token-91bc";
    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-kernel-adopt");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PASSWORD);
        UserStore users = new UserStore(new YamlStore());
        Path file = external.resolve("app-users.yml");
        users.save(file, new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(file, new User("op-user", "operator", hash, "Operator", "o@x", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        // The console's own value for one key the host also sets: it differs from the host's.
        Files.writeString(external.resolve("platform.yml"), """
                settings:
                  runway.backups.retention.max-count: "5"
                """);
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.security.secret-key", () -> SECRET_KEY);
        // "On the host": each ranks above the console's value, so the field is locked. The host's application.yml
        // (kernel-adopt-host/) sets runway.csp.report-uri and the YAML list runway.csp.extra-hosts; these are set
        // "elsewhere on the server", like an environment variable.
        registry.add("runway.health.token", () -> HEALTH_TOKEN);                       // a secret
        registry.add("runway.metrics.scrape-token", () -> METRICS_TOKEN);              // a secret
        registry.add("runway.proxy.trusted-cidrs", () -> "0.0.0.0/0");                 // runs, but the console refuses
        registry.add("runway.compression.mime-types", () -> "");                       // blank: not storable
        registry.add("runway.http-cache.public-pages", () -> "public, \\${not.resolved}"); // a literal ${
        registry.add("runway.backups.retention.max-count", () -> "12");                // console holds 5
        registry.add("runway.csp.mode", () -> "REPORT");                               // the default, any case
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private PlatformSettingsStore platform;
    @MockitoSpyBean
    private SecretCipher cipher;
    private MockMvc mvc;
    private Map<String, String> before;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        before = platform.get().settings();
    }

    @AfterEach
    void restore() {
        reset(cipher);
        platform.update(previous -> previous.withSettings(before));
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user(username).password(PASSWORD)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MvcResult adopt(MockHttpSession session, String screen, String key) throws Exception {
        return mvc.perform(post("/__app/config/" + screen + "/adopt").session(session).with(csrf()).param("key", key))
                .andReturn();
    }

    private Document page(MockHttpSession session, String screen) throws Exception {
        String html = mvc.perform(get("/__app/config/" + screen).session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Jsoup.parse(html);
    }

    /** The locked note of {@code key}'s row (its id is {@code lk-<key>}). */
    private static String note(Document page, String key) {
        Element note = page.getElementById("lk-" + key);
        assertNotNull(note, "a locked note for " + key);
        return note.text();
    }

    /** The adopt button of {@code key}'s row, or {@code null} when the row offers none. */
    private static Element button(Document page, String key) {
        return page.selectFirst("button.sr-adopt[value=\"" + key + "\"]");
    }

    @Test
    void aStringIsCopiedAndTheRowSaysRemovingTheLineNowChangesNothing() throws Exception {
        String key = "runway.csp.report-uri";
        MockHttpSession admin = sessionFor("admin");
        Document beforePage = page(admin, "server");
        assertTrue(note(beforePage, key).contains("Set on the server in external/application.yml")
                && note(beforePage, key).contains("the default takes effect"), note(beforePage, key));
        Element offered = button(beforePage, key);
        assertNotNull(offered, "the row offers to copy the value");
        assertEquals("st-adopt-form", offered.attr("form"), "posts its own form, never the settings form");
        assertEquals("Use the file's value", offered.text().trim());
        assertTrue(offered.attr("data-confirm").contains("restart once"), "asks first");
        Element form = beforePage.getElementById("st-adopt-form");
        assertNotNull(form);
        assertEquals("/__app/config/server/adopt", form.attr("action"));
        assertEquals("post", form.attr("method"));
        assertNotNull(form.selectFirst("input[name=_csrf]"), "with its own CSRF token");

        MvcResult result = adopt(admin, "server", key);
        assertEquals(302, result.getResponse().getStatus());
        assertTrue(String.valueOf(result.getFlashMap().get("flash")).contains("the console now holds the value"),
                String.valueOf(result.getFlashMap().get("flash")));
        assertEquals("/host-csp-report", platform.get().settings().get(key));

        Document after = page(admin, "server");
        assertTrue(after.selectFirst("[name=\"" + key + "\"]").hasAttr("disabled"),
                "still locked: the host still wins");
        assertTrue(note(after, key).contains("The console holds the same value, so removing it there changes "
                + "nothing."), note(after, key));
        assertNull(button(after, key), "nothing left to copy");
        assertTrue(after.getElementById("st-pending-text").text().contains(key), "listed as saved since the start");
    }

    @Test
    void aListTheHostWritesAsAYamlListIsStoredAsTheConsoleStoresLists() throws Exception {
        String key = "runway.csp.extra-hosts";
        MockHttpSession admin = sessionFor("admin");
        adopt(admin, "server", key);
        assertEquals("https://a.example.test,https://b.example.test", platform.get().settings().get(key));
        assertTrue(note(page(admin, "server"), key).contains("same value"));
    }

    @Test
    void aSecretIsStoredEncryptedAndNeverShown() throws Exception {
        String key = "runway.health.token";
        MockHttpSession admin = sessionFor("admin");
        Document beforePage = page(admin, "server");
        assertTrue(note(beforePage, key).contains("holds no value of its own"));
        assertEquals("Use the server's value", button(beforePage, key).text().trim(),
                "set elsewhere than the host's file");
        MvcResult result = adopt(admin, "server", key);
        String stored = platform.get().settings().get(key);
        assertNotNull(stored);
        assertTrue(stored.startsWith("ENC("), "stored encrypted: " + stored);
        assertFalse(stored.contains(HEALTH_TOKEN));
        assertEquals(HEALTH_TOKEN, cipher.decrypt(stored));
        assertFalse(String.valueOf(result.getFlashMap().get("flash")).contains(HEALTH_TOKEN), "not in the flash");
        Document after = page(admin, "server");
        assertFalse(after.html().contains(HEALTH_TOKEN), "nor on the page");
        assertTrue(note(after, key).contains("same value"), note(after, key));
    }

    @Test
    void aSecretWithoutTheSecretKeyIsRefusedAndNothingIsStored() throws Exception {
        doReturn(false).when(cipher).isAvailable();
        MvcResult result = adopt(sessionFor("admin"), "server", "runway.metrics.scrape-token");
        assertEquals(before, platform.get().settings());
        String error = String.valueOf(result.getFlashMap().get("error"));
        assertTrue(error.contains("Set runway.security.secret-key on the server to store a token here."), error);
        assertFalse(error.contains(METRICS_TOKEN));
    }

    @Test
    void aValueThePerKeyCheckRefusesIsRefusedAndNothingIsStored() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        MvcResult catchAll = adopt(admin, "server", "runway.proxy.trusted-cidrs");
        assertTrue(String.valueOf(catchAll.getFlashMap().get("error")).contains("cover every IPv4 or every IPv6"),
                String.valueOf(catchAll.getFlashMap().get("error")));
        assertEquals(before, platform.get().settings());

        MvcResult placeholder = adopt(admin, "server", "runway.http-cache.public-pages");
        assertTrue(String.valueOf(placeholder.getFlashMap().get("error")).contains("cannot contain ${"),
                String.valueOf(placeholder.getFlashMap().get("error")));
        assertEquals(before, platform.get().settings());

        MvcResult blank = adopt(admin, "server", "runway.compression.mime-types");
        assertTrue(String.valueOf(blank.getFlashMap().get("error")).contains("a blank field here means the default"),
                String.valueOf(blank.getFlashMap().get("error")));
        assertEquals(before, platform.get().settings());
    }

    @Test
    void aRefusalAndTheRowReadInSpanish() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        mvc.perform(post("/__app/config/server/adopt").session(admin).with(csrf())
                        .cookie(new Cookie("runway-op-lang", "es")).locale(Locale.forLanguageTag("es"))
                        .param("key", "runway.proxy.trusted-cidrs"))
                .andExpect(flash().attribute("error", allOf(containsString("Indique solo la dirección"),
                        not(containsString("runway.proxy.trusted-cidrs:")))));
        String html = mvc.perform(get("/__app/config/server").session(admin).cookie(new Cookie("runway-op-lang", "es")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Document es = Jsoup.parse(html);
        assertTrue(note(es, "runway.csp.report-uri").contains("La consola no guarda un valor propio"));
        assertEquals("Usar el valor del archivo", button(es, "runway.csp.report-uri").text().trim());
    }

    @Test
    void aConsoleValueThatDiffersIsSaidAndCopyingReplacesIt() throws Exception {
        String key = "runway.backups.retention.max-count";
        MockHttpSession admin = sessionFor("admin");
        Document page = page(admin, "backups");
        assertTrue(note(page, key).contains("The console holds a different value, which takes effect if you remove "
                + "it there."), note(page, key));
        assertNotNull(button(page, key));
        adopt(admin, "backups", key);
        assertEquals("12", platform.get().settings().get(key));
        assertTrue(note(page(admin, "backups"), key).contains("same value"));
    }

    @Test
    void aHostValueEqualToTheDefaultNeedsNothingAndOffersNothing() throws Exception {
        String key = "runway.csp.mode";
        MockHttpSession admin = sessionFor("admin");
        Document page = page(admin, "server");
        assertTrue(note(page, key).contains("It is also the default, so removing it there changes nothing."),
                note(page, key));
        assertNull(button(page, key), "nothing to copy");
        MvcResult result = adopt(admin, "server", key); // posted anyway: stores nothing, says why
        assertNull(platform.get().settings().get(key));
        assertTrue(String.valueOf(result.getFlashMap().get("flash")).contains("is the default"));
    }

    @Test
    void aFieldTheHostDoesNotSetOrAnUnknownKeyHasNothingToCopy() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        MvcResult notOnHost = adopt(admin, "server", "runway.csp.strict-dynamic");
        assertTrue(String.valueOf(notOnHost.getFlashMap().get("error")).contains("nothing to copy"));
        assertEquals(before, platform.get().settings());
        mvc.perform(post("/__app/config/server/adopt").session(admin).with(csrf()).param("key", "server.port"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/__app/config/server/adopt").session(admin).with(csrf())
                .param("key", "runway.backups.retention.max-count")).andExpect(status().isNotFound()); // other screen
    }

    @Test
    void onlyPlatformAdminsWithACsrfTokenAndOnlyByPost() throws Exception {
        String key = "runway.csp.report-uri";
        mvc.perform(post("/__app/config/server/adopt").session(sessionFor("operator")).with(csrf()).param("key", key))
                .andExpect(status().isForbidden());
        mvc.perform(post("/__app/config/server/adopt").session(sessionFor("admin")).param("key", key))
                .andExpect(status().isForbidden());
        mvc.perform(get("/__app/config/server/adopt").session(sessionFor("admin")).param("key", key))
                .andExpect(status().is(org.hamcrest.Matchers.not(200)));
        assertEquals(before, platform.get().settings(), "nothing stored by any of them");
    }

    @Test
    void theActivityLogNamesTheKeyAndNeverTheValue() throws Exception {
        adopt(sessionFor("admin"), "server", "runway.health.token");
        String log;
        try (Stream<Path> files = Files.walk(external.resolve("activity"))) {
            log = files.filter(Files::isRegularFile).map(p -> {
                try {
                    return Files.readString(p);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).reduce("", String::concat);
        }
        assertTrue(log.contains("platform.kernel-setting-adopted") && log.contains("runway.health.token"), log);
        assertFalse(log.contains(HEALTH_TOKEN), "never the value");
    }

    /**
     * The point of the action: once the host's line is gone, what the console stored is what binds, so the restart
     * after removing the line changes nothing. The "server after removing the line" is a fresh environment holding
     * only the secret key, loaded by the same initializer the application runs.
     */
    @Test
    void afterTheHostsLineIsRemovedTheConsolesValueIsWhatBinds() throws Exception {
        MockHttpSession admin = sessionFor("admin");
        adopt(admin, "server", "runway.csp.report-uri");
        adopt(admin, "server", "runway.csp.extra-hosts");
        adopt(admin, "server", "runway.health.token");

        StandardEnvironment withoutTheLines = new StandardEnvironment();
        withoutTheLines.getPropertySources().addFirst(new MapPropertySource("host", Map.of(
                "runway.storage.external", external.toString(), "runway.security.secret-key", SECRET_KEY)));
        try (GenericApplicationContext fresh = new GenericApplicationContext()) {
            fresh.setEnvironment(withoutTheLines);
            new KernelSettingsInitializer().initialize(fresh);
        }
        Binder binder = Binder.get(withoutTheLines);
        RunwayCspProperties csp = binder.bindOrCreate("runway.csp", RunwayCspProperties.class);
        assertEquals("/host-csp-report", csp.getReportUri());
        assertEquals(List.of("https://a.example.test", "https://b.example.test"), csp.getExtraHosts());
        assertEquals(HEALTH_TOKEN, withoutTheLines.getProperty("runway.health.token"));
        assertEquals(List.of("https://a.example.test", "https://b.example.test"),
                binder.bind("runway.csp.extra-hosts", Bindable.listOf(String.class)).get());
    }
}
