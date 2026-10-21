package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * An uploaded SVG or XML file, and the site's SVG icon, reach the browser with {@code Content-Security-Policy:
 * sandbox} through the REAL filter chain (with the page CSP enforced), so a script inside one cannot run on the
 * site's origin when the file is opened directly; it stays inline, so it still works as an image. A unit test of
 * the controller cannot show that no filter drops or replaces the header on the way out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SvgSandboxEndToEndTest {

    private static final String SCRIPTED_SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(document.domain)</script></svg>";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-svg-sandbox");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        new SiteStore(layout, new YamlStore()).save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE));
        Path site = layout.siteDir("acme");
        Files.createDirectories(site.resolve("static/img"));
        Files.createDirectories(site.resolve("icons"));
        Files.writeString(site.resolve("static/img/evil.svg"), SCRIPTED_SVG);
        Files.writeString(site.resolve("static/evil.xml"), SCRIPTED_SVG);
        Files.writeString(site.resolve("static/site.css"), "body{}");
        Files.writeString(site.resolve("icons/favicon.svg"), SCRIPTED_SVG);
        // A platform administrator exists, so the server is set up and serves sites rather than the first-run wizard.
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"), new User("app-master", "root",
                PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Svg-Sandbox-9"), "Root", "", true, true,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.csp.mode", () -> "enforce");
    }

    @LocalServerPort
    private int port;

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void svgAndXmlAreSandboxedAndStayInline() throws Exception {
        for (String path : new String[] {"/static/img/evil.svg", "/static/evil.xml", "/favicon.svg"}) {
            HttpResponse<String> res = get(path);
            assertEquals(200, res.statusCode(), path);
            assertEquals(List.of("sandbox"), res.headers().allValues("Content-Security-Policy"), path);
            assertFalse(res.headers().firstValue("Content-Disposition").isPresent(), "inline: " + path);
            assertTrue(res.body().contains("<svg"), path);
        }
        assertTrue(get("/static/site.css").headers().allValues("Content-Security-Policy").isEmpty(),
                "a stylesheet needs no policy of its own");
    }

    /**
     * HEAD on an icon route answered 404 text/html (a route declared GET was not matched for HEAD), so a
     * crawler or CDN probing the icon with HEAD was told there was none. HEAD is answered like GET, without the body.
     */
    @Test
    void headIsAnsweredLikeGetWithoutTheBody() throws Exception {
        for (String path : new String[] {"/favicon.svg", "/static/img/evil.svg"}) {
            HttpResponse<String> got = get(path);
            HttpResponse<String> head = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, head.statusCode(), path);
            assertEquals(got.headers().firstValue("Content-Type"), head.headers().firstValue("Content-Type"), path);
            assertEquals(List.of("sandbox"), head.headers().allValues("Content-Security-Policy"), path);
            assertEquals(String.valueOf(got.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
                    head.headers().firstValue("Content-Length").orElse(""), "the length of the body: " + path);
            assertEquals("", head.body(), path);
        }
    }
}
