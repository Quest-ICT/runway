package mx.quest.runway.wizard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * WIZARD mode through a real Jetty: the open first-run {@code /setup} form is posted before any account
 * exists, so its body is held to the pre-authentication budget ({@code runway.endpoints.max-form-bytes}, here
 * 2 KiB) instead of the container's 16 MiB form limit. An incomplete form (no {@code siteAdminPassword}) shows a
 * body within the cap reaching the controller without completing setup; the one complete form, sent chunked at
 * exactly the cap, shows the captured fields arriving whole — with the {@link Restarter} mocked, so the completed
 * setup does not shut the test's server down for its relaunch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"runway.endpoints.max-form-bytes=2048"})
class SetupFormBodyLimitE2ETest {

    private static final int CAP = 2048;
    private static final String FORM = "application/x-www-form-urlencoded";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        // No runway.security.app-master-id: WIZARD mode.
        Path external = Files.createTempDirectory("runway-setup-body-e2e");
        registry.add("runway.storage.external", () -> external.toString());
    }

    @LocalServerPort
    private int port;

    @MockitoBean
    private Restarter restarter;

    /** An incomplete setup form (no siteAdminPassword), padded to exactly {@code length} bytes. */
    private static String incompleteSetup(int length) {
        String head = "username=root&password=Setup-Body-E2E-1&siteAdminUsername=admin&pad=";
        return head + "x".repeat(length - head.length());
    }

    private int post(String body, boolean chunked) throws Exception {
        return send(body, chunked).statusCode();
    }

    private HttpResponse<String> send(String body, boolean chunked) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher publisher = chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
                : HttpRequest.BodyPublishers.ofByteArray(bytes);
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/setup"))
                .header("Content-Type", FORM).POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * One ordered walk (setup can complete only once per server): over the cap is refused both ways; exactly the cap
     * reaches the controller (an incomplete form: redirected back to {@code /setup}, not 413); and a complete form,
     * chunked at exactly the cap, completes setup — its captured fields arrived whole.
     */
    @Test
    void theSetupFormIsBoundedAtTheCapAndAChunkedCompleteFormStillCompletesSetup() throws Exception {
        assertEquals(413, post(incompleteSetup(CAP + 1), false), "one byte over: refused before parsing");
        assertEquals(413, post(incompleteSetup(CAP + 1), true), "chunked, one byte over");
        int exact = post(incompleteSetup(CAP), false);
        assertTrue(exact != 413 && exact < 500, "exactly the cap reaches the controller: " + exact);
        verify(restarter, never()).restart();

        String head = "username=root&password=Setup-Body-E2E-1&siteAdminUsername=admin"
                + "&siteAdminPassword=Setup-Body-E2E-2&pad=";
        HttpResponse<String> done = send(head + "x".repeat(CAP - head.length()), true);
        assertEquals(200, done.statusCode(), done.body());
        assertTrue(done.body().contains("Setup complete"), "the chunked, captured form completed setup");
        verify(restarter).restart();
    }

    @Test
    void aHugeDeclaredLengthIsRefusedUnread() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /setup HTTP/1.1\r\nHost: localhost\r\nContent-Type: " + FORM + "\r\n"
                    + "Content-Length: 999999999\r\nConnection: close\r\n\r\nusername=a")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            assertEquals("HTTP/1.1 413", new String(in.readNBytes(12), StandardCharsets.US_ASCII));
        }
    }
}
