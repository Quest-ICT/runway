package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
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
 * Self-managed TLS end-to-end against a REAL Jetty: the HTTPS connector handshakes with the bundled test
 * keystore, and the plain-HTTP listener redirects everything except the ACME webroot. This is the coverage
 * MockMvc cannot give — an actual socket, an actual TLS handshake, an actual second connector.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TlsIntegrationTest {

    private static final String PW = "Tls-Test-Secret-9";
    private static int redirectPort;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-tls");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode(PW);
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"),
                new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));

        // The checked-in localhost keystore, copied to a real file path (TlsStartup validates readability).
        Path keystore = external.resolve("test-keystore.p12");
        try (InputStream in = TlsIntegrationTest.class.getResourceAsStream("/tls/test-keystore.p12")) {
            Files.copy(in, keystore, StandardCopyOption.REPLACE_EXISTING);
        }
        Path acme = Files.createDirectories(external.resolve("acme-challenges"));
        Files.writeString(acme.resolve("test-token"), "ACME-OK", StandardCharsets.UTF_8);
        try (ServerSocket free = new ServerSocket(0)) {
            redirectPort = free.getLocalPort();
        }

        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.tls.enabled", () -> "true");
        registry.add("runway.tls.keystore", () -> keystore.toString());
        registry.add("runway.tls.keystore-password", () -> "test-password");
        registry.add("runway.tls.redirect-http.enabled", () -> "true");
        registry.add("runway.tls.redirect-http.port", () -> String.valueOf(redirectPort));
        registry.add("runway.tls.redirect-http.acme-challenges-dir", () -> acme.toString());
    }

    @LocalServerPort
    private int httpsPort;

    @org.springframework.beans.factory.annotation.Autowired
    private TlsCertificateReloader reloader;

    @org.springframework.beans.factory.annotation.Value("${runway.tls.keystore}")
    private String keystorePath;

    /** A client trusting the self-signed test certificate (subject CN/SAN = localhost). */
    private static HttpClient trustingClient() throws Exception {
        TrustManager trustAll = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[] {trustAll}, null);
        return HttpClient.newBuilder().sslContext(ssl).build();
    }

    @Test
    void theServerTerminatesTlsItself() throws Exception {
        HttpResponse<String> health = trustingClient().send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + httpsPort + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode(), "a real TLS handshake reached the app");
        assertTrue(health.body().contains("UP"));
    }

    @Test
    void thePlainListenerRedirectsToHttps() throws Exception {
        HttpResponse<String> plain = HttpClient.newHttpClient().send( // follow-redirects is NEVER by default
                HttpRequest.newBuilder(URI.create("http://localhost:" + redirectPort + "/some/page?q=1")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(301, plain.statusCode());
        String location = plain.headers().firstValue("Location").orElse("");
        assertEquals("https://localhost:" + httpsPort + "/some/page?q=1", location,
                "the Location carries the ACTUAL bound HTTPS port (resolved per request, not a startup snapshot)");
    }

    @Test
    void theAcmeWebrootIsExemptFromTheRedirect() throws Exception {
        HttpResponse<String> challenge = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + redirectPort + "/.well-known/acme-challenge/test-token")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, challenge.statusCode(), "the challenge is served, not redirected");
        assertEquals("ACME-OK", challenge.body());
    }

    @Test
    void rewritingTheKeystoreHotReloadsTheCertificateWithoutRestart() throws Exception {
        int before = reloader.reloadCount();

        // Simulate a renewal: rewrite the watched keystore with a fresh copy of the (same) certificate.
        Path keystore = Path.of(keystorePath);
        try (InputStream in = TlsIntegrationTest.class.getResourceAsStream("/tls/test-keystore.p12")) {
            Files.copy(in, keystore, StandardCopyOption.REPLACE_EXISTING);
        }

        // The watcher fires on its own thread (debounced) — wait for the reload to be observed.
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        while (reloader.reloadCount() == before && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        assertTrue(reloader.reloadCount() > before, "a keystore change should drive a live certificate reload");

        // And the server keeps serving TLS after the reload — the connector was not broken.
        HttpResponse<String> health = trustingClient().send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + httpsPort + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode(), "TLS still serves after a hot certificate reload");
    }
}
