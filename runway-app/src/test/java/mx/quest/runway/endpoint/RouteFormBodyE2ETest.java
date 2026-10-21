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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The form body of a <b>CSRF-checked</b> (non-anonymous) {@code POST} plugin route reaches
 * {@link RouteRequest#form()} and {@link RouteRequest#body()} whichever way the token travels — through a REAL Jetty
 * and the full production filter chain. MockMvc cannot prove this: its mock request parses parameters from a
 * byte array it can hand out again, while Jetty's {@code getParameter()} (what Spring Security's {@code CsrfFilter}
 * calls to find a token in the body) <em>consumes</em> the body stream. Before the fix a token sent as the
 * {@code _csrf} form field left the route with an empty {@code form()} (found by contact 2.0.0's SMTP test).
 *
 * <p>Also pinned: a query-string field never leaks into {@code form()}, even when the body carries the same name
 * (the two stay apart), the body-size cap still answers {@code 413}, and an anonymous route still works.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "runway.endpoints.max-body-bytes=4096")
@Import(RouteFormBodyE2ETest.Routes.class)
class RouteFormBodyE2ETest {

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-route-form-e2e");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        new SiteStore(layout, new YamlStore()).save(new Site("acme", "Acme Corp", List.of("localhost"),
                Locale.ENGLISH, List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        Path templates = layout.siteDir("acme").resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("_master.html"), "<html><head></head><body></body></html>");
        // ACTIVE mode (an app superadmin exists), or every request is sent to the first-run wizard.
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Route-Form-E2E-3");
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"), new User("app-master", "root", hash,
                "Root", "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    /** A kernel-side contributor standing in for a plugin's: echoes what the route was handed. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Routes {
        @Bean
        EndpointContributor formEchoProbe() {
            return new EndpointContributor() {
                @Override
                public String key() {
                    return "form-echo";
                }

                @Override
                public List<Route> routes() {
                    return List.of(new Route("POST", "/form-echo/ordinary", Realm.TENANT, null),
                            Route.anonymousPost("/form-echo/anon", null));
                }

                @Override
                public RouteResponse handle(RouteRequest request) {
                    return RouteResponse.text(200, "form=" + request.form() + "\nquery=" + request.query()
                            + "\nbody=" + new String(request.body(), StandardCharsets.UTF_8));
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
        assertEquals(200, login.statusCode(), "the tenant login page renders and opens a session");
        Matcher m = CSRF.matcher(login.body());
        assertTrue(m.find(), "the login page carries a CSRF token");
        token = m.group(1);
    }

    private URI uri(String pathAndQuery) {
        return URI.create("http://localhost:" + port + pathAndQuery);
    }

    private HttpResponse<String> post(String pathAndQuery, String body, String headerToken) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(pathAndQuery))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (headerToken != null) {
            b.header("X-CSRF-TOKEN", headerToken);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    void aTokenInTheFormBodyLeavesEveryFieldInForm() throws Exception {
        String body = "_csrf=" + enc(token) + "&to=" + enc("ops@example.com") + "&note=" + enc("¡Hola, señor!");
        HttpResponse<String> res = post("/form-echo/ordinary", body, null);
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("to=[ops@example.com]"), res.body());
        assertTrue(res.body().contains("note=[¡Hola, señor!]"), res.body());
        assertTrue(res.body().contains("_csrf=[" + token + "]"), "the token field is a body field too: " + res.body());
        assertTrue(res.body().contains("\nbody=" + body), "the raw body is intact: " + res.body());
    }

    @Test
    void aTokenInTheHeaderLeavesEveryFieldInFormToo() throws Exception {
        String body = "to=" + enc("ops@example.com") + "&note=" + enc("¡Hola, señor!");
        HttpResponse<String> res = post("/form-echo/ordinary", body, token);
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("form={to=[ops@example.com], note=[¡Hola, señor!]}"), res.body());
        assertTrue(res.body().contains("\nbody=" + body), res.body());
    }

    @Test
    void aQueryFieldNeverLeaksIntoFormEvenUnderTheSameName() throws Exception {
        String body = "_csrf=" + enc(token) + "&to=body%40example.com&to=second%40example.com";
        HttpResponse<String> res = post("/form-echo/ordinary?to=query%40example.com&only=q", body, null);
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("to=[body@example.com, second@example.com]"), res.body());
        assertTrue(res.body().contains("query={to=[query@example.com], only=[q]}"), res.body());
        assertTrue(!res.body().split("\n")[0].contains("query@example.com"), "query value leaked into form: " + res.body());
        assertTrue(!res.body().split("\n")[0].contains("only"), "query-only name leaked into form: " + res.body());
    }

    @Test
    void theQueryStringAloneCannotCarryTheTokenIntoForm() throws Exception {
        // The token in the query string satisfies CSRF (Spring reads parameters), but form() stays the body alone.
        HttpResponse<String> res = post("/form-echo/ordinary?_csrf=" + enc(token), "to=x", null);
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().startsWith("form={to=[x]}\n"), res.body());
    }

    @Test
    void aMissingTokenIsStillRefused() throws Exception {
        assertEquals(403, post("/form-echo/ordinary", "to=x", null).statusCode());
        assertEquals(403, post("/form-echo/ordinary", "_csrf=wrong&to=x", null).statusCode());
    }

    @Test
    void aBodyOverTheCapIsStill413() throws Exception {
        String body = "_csrf=" + enc(token) + "&to=" + "x".repeat(5000);
        assertEquals(413, post("/form-echo/ordinary", body, null).statusCode());
        assertEquals(413, post("/form-echo/ordinary", "to=" + "x".repeat(5000), token).statusCode());
        // Chunked (no Content-Length): the cap is enforced while reading, not only from the declared length.
        HttpRequest chunked = HttpRequest.newBuilder(uri("/form-echo/ordinary"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(
                        ("to=" + "x".repeat(5000)).getBytes(StandardCharsets.UTF_8))))
                .build();
        assertEquals(413, client.send(chunked, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void aMalformedBodyIs400WithAHeaderTokenAnd403WithABodyToken() throws Exception {
        assertEquals(400, post("/form-echo/ordinary", "to=%zz", token).statusCode());
        assertEquals(403, post("/form-echo/ordinary", "_csrf=" + enc(token) + "&to=%zz", null).statusCode(),
                "a body that cannot be parsed offers no token");
    }

    @Test
    void anAnonymousRouteStillGetsItsForm() throws Exception {
        HttpResponse<String> res = post("/form-echo/anon?q=1", "name=Ana", null);
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().startsWith("form={name=[Ana]}\nquery={q=[1]}"), res.body());
    }
}
