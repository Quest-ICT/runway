package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The operator console stays off the host names of tenant sites, against
 * a real server and the real filter chain. Default rule ({@code runway.console.hosts} unset): the console answers on
 * the default site's host ({@code main.test}) and on a host that serves no site ({@code localhost}), and answers a
 * plain 404 on the host of another site ({@code shop.test}) — for every console path, sign-in and recovery
 * included. The site's own console ({@code /__admin}) and public pages on that host are unchanged.
 *
 * <p>Requests go over a raw socket so each can name its {@code Host}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class ConsoleHostEndToEndTest {

    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-console-host");
        seed(external, "main");
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    /** Two sites, an operator, and (when {@code defaultSite} is not null) the platform's default-site pointer. */
    static void seed(Path external, String defaultSite) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        sites.save(new Site("main", "Main", List.of("main.test"), Locale.ENGLISH, List.of(Locale.ENGLISH),
                null, SiteStatus.ACTIVE, false, null));
        sites.save(new Site("shop", "Shop", List.of("shop.test", "www.shop.test"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE, false, null));
        if (defaultSite != null) {
            new PlatformSettingsStore(layout, yaml).update(current -> current.withDefaultSiteId(defaultSite));
        }
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Console-Host-9");
        new UserStore(yaml).save(external.resolve("app-users.yml"),
                new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
    }

    @LocalServerPort
    private int port;

    private RawHttp.Response get(String host, String path) throws IOException {
        return RawHttp.send(port, "GET", host + ":" + port, path);
    }

    private static final List<String> CONSOLE_GETS = List.of(
            "/__app", "/__app/", "/__app/sites", "/__app/settings", "/__app/plugins/x/y",
            "/app/login", "/app/forgot-password", "/app/reset", "/app/reset?token=abc", "/app/logout");

    @Test
    void everyConsolePathAnswers404OnAnotherSitesHost() throws IOException {
        for (String host : List.of("shop.test", "SHOP.TEST", "www.shop.test")) {
            for (String path : CONSOLE_GETS) {
                assertEquals(404, get(host, path).status(), "GET " + path + " on " + host);
            }
        }
    }

    @Test
    void formPostsToSignInAndRecoveryAre404TooBeforeAnyCsrfOrLoginCheck() throws IOException {
        for (String path : List.of("/app/login", "/app/logout", "/app/forgot-password", "/app/reset",
                "/__app/settings")) {
            RawHttp.Response res = RawHttp.send(port, "POST", "shop.test:" + port, path,
                    "username=root&password=Console-Host-9&email=a%40b.test&token=x");
            assertEquals(404, res.status(), "POST " + path + " on shop.test");
            assertTrue(res.header("set-cookie") == null, "no session is started on a refused host: " + path);
        }
    }

    @Test
    void headIsRefusedToo() throws IOException {
        assertEquals(404, RawHttp.send(port, "HEAD", "shop.test:" + port, "/app/login").status());
        assertEquals(404, RawHttp.send(port, "HEAD", "shop.test:" + port, "/__app").status());
        assertEquals(200, RawHttp.send(port, "HEAD", "main.test:" + port, "/app/login").status());
    }

    /** A dot-segment path the container leaves uncanonical still never shows the console's error page here. */
    @Test
    void aDotSegmentPathShowsNoConsolePageOnAnotherSitesHost() throws IOException {
        for (String path : new String[] {"/x;/../__app", "/x;/../app/login", "/x;/../__app/config/server"}) {
            RawHttp.Response res = get("shop.test", path);
            assertFalse(res.body().contains("Back to the console"), () -> path + " showed the console error page:\n" + res.body());
        }
    }

    @Test
    void theRefusalLooksLikeAnyUnknownPageOfThatSite() throws IOException {
        RawHttp.Response refused = get("shop.test", "/__app");
        RawHttp.Response unknown = get("shop.test", "/no-such-page");
        assertEquals(404, unknown.status());
        assertFalse(refused.body().contains("Back to the console"), refused.body());
        assertEquals(unknown.body(), refused.body(), "the same 404 page as an unknown page on that host");
        assertEquals(unknown.body(), get("shop.test", "/app/login").body());
    }

    @Test
    void theConsoleStillAnswersOnTheDefaultSitesHost() throws IOException {
        RawHttp.Response login = get("main.test", "/app/login");
        assertEquals(200, login.status());
        assertTrue(login.body().contains("password"), "the sign-in form");
        assertEquals(200, get("main.test", "/app/forgot-password").status());
        RawHttp.Response console = get("main.test", "/__app/sites");
        assertEquals(302, console.status(), "signed out: sent to the sign-in page");
        assertTrue(console.header("location").endsWith("/app/login"), console.header("location"));
    }

    @Test
    void theConsoleStillAnswersOnAHostThatServesNoSite() throws IOException {
        for (String host : List.of("localhost", "127.0.0.1", "unbound.test")) {
            assertEquals(200, get(host, "/app/login").status(), host);
            assertEquals(200, get(host, "/app/forgot-password").status(), host);
            assertEquals(302, get(host, "/__app").status(), host);
        }
    }

    @Test
    void anEncodedSpellingOfTheConsolePathIsNoWayAround() throws IOException {
        for (String path : List.of("/%5F%5Fapp/sites", "/%5f_app", "/app/%6Cogin", "/%61pp/login")) {
            RawHttp.Response res = get("shop.test", path);
            assertTrue(res.status() == 404 || res.status() == 400, path + " -> " + res.status());
        }
    }

    @Test
    void theSitesOwnConsoleAndPublicPagesOnThatHostAreUnchanged() throws IOException {
        RawHttp.Response admin = get("shop.test", "/__admin");
        assertEquals(302, admin.status(), "the site's console still sends to its own sign-in");
        assertTrue(admin.header("location").endsWith("/login"), admin.header("location"));
        assertEquals(200, get("shop.test", "/login").status());
        assertEquals(200, get("shop.test", "/forgot-password").status());
        assertNotEquals(404, get("shop.test", "/actuator/health").status(), "monitoring is unchanged");
        // A tenant path that only looks like the console is the site's own (and simply not found here).
        assertEquals(get("shop.test", "/no-such-page").body(), get("shop.test", "/__apple").body());
    }

    @Test
    void aRefusedHostIsLoggedOnceWithTheSiteAndTheWayToAllowIt(CapturedOutput output) throws IOException {
        int from = output.getAll().length();
        get("www.shop.test", "/app/login");
        get("www.shop.test", "/__app");
        get("www.shop.test", "/app/forgot-password");
        String log = output.getAll().substring(from);
        String line = "Operator console refused on host 'www.shop.test'";
        assertEquals(1, log.split(java.util.regex.Pattern.quote(line), -1).length - 1, log);
        int at = log.indexOf(line);
        String entry = log.substring(at, log.indexOf('\n', at));
        assertTrue(entry.contains("'shop'"), entry);
        assertTrue(entry.contains("runway.console.hosts"), entry);
        assertTrue(log.substring(log.lastIndexOf('\n', at) + 1, at).contains("INFO"), "logged at INFO");
    }
}
