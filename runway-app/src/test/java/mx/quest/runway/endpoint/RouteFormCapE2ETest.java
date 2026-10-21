package mx.quest.runway.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Through a REAL Jetty and the full production filter chain: the form body a plugin route receives
 * is read <b>before authentication and CSRF</b>, so it is bounded by the small {@code runway.endpoints.max-form-bytes}
 * — here 1 KiB — while {@code runway.endpoints.max-body-bytes} (64 KiB) still governs every other body of a signed-in
 * caller, read after security (a caller who is not signed in is held to the 1 KiB for any body since 1.0.0-rc1). An
 * oversized form is refused {@code 413} before the CSRF check could answer {@code 403}, whether it
 * declares its length, streams it chunked, or declares a huge length and sends nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"runway.endpoints.max-body-bytes=65536", "runway.endpoints.max-form-bytes=1024"})
@Import(RouteFormCapE2ETest.Routes.class)
class RouteFormCapE2ETest {

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-route-form-cap-e2e");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        new SiteStore(layout, new YamlStore()).save(new Site("acme", "Acme Corp", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        Path templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("_master.html"), "<html><head></head><body></body></html>");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Route-Form-Cap-E2E-4");
        new UserStore(new YamlStore()).save(layout.siteDir("acme").resolve("users.yml"), new User("acme-admin",
                "admin", hash, "Admin", "", true, false, Realm.TENANT, Set.of(Roles.ADMIN), List.of()));
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"), new User("app-master", "root", hash,
                "Root", "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    /** Echoes how many body bytes and form fields the route was handed. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Routes {
        @Bean
        EndpointContributor capProbe() {
            return new EndpointContributor() {
                @Override
                public String key() {
                    return "cap-probe";
                }

                @Override
                public List<Route> routes() {
                    return List.of(new Route("POST", "/cap/ordinary", Realm.TENANT, null),
                            Route.anonymousPost("/cap/anon", null));
                }

                @Override
                public RouteResponse handle(RouteRequest request) {
                    return RouteResponse.text(200, "bytes=" + request.body().length + " fields=" + request.form().size());
                }
            };
        }
    }

    @LocalServerPort
    private int port;

    private HttpClient client;
    private String token;

    @BeforeEach
    void session() throws Exception {
        client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        HttpResponse<String> login = client.send(HttpRequest.newBuilder(uri("/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode());
        Matcher m = CSRF.matcher(login.body());
        assertTrue(m.find(), "the login page carries a CSRF token");
        token = m.group(1);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> post(String path, String contentType, String body, String headerToken)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (headerToken != null) {
            b.header("X-CSRF-TOKEN", headerToken);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final String FORM = "application/x-www-form-urlencoded";

    @Test
    void aFormUnderTheFormCapReachesTheRoute() throws Exception {
        String body = "_csrf=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&note=" + "x".repeat(600);
        HttpResponse<String> res = post("/cap/ordinary", FORM, body, null);
        assertEquals(200, res.statusCode(), res.body());
        assertEquals("bytes=" + body.length() + " fields=2", res.body());
        assertEquals(200, post("/cap/anon", FORM, "name=" + "y".repeat(1000), null).statusCode(),
                "1005 bytes: under the 1024-byte form cap");
    }

    /** No session, no token: the bound applies before anyone is authenticated. */
    @Test
    void anAnonymousFormOverTheFormCapIs413() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/cap/anon"))
                .header("Content-Type", FORM).POST(HttpRequest.BodyPublishers.ofString("name=" + "y".repeat(2000)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(413, res.statusCode());
    }

    /** Refused at the capture (413) before the CSRF check could answer 403: nothing past 1 KiB is buffered pre-auth. */
    @Test
    void anOversizedFormIsRefusedBeforeTheCsrfCheck() throws Exception {
        assertEquals(413, post("/cap/ordinary", FORM, "to=" + "x".repeat(2000), null).statusCode(),
                "no token at all: still 413, the body was never parsed for one");
        assertEquals(403, post("/cap/ordinary", FORM, "to=x", null).statusCode(), "under the cap, CSRF decides");
        assertEquals(413, post("/cap/ordinary", FORM, "to=" + "x".repeat(2000), token).statusCode(),
                "a valid header token does not lift the form cap");
    }

    @Test
    void aChunkedFormOverTheCapIs413() throws Exception {
        HttpRequest chunked = HttpRequest.newBuilder(uri("/cap/anon")).header("Content-Type", FORM)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(
                        ("name=" + "y".repeat(5000)).getBytes(StandardCharsets.UTF_8))))
                .build();
        assertEquals(413, client.send(chunked, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    /** A declared length over the cap is answered at once — the server never waits for (or buffers) the body. */
    @Test
    void aHugeDeclaredLengthIsRefusedWithoutReadingTheBody() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /cap/anon HTTP/1.1\r\nHost: localhost\r\nContent-Type: " + FORM + "\r\n"
                    + "Content-Length: 999999999\r\nConnection: close\r\n\r\nname=a").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            String status = new String(in.readNBytes(12), StandardCharsets.US_ASCII);
            assertEquals("HTTP/1.1 413", status, "answered without the 999999999 bytes ever arriving");
        }
    }

    /**
     * max-body-bytes still governs a raw (non-form) body from a SIGNED-IN caller: 20 KB is fine, 70 KB is not. Since
     * 1.0.0-rc1 a caller who is not signed in — even one holding a valid CSRF token from an anonymous session — is held
     * to the 1 KiB pre-authentication cap whatever the body's type, before the CSRF check.
     */
    @Test
    void aRawBodyIsGovernedByTheBodyCapOnlyOnceSignedIn() throws Exception {
        assertEquals(413, post("/cap/ordinary", "text/plain", "z".repeat(20_000), token).statusCode(),
                "not signed in, valid token: the pre-auth cap");
        HttpResponse<String> small = post("/cap/ordinary", "text/plain", "z".repeat(1000), token);
        assertEquals(200, small.statusCode(), small.body());
        assertEquals("bytes=1000 fields=0", small.body());
        assertEquals(413, post("/cap/ordinary", "text/plain", "z".repeat(20_000), null).statusCode(),
                "no token: refused on its size before CSRF");
        assertEquals(403, post("/cap/ordinary", "text/plain", "z".repeat(1000), null).statusCode(),
                "a raw body under the cap is not read before CSRF refuses it");

        HttpResponse<String> login = post("/login", FORM, "_csrf=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                + "&username=admin&password=Route-Form-Cap-E2E-4", null);
        assertTrue(login.headers().firstValue("Location").orElse("").endsWith("/__admin"), "signed in");
        String signedInToken = tokenOf("/__admin/backups");
        HttpResponse<String> ok = post("/cap/ordinary", "text/plain", "z".repeat(20_000), signedInToken);
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals("bytes=20000 fields=0", ok.body());
        assertEquals(413, post("/cap/ordinary", "text/plain", "z".repeat(70_000), signedInToken).statusCode());
    }

    private String tokenOf(String page) throws Exception {
        HttpResponse<String> res = client.send(HttpRequest.newBuilder(uri(page)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = CSRF.matcher(res.body());
        assertTrue(m.find(), page + " carries a CSRF token");
        return m.group(1);
    }
}
