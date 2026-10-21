package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end, against a REAL Jetty + the FULL egress filter chain (Bandwidth → Compression → CSP → HeadInjection)
 * and the real {@code ResourceHttpRequestHandler} that serves {@code /__assets/**}: a {@code HEAD} on a
 * compressible console asset must report the resource's true (uncompressed) {@code Content-Length}, not {@code 0}.
 *
 * <p>This is the coverage the earlier single-filter unit tests could NOT give (and the reason the bug slipped:
 * they exercised one filter over a mocked producer, never the real handler behind the real filter stack). The
 * defect was {@code HeadInjectionFilter} buffering the body-less HEAD and re-stamping {@code Content-Length: 0}
 * from the empty buffer — invisible unless the whole chain runs against a handler that writes no HEAD body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssetHeadContentLengthIntegrationTest {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        // Minimal ACTIVE-mode boot (an app superadmin exists, so it is not the setup wizard): that wires the
        // operator console, /__assets, and the kernel HeadContributors (so HeadInjection's registry is non-empty
        // and it actually buffers — the precondition for the bug).
        Path external = Files.createTempDirectory("runway-asset-head");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Head-Test-Secret-9");
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"),
                new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @LocalServerPort
    private int port;

    /** Both are real classpath assets served at /__assets/**; both are over the compression min-size and js is compressible. */
    private static final List<String> COMPRESSIBLE_ASSETS =
            List.of("/__assets/captcha-v3.js", "/__assets/runway-chrome.js");

    @Test
    void headOnACompressibleConsoleAssetReturnsTheRealContentLengthNotZero() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        for (String asset : COMPRESSIBLE_ASSETS) {
            URI uri = URI.create("http://localhost:" + port + asset);

            // The true, uncompressed length: an identity GET's Content-Length equals the body it returns.
            HttpResponse<byte[]> get = client.send(
                    HttpRequest.newBuilder(uri).header("Accept-Encoding", "identity").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, get.statusCode(), asset);
            long realLength = get.body().length;
            assertTrue(realLength > 0, asset + " has a body");

            // A HEAD — even advertising compression — must report that same real length, and no body.
            HttpResponse<byte[]> head = client.send(
                    HttpRequest.newBuilder(uri).header("Accept-Encoding", "gzip, br")
                            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, head.statusCode(), asset);
            long headContentLength = head.headers().firstValueAsLong("Content-Length").orElse(-1L);
            assertEquals(realLength, headContentLength,
                    "HEAD " + asset + " must report the resource's real Content-Length, not 0");
            assertEquals(0, head.body().length, "a HEAD carries no body");
        }
    }
}
