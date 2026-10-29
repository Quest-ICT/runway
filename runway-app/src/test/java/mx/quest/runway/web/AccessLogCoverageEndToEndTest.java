package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.quest.runway.log.LogKind;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.protect.EdgeGuard;
import mx.quest.runway.protect.EdgeVerdict;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Every request the abuse floor lets through is in the access log, however it is answered: in the platform's
 * {@code runway.access} stream and in the answering site's own log. Until 1.1.2 the access log sat innermost, and
 * each answer below except the plain page was given by a filter outside it, so none of them was logged: a login
 * attempt, a CSRF refusal, a suspended or maintenance site, a refused slow body, an oversized form refused unread,
 * an endpoint route. Floor rejections and the internal surfaces stay out.
 *
 * <p>Against a real Jetty and the real filter chain, over raw sockets, because the gap was the chain's shape.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessLogCoverageEndToEndTest {

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*?value=\"([^\"]*)\"");
    /** The test guard turns this path away at the floor. */
    private static final String FLOOR_REJECTED = "/floor-rejected";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-access-log");
        ConsoleHostEndToEndTest.seed(external, null);
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        SiteStore sites = new SiteStore(StorageLayout.from(props), new YamlStore());
        sites.save(new Site("sus", "Sus", List.of("sus.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.SUSPENDED, false, null));
        sites.save(new Site("mnt", "Mnt", List.of("mnt.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE, true, "Back soon"));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.health.content-check", () -> "false");
        registry.add("runway.http.min-request-data-rate", () -> "1024");
    }

    @TestConfiguration
    static class Beans {

        @Bean
        @Primary
        EdgeGuard floorRejectingOnePath() {
            return request -> FLOOR_REJECTED.equals(request.path()) ? EdgeVerdict.reject(429) : EdgeVerdict.pass();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private SiteLogService siteLogs;

    private Logger accessLogger;
    private ListAppender<ILoggingEvent> platformLines;

    @BeforeEach
    void attach() {
        accessLogger = (Logger) LoggerFactory.getLogger("runway.access");
        platformLines = new ListAppender<>();
        platformLines.start();
        accessLogger.addAppender(platformLines);
    }

    @AfterEach
    void detach() {
        accessLogger.detachAppender(platformLines);
    }

    @Test
    void aPageIsLogged() throws IOException {
        String path = "/page-" + System.nanoTime();
        assertEquals(404, get("main.test", path).status());
        assertLogged("main", "GET", path, 404);
    }

    @Test
    void aLoginPostWithoutItsFormTokenIsLogged() throws IOException {
        assertEquals(403, RawHttp.send(port, "POST", "main.test:" + port, "/login",
                "username=guess&password=guess").status());
        assertLogged("main", "POST", "/login", 403);
    }

    @Test
    void aFailedLoginAttemptIsLogged() throws IOException {
        RawHttp.Response page = get("main.test", "/login");
        assertEquals(200, page.status());
        Matcher token = CSRF.matcher(page.body());
        assertTrue(token.find(), "a form token on /login");
        RawHttp.Response attempt = RawHttp.send(port, "POST", "main.test:" + port, "/login",
                "username=nobody&password=wrong-guess&_csrf=" + URLEncoder.encode(token.group(1),
                        StandardCharsets.UTF_8), Map.of("Cookie", String.join("; ", page.cookies())));
        assertEquals(302, attempt.status(), "Spring Security answers the attempt itself, with a redirect");
        assertLogged("main", "POST", "/login", 302);
    }

    @Test
    void aSuspendedSitesRefusalIsLogged() throws IOException {
        String path = "/suspended-" + System.nanoTime();
        assertEquals(503, get("sus.test", path).status());
        assertLogged("sus", "GET", path, 503);
    }

    @Test
    void aSiteInMaintenanceIsLogged() throws IOException {
        String path = "/maintenance-" + System.nanoTime();
        assertEquals(503, get("mnt.test", path).status());
        assertLogged("mnt", "GET", path, 503);
    }

    @Test
    void anEndpointRouteIsLogged() throws IOException {
        int status = get("main.test", "/manifest.webmanifest").status();
        assertLogged("main", "GET", "/manifest.webmanifest", status);
    }

    @Test
    void anOversizedPutFormRefusedUnreadIsLogged() throws IOException {
        // The form-content filter answers 413 from the declared length, without reading the body or calling the chain.
        String path = "/put-form-" + System.nanoTime();
        assertEquals(413, RawHttp.send(port, "PUT", "main.test:" + port, path, null, Map.of(
                "Content-Type", "application/x-www-form-urlencoded", "Content-Length", "99999999")).status());
        assertLogged("main", "PUT", path, 413);
    }

    @Test
    void aRefusedSlowLoginBodyIsLogged() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST /login HTTP/1.1\r\nHost: main.test:" + port + "\r\nContent-Type: "
                    + "application/x-www-form-urlencoded\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n"
                    + "username=").getBytes(StandardCharsets.US_ASCII));
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
            assertTrue(response.startsWith("HTTP/1.1 408"), response.lines().findFirst().orElse(""));
        }
        assertLogged("main", "POST", "/login", 408);
    }

    @Test
    void aFloorRejectionStaysOutOfTheAccessLog() throws IOException {
        assertEquals(429, get("main.test", FLOOR_REJECTED).status());
        String control = "/after-floor-" + System.nanoTime();
        get("main.test", control);
        awaitSiteLine("main", control);
        assertFalse(platformText().contains(FLOOR_REJECTED), platformText());
        assertFalse(siteText("main").contains(FLOOR_REJECTED), "reported by runway.protect, throttled, instead");
    }

    @Test
    void theInternalSurfacesStayOut() throws IOException {
        get("main.test", "/app/login");
        get("127.0.0.1:" + port, "/app/login");
        String control = "/after-internal-" + System.nanoTime();
        get("main.test", control);
        awaitSiteLine("main", control);
        assertFalse(platformText().contains("/app/login"), platformText());
        assertFalse(siteText("main").contains("/app/login"));
    }

    // ---------------------------------------------------------------------------------------------------------------

    private RawHttp.Response get(String host, String path) throws IOException {
        return RawHttp.send(port, "GET", host.contains(":") ? host : host + ":" + port, path);
    }

    /**
     * Both sinks carry the request with its final status. Both are waited for: the line is written when the chain
     * unwinds, which can be after the client has read a response the application already completed and closed.
     */
    private void assertLogged(String siteId, String method, String path, int status) {
        String platform = " " + method + " " + path + " -> " + status + " (";
        await(() -> platformText().contains(platform), () -> "runway.access lacks [" + platform + "]: "
                + platformText());
        awaitSiteLine(siteId, "\t" + method + "\t" + path + "\t" + status);
    }

    private String platformText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : List.copyOf(platformLines.list)) {
            text.append(event.getFormattedMessage()).append('\n');
        }
        return text.toString();
    }

    private String siteText(String siteId) {
        return String.join("\n", siteLogs.tail(siteId, LogKind.ACCESS, 1000));
    }

    /** Waits (up to 15 s) until the site's access log has a line containing {@code text}. */
    private void awaitSiteLine(String siteId, String text) {
        await(() -> siteText(siteId).contains(text),
                () -> "no access line with [" + text + "] for " + siteId + ": " + siteText(siteId));
    }

    private static void await(BooleanSupplier condition, Supplier<String> failure) {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(failure.get());
    }
}
