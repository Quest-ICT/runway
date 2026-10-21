package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * With {@code runway.console.hosts} set, the operator console answers only on the listed host names (case and port
 * ignored), and everything else gets a 404 — the default site's host and unbound hosts included. Same two sites as
 * {@link ConsoleHostEndToEndTest}: {@code main} (default, {@code main.test}) and {@code shop} ({@code shop.test}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class ConsoleHostListEndToEndTest {

    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-console-host-list");
        ConsoleHostEndToEndTest.seed(external, "main");
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        // Written as an operator might: mixed case and a port, which are both ignored.
        registry.add("runway.console.hosts[0]", () -> "Ops.Test:9999");
        registry.add("runway.console.hosts[1]", () -> "127.0.0.1");
    }

    @LocalServerPort
    private int port;

    private RawHttp.Response get(String host, String path) throws IOException {
        return RawHttp.send(port, "GET", host + ":" + port, path);
    }

    @Test
    void theConsoleAnswersOnTheListedHosts() throws IOException {
        for (String host : List.of("ops.test", "OPS.TEST", "127.0.0.1")) {
            assertEquals(200, get(host, "/app/login").status(), host);
            assertEquals(200, get(host, "/app/forgot-password").status(), host);
            assertEquals(200, get(host, "/app/reset").status(), host);
            assertEquals(302, get(host, "/__app").status(), host);
        }
    }

    @Test
    void everyOtherHostGets404TheDefaultSitesAndUnboundOnesIncluded() throws IOException {
        for (String host : List.of("main.test", "shop.test", "localhost", "unbound.test")) {
            for (String path : List.of("/__app", "/__app/sites", "/app/login", "/app/logout",
                    "/app/forgot-password", "/app/reset")) {
                assertEquals(404, get(host, path).status(), path + " on " + host);
            }
            assertEquals(404, RawHttp.send(port, "HEAD", host + ":" + port, "/app/login").status(), host);
            assertEquals(404, RawHttp.send(port, "POST", host + ":" + port, "/app/login",
                    "username=root&password=x").status(), host);
        }
    }

    @Test
    void aSitesOwnConsoleIsUnchanged() throws IOException {
        RawHttp.Response admin = get("shop.test", "/__admin");
        assertEquals(302, admin.status());
        assertTrue(admin.header("location").endsWith("/login"), admin.header("location"));
        assertEquals(200, get("main.test", "/login").status());
    }

    @Test
    void theLogLineSaysTheHostIsNotListed(CapturedOutput output) throws IOException {
        int from = output.getAll().length();
        get("www.shop.test", "/app/login");
        get("www.shop.test", "/app/login");
        String log = output.getAll().substring(from);
        String line = "Operator console refused on host 'www.shop.test'";
        assertEquals(1, log.split(java.util.regex.Pattern.quote(line), -1).length - 1, log);
        assertTrue(log.contains("it is not listed in runway.console.hosts"), log);
        assertTrue(log.contains("'shop'"), log);
    }
}
