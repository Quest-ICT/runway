package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mx.quest.runway.platform.PlatformSettingsStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * With no default site, the default rule leaves only host names that serve no site: every site's host gets a 404.
 * Choosing a default site later opens its hosts at once (the platform setting is read per request).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleHostNoDefaultSiteEndToEndTest {

    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-console-host-nodefault");
        ConsoleHostEndToEndTest.seed(external, null);
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private PlatformSettingsStore platform;

    private int status(String host, String path) throws IOException {
        return RawHttp.send(port, "GET", host + ":" + port, path).status();
    }

    @Test
    void onlyHostsThatServeNoSiteReachTheConsoleAndADefaultSiteOpensItsOwnHosts() throws IOException {
        for (String host : List.of("main.test", "shop.test")) {
            assertEquals(404, status(host, "/app/login"), host);
            assertEquals(404, status(host, "/__app"), host);
        }
        assertEquals(200, status("localhost", "/app/login"));
        assertEquals(302, status("localhost", "/__app"));

        platform.update(current -> current.withDefaultSiteId("main"));
        try {
            assertEquals(200, status("main.test", "/app/login"), "the new default site's host");
            assertEquals(404, status("shop.test", "/app/login"), "another site's host stays refused");
        } finally {
            platform.update(current -> current.withDefaultSiteId(null));
        }
    }
}
