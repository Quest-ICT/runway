package mx.quest.runway.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.CookieManager;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What a plugin route is handed for the bodies {@link RouteFormBodyE2ETest} does not cover — {@code PUT}, {@code PATCH}
 * and {@code DELETE} form bodies and a {@code multipart/form-data} {@code POST} — through a REAL Jetty and the full
 * production filter chain.
 *
 * <p>Two outer readers used to consume these bodies before the route saw them: Spring Boot's
 * {@code FormContentFilter} parses a {@code PUT}/{@code PATCH}/{@code DELETE} form into request parameters (reading the
 * stream), and the kernel's {@code MultipartFilter} parses a multipart body into parts. A plugin route now gets the
 * raw bytes in {@code body()} in every case, and {@code form()} for a form body whatever the method; a multipart body's
 * fields are not parsed for the plugin ({@code form()} stays empty, the bytes are in {@code body()}), so its CSRF token
 * travels in the {@code X-CSRF-TOKEN} header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "runway.endpoints.max-body-bytes=4096")
@Import(RouteBodyMethodsE2ETest.Routes.class)
class RouteBodyMethodsE2ETest {

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");
    private static final String BOUNDARY = "RunwayK4Boundary";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-route-body-methods-e2e");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        new SiteStore(layout, new YamlStore()).save(new Site("acme", "Acme Corp", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        Path templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("_master.html"), "<html><head></head><body></body></html>");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Route-Body-E2E-4");
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"), new User("app-master", "root", hash,
                "Root", "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    /** A kernel-side contributor standing in for a plugin's: echoes what each route was handed. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Routes {
        @Bean
        EndpointContributor bodyEchoProbe() {
            return new EndpointContributor() {
                @Override
                public String key() {
                    return "body-echo";
                }

                @Override
                public List<Route> routes() {
                    return List.of(new Route("PUT", "/body-echo", Realm.TENANT, null),
                            new Route("PATCH", "/body-echo", Realm.TENANT, null),
                            new Route("DELETE", "/body-echo", Realm.TENANT, null),
                            new Route("POST", "/body-echo", Realm.TENANT, null),
                            Route.anonymousPost("/body-echo/anon", null));
                }

                @Override
                public RouteResponse handle(RouteRequest request) {
                    return RouteResponse.text(200, request.method() + " form=" + request.form() + "\nquery="
                            + request.query() + "\nbody=" + new String(request.body(), StandardCharsets.UTF_8));
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

    private URI uri(String pathAndQuery) {
        return URI.create("http://localhost:" + port + pathAndQuery);
    }

    private HttpResponse<String> send(String method, String pathAndQuery, String contentType, String body,
                                      String headerToken) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(pathAndQuery)).header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (headerToken != null) {
            b.header("X-CSRF-TOKEN", headerToken);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String multipart(String... nameValuePairs) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            out.append("--").append(BOUNDARY).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(nameValuePairs[i]).append("\"\r\n\r\n")
                    .append(nameValuePairs[i + 1]).append("\r\n");
        }
        return out.append("--").append(BOUNDARY).append("--\r\n").toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void aFormBodyReachesTheRouteWhateverTheMethodWithTheTokenInTheHeader(String method) throws Exception {
        String body = "to=" + enc("ops@example.com") + "&note=" + enc("¡Hola!");
        HttpResponse<String> res = send(method, "/body-echo?q=1", "application/x-www-form-urlencoded", body, token);
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().startsWith(method + " form={to=[ops@example.com], note=[¡Hola!]}\nquery={q=[1]}"),
                res.body());
        assertTrue(res.body().endsWith("\nbody=" + body), "the raw body is intact: " + res.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void aFormBodyReachesTheRouteWhateverTheMethodWithTheTokenInTheBody(String method) throws Exception {
        String body = "_csrf=" + enc(token) + "&to=" + enc("ops@example.com");
        HttpResponse<String> res = send(method, "/body-echo", "application/x-www-form-urlencoded", body, null);
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("to=[ops@example.com]"), res.body());
        assertTrue(res.body().endsWith("\nbody=" + body), res.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void aFormBodyWithoutATokenIsStillRefused(String method) throws Exception {
        assertEquals(403, send(method, "/body-echo", "application/x-www-form-urlencoded", "to=x", null).statusCode());
    }

    @Test
    void aMultipartBodyReachesTheRouteRawWithTheTokenInTheHeader() throws Exception {
        String body = multipart("to", "ops@example.com", "note", "hi");
        HttpResponse<String> res = send("POST", "/body-echo", "multipart/form-data; boundary=" + BOUNDARY, body,
                token);
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().startsWith("POST form={}\n"), "multipart fields are not parsed into form(): "
                + res.body());
        assertTrue(res.body().endsWith("\nbody=" + body), "the raw multipart body is intact: " + res.body());
    }

    @Test
    void aMultipartTokenFieldIsNotReadSoTheTokenMustTravelInTheHeader() throws Exception {
        String body = multipart("_csrf", token, "to", "ops@example.com");
        assertEquals(403, send("POST", "/body-echo", "multipart/form-data; boundary=" + BOUNDARY, body, null)
                .statusCode());
    }

    @Test
    void aMultipartBodyOverTheCapIs413() throws Exception {
        String body = multipart("blob", "x".repeat(5000));
        assertEquals(413, send("POST", "/body-echo", "multipart/form-data; boundary=" + BOUNDARY, body, token)
                .statusCode());
    }

    @Test
    void anAnonymousRouteStillRefusesMultipart() throws Exception {
        assertEquals(415, send("POST", "/body-echo/anon", "multipart/form-data; boundary=" + BOUNDARY,
                multipart("name", "Ana"), null).statusCode());
    }
}
