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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A request for a missing console asset is an ordinary 404, and the log must say nothing about it.
 *
 * <p>The defect: the resource handler answers a miss with {@code sendError(404)}, which hands the response to the
 * container's error handling; the buffering egress filters ({@code HeadInjectionFilter}, {@code CompressionFilter})
 * then wrote their (empty) buffered body into that response, and Jetty logged an {@code EofException} stack trace
 * per miss — harmless to the client, but noise that buries real errors (and a cheap way to flood the log).
 * Needs a real server: the exception is Jetty's, a mock response never throws it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class AssetNotFoundLogTest {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-asset-404");
        // A provisioned install (an application superadmin exists), so no first-run redirect stands in front.
        String hash = mx.quest.runway.security.PasswordEncoders.argon2id(
                mx.quest.runway.security.PasswordStrength.STANDARD).encode("Asset-404-Test-9");
        new mx.quest.runway.security.UserStore(new mx.quest.runway.storage.YamlStore()).save(
                external.resolve("app-users.yml"),
                new mx.quest.runway.security.User("app-master", "root", hash, "Root", "", true, true,
                        mx.quest.runway.spi.model.Realm.APPLICATION,
                        java.util.Set.of(mx.quest.runway.security.Roles.SYSTEM_SUPERADMIN), java.util.List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @LocalServerPort
    private int port;

    private HttpResponse<String> get(String path, String acceptEncoding) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (acceptEncoding != null) {
            request.header("Accept-Encoding", acceptEncoding);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertQuiet(CapturedOutput output, int from) {
        String log = output.getAll().substring(from);
        assertFalse(log.contains("EofException"), "a 404 must not log an EofException:\n" + log);
        assertFalse(log.contains("\tat org.eclipse.jetty"), "nor any Jetty stack trace:\n" + log);
        assertFalse(log.contains(" ERROR "), "nor any ERROR line:\n" + log);
    }

    @Test
    void aMissingAssetIsA404WithNoStackTraceInTheLog(CapturedOutput output) throws Exception {
        int from = output.getAll().length();

        HttpResponse<String> miss = get("/__assets/no-such-file.js", null);
        HttpResponse<String> versioned = get("/__assets/no-such-file.js?v=1", null);
        HttpResponse<String> nested = get("/__assets/vendor/none/missing.css", null);

        assertEquals(404, miss.statusCode());
        assertEquals(404, versioned.statusCode());
        assertEquals(404, nested.statusCode());
        assertQuiet(output, from);
    }

    @Test
    void aMissingAssetIsQuietWhenTheClientAcceptsCompressionToo(CapturedOutput output) throws Exception {
        int from = output.getAll().length();

        HttpResponse<String> miss = get("/__assets/no-such-file.css", "br, gzip");

        assertEquals(404, miss.statusCode());
        assertQuiet(output, from);
    }

    @Test
    void anExistingAssetIsStillServedWhole(CapturedOutput output) throws Exception {
        int from = output.getAll().length();

        HttpResponse<String> hit = get("/__assets/runway.svg", null);

        assertEquals(200, hit.statusCode());
        assertTrue(hit.body().contains("<svg"), "the asset body is intact");
        assertEquals(String.valueOf(hit.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
                hit.headers().firstValue("Content-Length").orElse(null), "and its length is right");
        assertQuiet(output, from);
    }
}
