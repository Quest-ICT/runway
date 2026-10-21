package mx.quest.runway.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Through a REAL Jetty and the full production filter chain: a caller who is <b>not signed in</b> can make
 * the server read at most the pre-authentication budget ({@code runway.endpoints.max-form-bytes}, here 2 KiB) of a
 * request body, on every path — the sign-in and password-recovery forms of both realms, a console path that would
 * answer {@code 403} only afterwards, the CSRF-exempt CSP report endpoint, a {@code PUT} form, and a plugin route
 * whose handler decides access itself. Without this cap the container would parse a form of up to 16 MiB for anyone
 * (the CSRF check asks for {@code _csrf} before authorization), and a plugin route would read 10 MiB. A signed-in
 * caller keeps the larger limits: the container's for a console form, {@code max-body-bytes} (here 64 KiB) for a
 * route.
 *
 * <p>Each limit is shown at its boundary (exactly the cap passes, one byte more is {@code 413}), chunked (no declared
 * length), and with a huge declared {@code Content-Length} answered before any of the body arrives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"runway.endpoints.max-body-bytes=65536", "runway.endpoints.max-form-bytes=2048"})
@Import(PreAuthBodyLimitE2ETest.Routes.class)
class PreAuthBodyLimitE2ETest {

    private static final int CAP = 2048;
    private static final String PW = "Pre-Auth-Body-E2E-9";
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-preauth-body-e2e");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        new SiteStore(layout, yaml).save(new Site("acme", "Acme Corp", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        // The operator console answers on the default site's host names (or on hosts that serve no site), and this
        // test reaches both realms on "localhost": make acme the default site, as the setup wizard does.
        new mx.quest.runway.platform.PlatformSettingsStore(layout, yaml)
                .update(current -> current.withDefaultSiteId("acme"));
        Path templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("_master.html"), "<html><head></head><body></body></html>");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        UserStore users = new UserStore(yaml);
        users.save(layout.siteDir("acme").resolve("users.yml"), new User("acme-admin", "admin", hash, "Admin", "",
                true, false, Realm.TENANT, Set.of(Roles.ADMIN), List.of()));
        users.save(external.resolve("app-users.yml"), new User("app-master", "root", hash, "Root", "", true, true,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        // Accounts the suspended-session test suspends while their session is open.
        users.save(layout.siteDir("acme").resolve("users.yml"), new User("acme-leaver", "leaver", hash, "Leaver", "",
                true, false, Realm.TENANT, Set.of(Roles.ADMIN), List.of()));
        users.save(external.resolve("app-users.yml"), new User("app-leaver", "appleaver", hash, "App leaver", "",
                true, false, Realm.APPLICATION, Set.of(Roles.SYSTEM_ADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.storage.backups", () -> external.resolve("backups").toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    /** A plugin route that decides access itself (permitAll at the chain), echoing how many body bytes it got. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Routes {
        @Bean
        EndpointContributor bodyProbe() {
            return new EndpointContributor() {
                @Override
                public String key() {
                    return "body-probe";
                }

                @Override
                public List<Route> routes() {
                    return List.of(new Route("GET", "/probe/api", Realm.TENANT, null),
                            new Route("POST", "/probe/api", Realm.TENANT, null));
                }

                @Override
                public RouteResponse handle(RouteRequest request) {
                    return RouteResponse.text(200, "bytes=" + request.body().length);
                }
            };
        }
    }

    @LocalServerPort
    private int port;

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** A fresh client with a cookie jar, and the CSRF token of the page it opened. */
    private record Browser(HttpClient client, String token) {
    }

    private Browser open(String page) throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        HttpResponse<String> res = client.send(HttpRequest.newBuilder(uri(page)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), page);
        Matcher m = CSRF.matcher(res.body());
        assertTrue(m.find(), page + " carries a CSRF token");
        return new Browser(client, m.group(1));
    }

    /** {@code fields} plus a {@code pad} field making the whole body exactly {@code length} bytes. */
    private static String padded(String fields, int length) {
        String head = fields + "&pad=";
        assertTrue(head.length() <= length, "fields alone exceed " + length);
        String body = head + "x".repeat(length - head.length());
        assertEquals(length, body.getBytes(StandardCharsets.UTF_8).length);
        return body;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> send(HttpClient client, String method, URI uri, String contentType,
                                             String body, boolean chunked) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher publisher = chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
                : HttpRequest.BodyPublishers.ofByteArray(bytes);
        return client.send(HttpRequest.newBuilder(uri).header("Content-Type", contentType).method(method, publisher)
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String path, String body) throws Exception {
        return send(client, "POST", uri(path), FORM, body, false);
    }

    /** A request that DECLARES a huge body and sends ten bytes of it: the status line must come back at once. */
    private String statusForHugeDeclaredBody(String method, String path, String contentType) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000); // a server that waited for the body would time this out
            OutputStream out = socket.getOutputStream();
            out.write((method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: " + contentType + "\r\n"
                    + "Content-Length: 999999999\r\nConnection: close\r\n\r\nname=aaaaa")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readNBytes(12), StandardCharsets.US_ASCII);
        }
    }

    // --- the sign-in forms ---------------------------------------------------------------------------------

    /** Exactly the cap signs in; one byte more is refused before the form is parsed — tenant realm. */
    @Test
    void theSiteLoginFormIsBoundedAtTheCap() throws Exception {
        Browser b = open("/login");
        String fields = "_csrf=" + enc(b.token()) + "&username=admin&password=" + enc(PW);
        HttpResponse<String> over = post(b.client(), "/login", padded(fields, CAP + 1));
        assertEquals(413, over.statusCode(), "one byte over the cap");
        HttpResponse<String> exact = post(b.client(), "/login", padded(fields, CAP));
        assertEquals(302, exact.statusCode(), exact.body());
        assertTrue(exact.headers().firstValue("Location").orElse("").endsWith("/__admin"),
                "exactly the cap: parsed, and the credentials signed in");
    }

    /** The same for the application realm's sign-in. */
    @Test
    void theAppLoginFormIsBoundedAtTheCap() throws Exception {
        Browser b = open("/app/login");
        String fields = "_csrf=" + enc(b.token()) + "&username=root&password=" + enc(PW);
        assertEquals(413, post(b.client(), "/app/login", padded(fields, CAP + 1)).statusCode());
        HttpResponse<String> exact = post(b.client(), "/app/login", padded(fields, CAP));
        assertEquals(302, exact.statusCode(), exact.body());
        assertTrue(exact.headers().firstValue("Location").orElse("").endsWith("/__app"), "signed in");
    }

    /** No declared length: the form is read under the cap here and its fields still reach the login filter. */
    @Test
    void aChunkedLoginFormIsCapturedUnderTheCapAndRefusedOverIt() throws Exception {
        Browser b = open("/login");
        String fields = "_csrf=" + enc(b.token()) + "&username=admin&password=" + enc(PW);
        assertEquals(413, send(b.client(), "POST", uri("/login"), FORM, padded(fields, CAP + 1), true).statusCode());
        HttpResponse<String> ok = send(b.client(), "POST", uri("/login"), FORM, padded(fields, CAP), true);
        assertEquals(302, ok.statusCode());
        assertTrue(ok.headers().firstValue("Location").orElse("").endsWith("/__admin"),
                "the captured fields signed in: " + ok.headers().firstValue("Location"));
    }

    @Test
    void aHugeDeclaredLengthOnASignInFormIsRefusedUnread() throws Exception {
        assertEquals("HTTP/1.1 413", statusForHugeDeclaredBody("POST", "/login", FORM));
        assertEquals("HTTP/1.1 413", statusForHugeDeclaredBody("POST", "/app/login", FORM));
    }

    // --- the password-recovery forms, and a console path ------------------------------------------------------

    @Test
    void thePasswordRecoveryFormsOfBothRealmsAreBounded() throws Exception {
        for (String[] form : List.of(new String[] {"/forgot-password", "/forgot-password"},
                new String[] {"/forgot-password", "/reset"},
                new String[] {"/app/forgot-password", "/app/forgot-password"},
                new String[] {"/app/forgot-password", "/app/reset"})) {
            Browser b = open(form[0]);
            String fields = "_csrf=" + enc(b.token()) + "&email=nobody%40example.test&token=x&password=y";
            assertEquals(413, post(b.client(), form[1], padded(fields, CAP + 1)).statusCode(), form[1]);
            int status = post(b.client(), form[1], padded(fields, CAP)).statusCode();
            assertTrue(status != 413 && status < 500, form[1] + " at the cap: " + status);
            assertEquals("HTTP/1.1 413", statusForHugeDeclaredBody("POST", form[1], FORM), form[1]);
        }
    }

    /** A console form posted by nobody: refused 413 before the CSRF check could parse it (it used to be 403 after). */
    @Test
    void aConsoleFormPostedWithoutSigningInIsBoundedBeforeTheCsrfCheck() throws Exception {
        HttpClient anonymous = HttpClient.newHttpClient();
        assertEquals(413, post(anonymous, "/__admin/backups/create", padded("a=b", CAP + 1)).statusCode());
        assertEquals(403, post(anonymous, "/__admin/backups/create", padded("a=b", CAP)).statusCode(),
                "under the cap the CSRF check decides, as before");
        assertEquals("HTTP/1.1 413", statusForHugeDeclaredBody("POST", "/__admin/pages/home/save", FORM));
    }

    /** Signed in, a console form keeps the container's limit: 100 KB (50 times the pre-auth cap) is accepted. */
    @Test
    void aSignedInConsoleFormIsNotHeldToThePreAuthCap() throws Exception {
        Browser b = open("/login");
        HttpResponse<String> login = post(b.client(), "/login",
                "_csrf=" + enc(b.token()) + "&username=admin&password=" + enc(PW));
        assertTrue(login.headers().firstValue("Location").orElse("").endsWith("/__admin"), "signed in");
        HttpResponse<String> screen = b.client().send(HttpRequest.newBuilder(uri("/__admin/backups")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = CSRF.matcher(screen.body());
        assertTrue(m.find(), "the backups screen carries a CSRF token");
        HttpResponse<String> create = post(b.client(), "/__admin/backups/create",
                padded("_csrf=" + enc(m.group(1)), 100_000));
        assertEquals(302, create.statusCode(), "a signed-in 100 KB form is parsed and served");
        assertTrue(create.headers().firstValue("Location").orElse("").endsWith("/__admin/backups"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private mx.quest.runway.security.SiteUserStore siteUsers;
    @org.springframework.beans.factory.annotation.Autowired
    private mx.quest.runway.security.AppUserStore appUsers;

    /**
     * The body budget used to trust the session's stored principal, read before
     * {@code AccountStateFilter} drops a suspended (or deleted, or password-changed) account — so a suspended user's
     * open session still got the container's 16 MiB form limit. The account state is now checked first: once the
     * account is suspended, the very next request of that session is held to the pre-authentication cap.
     */
    @Test
    void aSuspendedAccountsOpenSessionIsHeldToThePreAuthCap() throws Exception {
        // tenant realm
        Browser b = open("/login");
        HttpResponse<String> login = post(b.client(), "/login",
                "_csrf=" + enc(b.token()) + "&username=leaver&password=" + enc(PW));
        assertTrue(login.headers().firstValue("Location").orElse("").endsWith("/__admin"), "signed in");
        Matcher m = CSRF.matcher(b.client().send(HttpRequest.newBuilder(uri("/__admin/backups")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
        assertTrue(m.find());
        String body = padded("_csrf=" + enc(m.group(1)), 100_000);
        siteUsers.save("acme", siteUsers.findByPrincipalId("acme", "acme-leaver").orElseThrow().withEnabled(false));
        assertEquals(413, post(b.client(), "/__admin/backups/create", body).statusCode(),
                "suspended: the open session no longer has the signed-in budget");

        // application realm
        Browser a = open("/app/login");
        HttpResponse<String> appLogin = post(a.client(), "/app/login",
                "_csrf=" + enc(a.token()) + "&username=appleaver&password=" + enc(PW));
        assertTrue(appLogin.headers().firstValue("Location").orElse("").endsWith("/__app"), "signed in");
        Matcher am = CSRF.matcher(a.client().send(HttpRequest.newBuilder(uri("/__app")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
        assertTrue(am.find());
        String appBody = padded("_csrf=" + enc(am.group(1)), 100_000);
        appUsers.save(appUsers.findByPrincipalId("app-leaver").orElseThrow().withEnabled(false));
        assertEquals(413, post(a.client(), "/__app/backups/create", appBody).statusCode(),
                "suspended operator: held to the pre-auth cap too");
    }

    // --- raw bodies and other readers --------------------------------------------------------------------------

    /** The CSRF-exempt CSP report endpoint read a raw body of any size; now the pre-auth cap holds it. */
    @Test
    void theCspReportEndpointIsBounded() throws Exception {
        HttpClient anonymous = HttpClient.newHttpClient();
        String json = "{\"csp-report\":{\"blocked-uri\":\"" + "x".repeat(500) + "\"}}";
        assertEquals(204, send(anonymous, "POST", uri("/__csp-report"), "application/csp-report", json, false)
                .statusCode());
        String big = "{\"csp-report\":{\"blocked-uri\":\"" + "x".repeat(CAP) + "\"}}";
        assertEquals(413, send(anonymous, "POST", uri("/__csp-report"), "application/csp-report", big, false)
                .statusCode(), "declared over the cap");
        int chunked = send(anonymous, "POST", uri("/__csp-report"), "application/csp-report", big, true)
                .statusCode();
        assertTrue(chunked == 400 || chunked == 413, "chunked over the cap: stopped at the cap, " + chunked);
        assertEquals("HTTP/1.1 413", statusForHugeDeclaredBody("POST", "/__csp-report", "application/csp-report"));
    }

    /** Boot's form-content filter (PUT/PATCH/DELETE) read the whole body before security, for anyone, unbounded. */
    @Test
    void aPutFormIsBoundedBeforeSecurity() throws Exception {
        HttpClient anonymous = HttpClient.newHttpClient();
        assertEquals("HTTP/1.1 413", statusForHugeDeclaredBody("PUT", "/anything", FORM));
        assertEquals("HTTP/1.1 413", statusForHugeDeclaredBody("DELETE", "/anything", FORM));
        assertEquals(413, send(anonymous, "PUT", uri("/anything"), FORM, padded("a=b", CAP + 1), true).statusCode(),
                "chunked: stopped at the cap");
        assertEquals(413, send(anonymous, "PATCH", uri("/anything"), FORM, padded("a=b", CAP + 1), false)
                .statusCode());
        assertNotEquals(413, send(anonymous, "PUT", uri("/anything"), FORM, padded("a=b", CAP), false).statusCode(),
                "exactly the cap is parsed (and then refused by CSRF)");
    }

    // --- a plugin route that decides access itself -------------------------------------------------------------

    /** Not signed in: a GET route with a JSON body is held to the pre-auth cap, at its boundary. */
    @Test
    void anAnonymousCallersBodyToAPluginRouteIsHeldToThePreAuthCap() throws Exception {
        HttpClient anonymous = HttpClient.newHttpClient();
        HttpResponse<String> exact = send(anonymous, "GET", uri("/probe/api"), "application/json",
                "z".repeat(CAP), false);
        assertEquals(200, exact.statusCode());
        assertEquals("bytes=" + CAP, exact.body());
        assertEquals(413, send(anonymous, "GET", uri("/probe/api"), "application/json", "z".repeat(CAP + 1), false)
                .statusCode());
        assertEquals(413, send(anonymous, "GET", uri("/probe/api"), "application/json", "z".repeat(20_000), true)
                .statusCode(), "chunked");
        assertEquals("HTTP/1.1 413", statusForHugeDeclaredBody("GET", "/probe/api", "application/json"));
    }

    /** Signed in: the same route takes up to max-body-bytes (64 KiB), and 413 past it. */
    @Test
    void aSignedInCallersBodyToAPluginRouteKeepsTheBodyCap() throws Exception {
        Browser b = open("/login");
        post(b.client(), "/login", "_csrf=" + enc(b.token()) + "&username=admin&password=" + enc(PW));
        HttpResponse<String> big = send(b.client(), "GET", uri("/probe/api"), "application/json",
                "z".repeat(20_000), false);
        assertEquals(200, big.statusCode());
        assertEquals("bytes=20000", big.body());
        HttpResponse<String> chunked = send(b.client(), "GET", uri("/probe/api"), "application/json",
                "z".repeat(20_000), true);
        assertEquals("bytes=20000", chunked.body(), "chunked, signed in");
        assertEquals(413, send(b.client(), "GET", uri("/probe/api"), "application/json", "z".repeat(70_000), false)
                .statusCode(), "max-body-bytes still applies");
    }
}
