package mx.quest.runway.console;

import static org.assertj.core.api.Assertions.assertThat;
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
import mx.quest.runway.platform.PlatformSettings;
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
import mx.quest.runway.web.ForwardedFor;
import mx.quest.runway.web.ProxyResolution;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The app-realm <b>About Runway</b> screen renders the effective configuration and runtime environment, is
 * reachable to a signed-in operator, exposes both Settings-dropdown children, and — critically — never leaks a
 * configured secret (the TLS keystore password or the master secret key).
 */
@SpringBootTest
class AppAboutPageTest {

    private static final String PW = "About-Page-7";
    private static final String TLS_PW = "TLSPW-must-not-leak-4242";
    private static final String SECRET_KEY = "vX9kQ2m-secret-key-must-not-leak-0123456789abcdef0123456789";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-about");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true,
                true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        // Configure two real secrets so the "never leaks" assertions are meaningful.
        registry.add("runway.security.secret-key", () -> SECRET_KEY);
        registry.add("runway.tls.keystore-password", () -> TLS_PW);
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private RuntimeInfoService info;
    @Autowired
    private mx.quest.runway.platform.PlatformSettingsStore platform;
    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(new SiteContextFilter(siteResolver))
                    .apply(springSecurity()).build();
        }
        return mvc;
    }

    private MockHttpSession login() throws Exception {
        MvcResult r = mvc().perform(formLogin("/app/login").user("root").password(PW)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void aboutRendersConfigAndRuntimeSections() throws Exception {
        MockHttpSession session = login();
        mvc().perform(get("/__app/about").session(session).locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("About Runway")))
                .andExpect(content().string(Matchers.containsString("Effective configuration")))
                .andExpect(content().string(Matchers.containsString("Runtime environment")))
                .andExpect(content().string(Matchers.containsString("Spring Boot")))   // a bundled component
                .andExpect(content().string(Matchers.containsString("Argon2id")));      // the password-hashing row
    }

    @Test
    void aboutNeverLeaksConfiguredSecrets() throws Exception {
        MockHttpSession session = login();
        String body = mvc().perform(get("/__app/about").session(session).locale(Locale.ENGLISH))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(TLS_PW);
        assertThat(body).doesNotContain(SECRET_KEY);
        // Secret-adjacent state is shown only as a boolean; the key is configured, so it reads "configured".
        assertThat(body).contains("configured");
    }

    @Test
    void settingsDropdownExposesBothChildren() throws Exception {
        MockHttpSession session = login();
        mvc().perform(get("/__app/about").session(session).locale(Locale.ENGLISH))
                .andExpect(content().string(Matchers.containsString("grp-settings")))
                .andExpect(content().string(Matchers.containsString("href=\"/__app/about\"")))
                .andExpect(content().string(Matchers.containsString("href=\"/__app/settings\"")));
    }

    @Test
    void aboutShowsThisRequestAsRunwaySeesIt() throws Exception {
        MockHttpSession session = login();
        mvc().perform(get("/__app/about").session(session).locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("This request")))
                .andExpect(content().string(Matchers.containsString("Client address used")))
                .andExpect(content().string(Matchers.containsString("Scheme and host")));
    }

    @Test
    void theRequestRowsShowHowTheProxyGateAttributedIt() {
        MockHttpServletRequest trusted = new MockHttpServletRequest("GET", "/__app/about");
        trusted.setAttribute(ProxyResolution.ATTRIBUTE, new ProxyResolution("10.0.0.5", true, true,
                new ForwardedFor.Chain(List.of("6.6.6.6", "203.0.113.7"), 2), "203.0.113.7"));
        assertThat(info.request(trusted)).extracting(RuntimeInfoService.NameValue::value)
                .containsExactly("10.0.0.5 (trusted proxy)", "6.6.6.6, 203.0.113.7", "203.0.113.7",
                        "http://localhost");

        MockHttpServletRequest direct = new MockHttpServletRequest("GET", "/__app/about");
        direct.setAttribute(ProxyResolution.ATTRIBUTE, new ProxyResolution("198.51.100.9", false, false,
                new ForwardedFor.Chain(List.of("6.6.6.6"), 1), "198.51.100.9"));
        assertThat(info.request(direct)).extracting(RuntimeInfoService.NameValue::value)
                .containsExactly("198.51.100.9 (not a trusted proxy)", "6.6.6.6 (ignored)", "198.51.100.9",
                        "http://localhost");

        MockHttpServletRequest none = new MockHttpServletRequest("GET", "/__app/about");
        none.setAttribute(ProxyResolution.ATTRIBUTE,
                new ProxyResolution("127.0.0.1", true, true, ForwardedFor.Chain.NONE, "127.0.0.1"));
        assertThat(info.request(none)).extracting(RuntimeInfoService.NameValue::value)
                .containsExactly("127.0.0.1 (trusted proxy)", "none", "127.0.0.1", "http://localhost");
    }

    @Test
    void aboutRequiresAuthentication() throws Exception {
        mvc().perform(get("/__app/about")).andExpect(status().is3xxRedirection());
    }

    @Test
    void thePasswordRowShowsTheProfileInForceAndASavedChangeSeparately() {
        PlatformSettings before = platform.get();
        try {
            assertThat(info.passwordSummary()).isEqualTo("Argon2id · STANDARD");
            platform.update(previous -> before.withPasswordStrength("HIGH")); // saved now, in force only after a restart
            assertThat(info.passwordSummary())
                    .isEqualTo("Argon2id · STANDARD (HIGH is saved and applies after the next restart)");
        } finally {
            platform.update(previous -> before);
        }
    }

    @Test
    void aDataPathReachedThroughALinkShowsWhereItReallyIs(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws IOException {
        Path real = Files.createDirectories(tmp.resolve("runway-data/external"));
        Path link = Files.createSymbolicLink(tmp.resolve("external"), real);
        assertThat(RuntimeInfoService.pathSummary(link)).isEqualTo(link + " → " + real.toRealPath());
        assertThat(RuntimeInfoService.pathSummary(real.toRealPath())).isEqualTo(real.toRealPath().toString());
        assertThat(RuntimeInfoService.pathSummary(tmp.resolve("missing"))).isEqualTo(tmp.resolve("missing").toString());
    }

    @Test
    void runtimeInfoServiceSnapshotIsPopulated() {
        assertThat(info.version()).isNotBlank();
        assertThat(info.runtime()).anyMatch(nv -> nv.name().equals("op.about.rt.java"));
        assertThat(info.effectiveConfig()).anyMatch(nv -> nv.name().equals("op.about.cfg.pw"));
        // The curated component set resolves — including libs with no Implementation-Version (BouncyCastle,
        // Prometheus), the Thymeleaf engine internals (attoparser), and the class-less JS bundle (CodeMirror).
        assertThat(info.components()).extracting(RuntimeInfoService.NameValue::name)
                .contains("Spring Boot", "Jetty", "Thymeleaf", "attoparser", "Jackson",
                        "BouncyCastle", "Prometheus", "Micrometer", "CodeMirror", "SLF4J");
        // Every listed component carries a non-blank version (no silent-drop / blank rows).
        assertThat(info.components()).allSatisfy(nv -> assertThat(nv.value()).isNotBlank());
    }
}
