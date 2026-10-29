package mx.quest.runway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * The slow-body limit on the running application, where it was missing in 1.1.0: the login forms of both realms and a
 * multipart post, trickled in, are answered {@code 408} within seconds instead of holding a request thread until the
 * idle timeout. Through the whole chain (Spring Security reads the login form), which a bare Jetty cannot stand in
 * for: there, a refused read that the chain treats as a client gone away was answered an empty {@code 200}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SlowRequestBodyEndToEndTest {

    private static final String HOST = "localhost";
    /** The console is never served on a site's host; this one is no site's. */
    private static final String CONSOLE_HOST = "127.0.0.1";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-slow-body");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        YamlStore yaml = new YamlStore();
        new SiteStore(StorageLayout.from(props), yaml).save(new Site("acme", "Acme", List.of(HOST), Locale.ENGLISH,
                List.of(Locale.ENGLISH), null, SiteStatus.ACTIVE, false, null));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Slow-Body-7x");
        new UserStore(yaml).save(external.resolve("app-users.yml"),
                new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.health.content-check", () -> "false");
        registry.add("runway.http.min-request-data-rate", () -> "1024");
    }

    @LocalServerPort
    private int port;

    /** Sends the head of a 1000-byte body, then one byte a second; returns the status line and how long it took. */
    private String drip(String path, String contentType, String bodyStart) throws Exception {
        String host = path.startsWith("/app/") ? CONSOLE_HOST : HOST;
        long started = System.nanoTime();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST " + path + " HTTP/1.1\r\nHost: " + host + "\r\nContent-Type: " + contentType
                    + "\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n" + bodyStart)
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            try {
                for (int i = 0; i < 20; i++) {
                    Thread.sleep(1000);
                    out.write('a');
                    out.flush();
                }
            } catch (IOException closedByServer) {
                // expected: answered and closed while the client was still trickling
            }
            socket.setSoTimeout(40_000);
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as(path + ": cut long before the idle timeout")
                    .isLessThan(Duration.ofSeconds(15));
            return response.isEmpty() ? "" : response.substring(0, response.indexOf("\r\n"));
        }
    }

    @Test
    void aTrickledSiteLoginIsAnswered408() throws Exception {
        assertThat(drip("/login", "application/x-www-form-urlencoded", "username="))
                .isEqualTo("HTTP/1.1 408 Request Timeout");
    }

    @Test
    void aTrickledConsoleLoginIsAnswered408() throws Exception {
        assertThat(drip("/app/login", "application/x-www-form-urlencoded", "username="))
                .isEqualTo("HTTP/1.1 408 Request Timeout");
    }

    @Test
    void aTrickledMultipartPostIsAnswered408() throws Exception {
        assertThat(drip("/app/login", "multipart/form-data; boundary=XyZ",
                "--XyZ\r\nContent-Disposition: form-data; name=\"username\"\r\n\r\n"))
                .isEqualTo("HTTP/1.1 408 Request Timeout");
    }

    @Test
    void aLoginSentAtOnceIsNotTouched() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            String body = "username=root&password=wrong";
            socket.getOutputStream().write(("POST /app/login HTTP/1.1\r\nHost: " + CONSOLE_HOST
                    + "\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: " + body.length()
                    + "\r\nConnection: close\r\n\r\n" + body).getBytes(StandardCharsets.US_ASCII));
            socket.setSoTimeout(10_000);
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertThat(response).as("refused for its missing CSRF token, as before: not a slow body")
                    .startsWith("HTTP/1.1 403");
        }
    }
}
