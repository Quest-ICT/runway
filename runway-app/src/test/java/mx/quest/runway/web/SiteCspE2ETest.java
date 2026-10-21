package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.platform.EmbedHostService;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteContextFilter;
import mx.quest.runway.site.SiteCspService;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Per-site CSP additions end to end ("site adds, platform approves"): the operator approves a host for some
 * directives, a site switches it on, and only that site's public pages carry it — not its console, not another
 * site; withdrawing the approval takes it away; a hand-written choice of an unapproved host does nothing; and the
 * screens are gated (operator: platform admin; site: Configure site).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteCspE2ETest {

    private static final String PW = "Site-Csp-Secret-9";
    private static final String CSP = "Content-Security-Policy";
    private static Path external;
    private static StorageLayout layout;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-site-csp");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        sites.save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE));
        sites.save(new Site("beta", "Beta", List.of("127.0.0.1"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE));
        for (String site : new String[] {"acme", "beta"}) {
            Path t = layout.siteDir(site).resolve("templates");
            Files.createDirectories(t);
            Files.writeString(t.resolve("home.html"), "<!DOCTYPE html><html><head><title>H</title></head><body>"
                    + "<main id=\"content\">home</main></body></html>", StandardCharsets.UTF_8);
            Files.writeString(t.resolve("home.meta.yml"), "path: /\nlocales: [en]\n", StandardCharsets.UTF_8);
        }
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true, true,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        users.save(external.resolve("app-users.yml"), new User("app-op", "operator", hash, "Op", "", true, false,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        Path acmeUsers = layout.siteDir("acme").resolve("users.yml");
        users.save(acmeUsers, new User("acme-admin", "admin", hash, "Admin", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));
        users.save(acmeUsers, new User("acme-op", "op", hash, "Op", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.csp.mode", () -> "enforce");
    }

    @LocalServerPort
    private int port;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SiteResolver siteResolver;
    @Autowired
    private EmbedHostService embedHosts;
    @Autowired
    private SiteCspService siteCsp;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new SiteContextFilter(siteResolver))
                .apply(springSecurity()).build();
        embedHosts.list().forEach(host -> embedHosts.remove(host.id()));
        siteCsp.save("acme", List.of());
        siteCsp.save("beta", List.of());
    }

    /** The CSP header of {@code path} on {@code host} (which picks the site: localhost is acme, 127.0.0.1 beta). */
    private String csp(String host, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + path)).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return response.headers().firstValue(CSP).orElse("");
    }

    private String acme(String path) throws Exception {
        return csp("localhost", path);
    }

    private String beta(String path) throws Exception {
        return csp("127.0.0.1", path);
    }

    @Test
    void onlyTheSitesOwnPublicPagesGetTheHostItSwitchedOn() throws Exception {
        String before = acme("/");
        assertFalse(before.contains("player.example.com"));
        embedHosts.save("player", "Video player", "https://player.example.com", List.of("frame-src", "media-src"));
        siteCsp.save("acme", List.of("player"));

        String page = acme("/");
        assertTrue(page.contains("frame-src 'self' https://player.example.com"), page);
        assertTrue(page.contains("media-src 'self' https://player.example.com"), page);
        assertFalse(page.contains("script-src 'self' https://player.example.com"), "never the script source");
        assertFalse(acme("/__admin").contains("player.example.com"), "not the site's console");
        assertFalse(acme("/login").contains("player.example.com"), "not its sign-in page");
        assertFalse(beta("/").contains("player.example.com"), "not another site");

        embedHosts.remove("player");
        assertFalse(acme("/").contains("player.example.com"), "withdrawing the approval takes it away at once");
    }

    @Test
    void aHandWrittenChoiceOfAnUnapprovedHostDoesNothing() throws Exception {
        Files.writeString(layout.siteDir("beta").resolve("csp.yml"), "hosts: [evil]\n", StandardCharsets.UTF_8);
        siteCsp.onSiteChanged(new mx.quest.runway.site.SiteChangedEvent("beta",
                mx.quest.runway.site.SiteChangedEvent.Kind.RESTORED));
        embedHosts.save("maps", "Maps", "https://maps.example.com", List.of("frame-src"));
        String page = beta("/");
        assertFalse(page.contains("evil"), page);
        assertFalse(page.contains("maps.example.com"), "an approved host the site did not choose is not added");
    }

    @Test
    void theOperatorScreenApprovesAndTheSiteScreenChooses() throws Exception {
        MvcResult login = mvc.perform(formLogin("/app/login").user("root").password(PW)).andReturn();
        MockHttpSession root = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(post("/__app/embed-hosts").session(root).with(csrf()).param("id", "video")
                        .param("label", "Video").param("source", "https://Video.Example.com/")
                        .param("directives", "frame-src"))
                .andExpect(flash().attribute("flash", "Approved Video."));
        assertEquals("https://video.example.com", embedHosts.list().get(0).source(), "lowercased, no slash");
        mvc.perform(post("/__app/embed-hosts").session(root).with(csrf()).param("id", "bad")
                        .param("label", "Bad").param("source", "https://cdn.example.com")
                        .param("directives", "script-src"))
                .andExpect(flash().attribute("error", "That is not a directive a host can join."));
        mvc.perform(post("/__app/embed-hosts").session(root).with(csrf()).param("id", "wild")
                        .param("label", "Wild").param("source", "*").param("directives", "frame-src"))
                .andExpect(flash().attributeExists("error"));
        assertEquals(1, embedHosts.list().size());

        MvcResult opLogin = mvc.perform(formLogin("/app/login").user("operator").password(PW)).andReturn();
        mvc.perform(get("/__app/embed-hosts").session((MockHttpSession) opLogin.getRequest().getSession(false)))
                .andExpect(status().isForbidden());

        MvcResult siteLogin = mvc.perform(formLogin("/login").user("admin").password(PW)).andReturn();
        MockHttpSession admin = (MockHttpSession) siteLogin.getRequest().getSession(false);
        String config = mvc.perform(get("/__admin/config").session(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(config.contains("value=\"video\""), "the approved host is offered");
        mvc.perform(post("/__admin/config/embeds").session(admin).with(csrf()).param("hosts", "video", "ghost"))
                .andExpect(flash().attributeExists("flash"));
        assertEquals(List.of("video"), siteCsp.chosen("acme"), "an unapproved id is dropped on save");
        assertTrue(acme("/").contains("frame-src 'self' https://video.example.com"));

        MvcResult opSite = mvc.perform(formLogin("/login").user("op").password(PW)).andReturn();
        mvc.perform(post("/__admin/config/embeds").session((MockHttpSession) opSite.getRequest().getSession(false))
                        .with(csrf()).param("hosts", "video"))
                .andExpect(status().isForbidden());
    }

    @Test
    void sourcesAreHttpsHostsOnly() {
        for (String bad : new String[] {"http://a.example.com", "https://a.example.com/path", "'unsafe-inline'",
                "data:", "https://*", "https://localhost", "https://a.example.com:99999x"}) {
            assertThrows(EmbedHostService.Invalid.class,
                    () -> embedHosts.save("x", "X", bad, List.of("img-src")), bad);
        }
        embedHosts.save("cdn", "CDN", "https://*.cdn.example.com:8443", List.of("img-src", "font-src"));
        assertEquals("https://*.cdn.example.com:8443", embedHosts.list().get(0).source());
    }
}
