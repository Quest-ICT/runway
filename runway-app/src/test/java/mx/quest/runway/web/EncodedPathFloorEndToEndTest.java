package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mx.quest.runway.plugin.EssentialFloor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * With the essential-plugin floor failed (an installed but unloadable {@code protection} plugin), every site answers
 * {@code 503} and the application realm stays reachable: on every spelling of its paths (an encoded one is the same
 * console to Spring) and on its password recovery, which the realm definition once left out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EncodedPathFloorEndToEndTest {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-encoded-floor");
        ConsoleHostEndToEndTest.seed(external, "main");
        Path plugin = Files.createDirectories(external.resolve("plugins").resolve("protection"));
        Files.writeString(plugin.resolve("plugin.yml"),
                "id: protection\nversion: 1.0.0\nessential: true\nenabled: false\n", StandardCharsets.UTF_8);
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private EssentialFloor floor;

    private int status(String path) throws IOException {
        return RawHttp.send(port, "GET", "main.test:" + port, path).status();
    }

    @Test
    void theApplicationRealmStaysReachableOnEverySpellingAndOnRecovery() throws IOException {
        assertTrue(floor.failed(), "the fixture fails the floor");
        assertEquals(503, status("/"), "a site's page is held");
        assertEquals(503, status("/login"), "so is the site's own console");
        for (String path : List.of("/app/login", "/app/%6Cogin", "/app/forgot-password", "/app/forgot-p%61ssword",
                "/app/reset")) {
            assertEquals(200, status(path), path);
        }
        assertEquals(302, status("/__app"));
        assertEquals(302, status("/%5F%5Fapp/sites"));
    }
}
