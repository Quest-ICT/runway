package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
 * Before first-run setup (WIZARD mode, no {@code runway.security.app-master-id}) the console-host rule does not
 * apply: even on a host bound to a site that is not the default, a console path is sent to {@code /setup} as before,
 * never a 404, so the wizard is reachable however the server is addressed.
 *
 * <p>The status alone cannot prove it: in setup mode the wizard chain also redirects the error page of a refused
 * request to {@code /setup}. So the test also checks that the filter refused nothing (no refusal is logged).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class ConsoleHostWizardEndToEndTest {

    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-console-host-wizard");
        ConsoleHostEndToEndTest.seed(external, null);
        // Before setup there is no operator yet (a stored master without the pin fails the start, by design).
        Files.delete(external.resolve("app-users.yml"));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.console.hosts[0]", () -> "ops.test");
    }

    @LocalServerPort
    private int port;

    @Test
    void theWizardIsNotAffected(CapturedOutput output) throws IOException {
        int from = output.getAll().length();
        for (String path : new String[] {"/app/login", "/__app"}) {
            RawHttp.Response res = RawHttp.send(port, "GET", "shop.test:" + port, path);
            assertEquals(302, res.status(), path);
            assertTrue(res.header("location").endsWith("/setup"), res.header("location"));
        }
        assertEquals(200, RawHttp.send(port, "GET", "shop.test:" + port, "/setup").status());
        assertFalse(output.getAll().substring(from).contains("Operator console refused"),
                "the console-host rule must be inert before setup");
    }
}
