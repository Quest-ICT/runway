package mx.quest.runway.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The OAuth2 client-credentials exchange against a real HTTP server on loopback: what is posted, what is cached and
 * for how long, how a refusal is reported (with the provider's reason and never the secret), and which endpoints are
 * accepted at all.
 */
class OAuth2TokenClientTest {

    private static final String SECRET = "s3cr3t~value+/=";

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-28T22:00:00Z");

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    private HttpServer server;
    private final List<Map<String, String>> posted = new ArrayList<>();
    private final List<String> contentTypes = new ArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody;
    private volatile String location;
    private int issued;
    private final TestClock clock = new TestClock();
    private OAuth2TokenClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/tenant/oauth2/v2.0/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> fields = new LinkedHashMap<>();
            for (String pair : form.split("&")) {
                String[] kv = pair.split("=", 2);
                fields.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
            synchronized (posted) {
                posted.add(fields);
                contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
                issued++;
            }
            String body = responseBody != null ? responseBody
                    : "{\"token_type\":\"Bearer\",\"expires_in\":3600,\"access_token\":\"token-" + issued + "\"}";
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        // The application's own client, so a test of redirects or timeouts tests what runs in production.
        client = new OAuth2TokenClient(OAuth2TokenClient.httpClient(), clock);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OAuth2TokenClient.Request request() {
        return new OAuth2TokenClient.Request(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/tenant/oauth2/v2.0/token",
                "client-123", SECRET, "https://outlook.office365.com/.default");
    }

    @Test
    void postsAClientCredentialsGrantAndReturnsTheToken() throws Exception {
        assertThat(client.token(request())).isEqualTo("token-1");

        assertThat(posted).hasSize(1);
        assertThat(posted.getFirst()).containsExactly(
                Map.entry("grant_type", "client_credentials"),
                Map.entry("client_id", "client-123"),
                Map.entry("client_secret", SECRET), // form-encoded on the wire, decoded intact
                Map.entry("scope", "https://outlook.office365.com/.default"));
        assertThat(contentTypes.getFirst()).isEqualTo("application/x-www-form-urlencoded");
    }

    @Test
    void aBlankScopeIsNotSent() throws Exception {
        OAuth2TokenClient.Request r = request();
        client.token(new OAuth2TokenClient.Request(r.tokenUrl(), r.clientId(), r.clientSecret(), " "));

        assertThat(posted.getFirst()).doesNotContainKey("scope");
    }

    @Test
    void aTokenIsReusedUntilFiveMinutesBeforeItExpires() throws Exception {
        assertThat(client.token(request())).isEqualTo("token-1");
        clock.now = clock.now.plusSeconds(3600 - 300 - 1);
        assertThat(client.token(request())).isEqualTo("token-1");
        assertThat(posted).hasSize(1);

        clock.now = clock.now.plusSeconds(1);
        assertThat(client.token(request())).isEqualTo("token-2");
        assertThat(posted).hasSize(2);
    }

    @Test
    void aShortLivedTokenIsRenewedAtHalfItsLifetime() throws Exception {
        responseBody = "{\"token_type\":\"Bearer\",\"expires_in\":\"60\",\"access_token\":\"short\"}";
        client.token(request());
        clock.now = clock.now.plusSeconds(29);
        client.token(request());
        assertThat(posted).hasSize(1);

        clock.now = clock.now.plusSeconds(1);
        client.token(request());
        assertThat(posted).hasSize(2);
    }

    @Test
    void changingAnyPartOfTheRequestOrForgettingAsksAgain() throws Exception {
        client.token(request());
        OAuth2TokenClient.Request r = request();
        assertThat(client.token(new OAuth2TokenClient.Request(r.tokenUrl(), r.clientId(), "rotated", r.scope())))
                .isEqualTo("token-2");
        assertThat(client.token(new OAuth2TokenClient.Request(r.tokenUrl(), "other", "rotated", r.scope())))
                .isEqualTo("token-3");
        client.forget();
        assertThat(client.token(new OAuth2TokenClient.Request(r.tokenUrl(), "other", "rotated", r.scope())))
                .isEqualTo("token-4");
    }

    @Test
    void aRefusalNamesTheStatusAndTheProvidersReasonButNeverTheSecret() {
        status = 401;
        responseBody = "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: Invalid client "
                + "secret provided.\\r\\nTrace ID: abc\"}";

        assertThatThrownBy(() -> client.token(request()))
                .isInstanceOf(OAuth2TokenClient.TokenException.class)
                .hasMessageContaining("127.0.0.1")
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("invalid_client")
                .hasMessageContaining("AADSTS7000215: Invalid client secret provided. Trace ID: abc")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void aRefusalIsNotCached() throws Exception {
        status = 500;
        responseBody = "oops";
        assertThatThrownBy(() -> client.token(request())).hasMessageContaining("HTTP 500");

        status = 200;
        responseBody = null;
        assertThat(client.token(request())).isEqualTo("token-2");
    }

    @Test
    void aResponseWithoutABearerTokenIsRefused() {
        responseBody = "{\"token_type\":\"Bearer\",\"expires_in\":3600}";
        assertThatThrownBy(() -> client.token(request())).hasMessageContaining("has no access_token");

        responseBody = "{\"token_type\":\"mac\",\"access_token\":\"t\"}";
        assertThatThrownBy(() -> client.token(request())).hasMessageContaining("not a bearer token");

        responseBody = "<html>login page</html>";
        assertThatThrownBy(() -> client.token(request())).hasMessageContaining("has no access_token");
    }

    @Test
    void anOversizedResponseIsRefused() {
        responseBody = "{\"access_token\":\"" + "a".repeat(OAuth2TokenClient.MAX_RESPONSE_BYTES) + "\"}";

        assertThatThrownBy(() -> client.token(request())).hasMessageContaining("larger than");
    }

    @Test
    void aServerThatStallsInTheMiddleOfTheBodyIsCutOffAtTheDeadline() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/stall", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 1000); // headers at once, then the body never comes
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("{\"access_token\":".getBytes(StandardCharsets.UTF_8));
                out.flush();
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException closed) {
                // the client gave up
            }
        });
        OAuth2TokenClient bounded = new OAuth2TokenClient(OAuth2TokenClient.httpClient(), clock,
                Duration.ofSeconds(2));
        OAuth2TokenClient.Request r = request();
        long start = System.nanoTime();
        try {
            assertThatThrownBy(() -> bounded.token(new OAuth2TokenClient.Request(
                    r.tokenUrl().replace("/tenant/oauth2/v2.0/token", "/stall"), r.clientId(), r.clientSecret(),
                    r.scope())))
                    .isInstanceOf(OAuth2TokenClient.TokenException.class)
                    .hasMessageContaining("did not complete within 2 seconds");
        } finally {
            release.countDown();
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void sendersWaitingOnAStalledProviderShareOneRequestAndOneDeadline() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger stalled = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/stall", exchange -> {
            stalled.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try {
                release.await(30, TimeUnit.SECONDS); // no answer at all
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        OAuth2TokenClient bounded = new OAuth2TokenClient(OAuth2TokenClient.httpClient(), clock,
                Duration.ofSeconds(2));
        OAuth2TokenClient.Request r = request();
        OAuth2TokenClient.Request stalling = new OAuth2TokenClient.Request(
                r.tokenUrl().replace("/tenant/oauth2/v2.0/token", "/stall"), r.clientId(), r.clientSecret(), r.scope());
        java.util.concurrent.ExecutorService senders = java.util.concurrent.Executors.newFixedThreadPool(4);
        long start = System.nanoTime();
        try {
            List<java.util.concurrent.Future<Throwable>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(senders.submit(() -> {
                    try {
                        bounded.token(stalling);
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            for (var result : results) {
                assertThat(result.get(20, TimeUnit.SECONDS)).isInstanceOf(OAuth2TokenClient.TokenException.class);
            }
        } finally {
            release.countDown();
            senders.shutdownNow();
        }
        // One deadline for all four (2 s, plus a moment), not four in turn.
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(4500));
        assertThat(stalled.get()).isEqualTo(1);
    }

    @Test
    void concurrentSendersGetOneTokenFromOneRequest() throws Exception {
        java.util.concurrent.ExecutorService senders = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<String>> tokens = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tokens.add(senders.submit(() -> client.token(request())));
            }
            for (var token : tokens) {
                assertThat(token.get(20, TimeUnit.SECONDS)).isEqualTo("token-1");
            }
        } finally {
            senders.shutdownNow();
        }
        assertThat(issued).isEqualTo(1);
    }

    @Test
    void aRedirectIsNotFollowedSoTheSecretGoesNowhereElse() {
        status = 302;
        location = "https://elsewhere.example/steal";
        responseBody = "";

        assertThatThrownBy(() -> client.token(request())).hasMessageContaining("HTTP 302");
        // 307 is the one a following client would answer by POSTing the form, secret included, again.
        status = 307;
        location = "/tenant/oauth2/v2.0/token";
        assertThatThrownBy(() -> client.token(request())).hasMessageContaining("HTTP 307");
        assertThat(posted).hasSize(2); // one POST per call: nothing re-sent
    }

    @Test
    void anUnreachableEndpointIsReportedAsATokenFailure() {
        server.stop(0);

        assertThatThrownBy(() -> client.token(request()))
                .isInstanceOf(OAuth2TokenClient.TokenException.class)
                .hasMessageContaining("OAuth2 token request to 127.0.0.1 failed");
    }

    @Test
    void onlyHttpsOrLoopbackHttpEndpointsAreAccepted() throws Exception {
        assertThat(OAuth2TokenClient.endpoint(endpoint("https://login.microsoftonline.com/t/oauth2/v2.0/token")))
                .hasHost("login.microsoftonline.com");
        assertThat(OAuth2TokenClient.endpoint(endpoint("http://localhost:9000/token"))).hasHost("localhost");
        assertThat(OAuth2TokenClient.endpoint(endpoint("http://[::1]:9000/token"))).hasPort(9000);

        for (String refused : List.of("http://login.example.com/token", "http://10.0.0.5/token",
                "ftp://login.example.com/token", "https://user:pw@login.example.com/token",
                "https://login.example.com/token#x", "login.example.com/token", "https://", "not a url")) {
            assertThatThrownBy(() -> OAuth2TokenClient.endpoint(endpoint(refused)))
                    .as(refused).isInstanceOf(OAuth2TokenClient.TokenException.class);
        }
    }

    @Test
    void anIncompleteRequestIsRefusedBeforeAnyCall() {
        for (OAuth2TokenClient.Request incomplete : List.of(
                new OAuth2TokenClient.Request(null, "c", "s", null),
                new OAuth2TokenClient.Request("https://l.example/t", " ", "s", null),
                new OAuth2TokenClient.Request("https://l.example/t", "c", null, null))) {
            assertThatThrownBy(() -> client.token(incomplete)).hasMessageContaining("needs a token endpoint");
        }
        assertThat(posted).isEmpty();
    }

    @Test
    void theRequestNeverPrintsItsSecret() {
        assertThat(request().toString()).doesNotContain(SECRET).contains("client-123");
    }

    private static OAuth2TokenClient.Request endpoint(String url) {
        return new OAuth2TokenClient.Request(url, "c", "s", null);
    }
}
