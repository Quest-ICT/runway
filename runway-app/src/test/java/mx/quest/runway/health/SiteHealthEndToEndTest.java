package mx.quest.runway.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The load-bearing proof for the per-site health probe, against a REAL server and the REAL filter chain.
 *
 * <p>The single most important assertion in this class: <b>a suspended site's {@code /__health} answers with
 * {@code 503} + JSON, not {@code SuspendedSiteFilter}'s plain-text body.</b> That is what proves the filter sits
 * at {@link mx.quest.runway.web.FilterOrder#CORE_SITE_HEALTH} (55) — OUTER of the gates it must describe. If the
 * endpoint ever drifts inside order 60 it would be short-circuited by those same gates and start reporting
 * nothing in exactly the states worth reporting; a mocked single-filter unit test cannot see that (the standing
 * lesson from the 0.2.1-beta10 HEAD defect: test the real path).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteHealthEndToEndTest {

    private static final String TOKEN = "health-probe-secret-token";
    private static Path external;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        external = Files.createTempDirectory("runway-site-health");
        YamlStore yaml = new YamlStore();
        // The probe answers on the site's OWN host; a RANDOM_PORT client sends Host: localhost.
        new SiteStore(mx.quest.runway.storage.StorageLayout.from(storageProps()), yaml)
                .save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH, List.of(Locale.ENGLISH),
                        null, SiteStatus.ACTIVE, false, null));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Site-Health-9");
        new UserStore(yaml).save(external.resolve("app-users.yml"),
                new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.health.token", () -> TOKEN);
        // Isolate the ORDERING proof from template seeding; the content check has its own unit coverage.
        registry.add("runway.health.content-check", () -> "false");
    }

    private static mx.quest.runway.storage.StorageProperties storageProps() {
        mx.quest.runway.storage.StorageProperties props = new mx.quest.runway.storage.StorageProperties();
        props.setExternal(external);
        return props;
    }

    @Autowired
    private SiteStore sites;

    @LocalServerPort
    private int port;

    @AfterEach
    void restoreActive() {
        sites.save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH, List.of(Locale.ENGLISH),
                null, SiteStatus.ACTIVE, false, null));
    }

    private void setState(SiteStatus status, boolean maintenance) {
        sites.save(new Site("acme", "Acme", List.of("localhost"), Locale.ENGLISH, List.of(Locale.ENGLISH),
                null, status, maintenance, maintenance ? "back soon" : null));
    }

    private HttpResponse<String> probe(String host, String token, String method) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/__health"));
        req = "HEAD".equals(method) ? req.method("HEAD", HttpRequest.BodyPublishers.noBody()) : req.GET();
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The content type, compared case-insensitively — the container normalizes the charset token's case. */
    private static void assertJson(HttpResponse<String> res) {
        String contentType = res.headers().firstValue("Content-Type").orElse("");
        assertEquals("application/json;charset=utf-8", contentType.toLowerCase(Locale.ROOT));
    }

    private HttpResponse<String> probe() throws Exception {
        return probe("localhost", null, "GET");
    }

    @Test
    void anActiveSiteIsUpAndThePublicShapeLeaksNothing() throws Exception {
        HttpResponse<String> res = probe();
        assertEquals(200, res.statusCode());
        assertJson(res);
        assertEquals("no-store", res.headers().firstValue("Cache-Control").orElse(null));
        assertTrue(res.headers().firstValue("X-Robots-Tag").orElse("").contains("noindex"));
        assertTrue(res.body().contains("\"status\":\"up\""), res.body());
        // The public shape carries status + time and NOTHING else — no cause, no site id, no checks.
        assertFalse(res.body().contains("\"state\""), "the cause must not be public: " + res.body());
        assertFalse(res.body().contains("\"site\""), "the site id must not be public: " + res.body());
        assertFalse(res.body().contains("\"checks\""), res.body());
        // A monitoring probe must never accumulate sessions on the box.
        assertTrue(res.headers().firstValue("Set-Cookie").isEmpty(), "the probe must not create a session");
    }

    @Test
    void aSuspendedSiteReportsDownInJsonInsteadOfBeingSwallowedByTheSuspendedFilter() throws Exception {
        setState(SiteStatus.SUSPENDED, false);
        HttpResponse<String> res = probe();

        assertEquals(503, res.statusCode());
        // THE proof of slot 55: SuspendedSiteFilter (order 60) would have written this plain text instead.
        assertFalse(res.body().contains("This site is temporarily unavailable."),
                "the probe was short-circuited by SuspendedSiteFilter — it is registered inside order 60: " + res.body());
        assertJson(res);
        assertTrue(res.body().contains("\"status\":\"down\""), res.body());
        assertTrue(res.headers().firstValue("Retry-After").isEmpty(),
                "suspension is indefinite — no Retry-After should be promised");
    }

    @Test
    void aSiteInMaintenanceReportsDownInJsonWithARetryAfter() throws Exception {
        setState(SiteStatus.ACTIVE, true);
        HttpResponse<String> res = probe();

        assertEquals(503, res.statusCode());
        assertFalse(res.body().contains("Down for maintenance"),
                "the probe was swallowed by MaintenanceSiteFilter (order 68): " + res.body());
        assertTrue(res.body().contains("\"status\":\"down\""), res.body());
        assertEquals("3600", res.headers().firstValue("Retry-After").orElse(null));
    }

    @Test
    void theTokenUnlocksTheCauseAndAWrongTokenSilentlyFallsBackToPublic() throws Exception {
        setState(SiteStatus.SUSPENDED, false);

        HttpResponse<String> detail = probe("localhost", TOKEN, "GET");
        assertEquals(503, detail.statusCode());
        assertTrue(detail.body().contains("\"state\":\"suspended\""), detail.body());
        assertTrue(detail.body().contains("\"site\":\"acme\""), detail.body());
        assertTrue(detail.body().contains("\"checks\""), detail.body());

        HttpResponse<String> wrong = probe("localhost", "not-the-token", "GET");
        assertEquals(503, wrong.statusCode(), "status must not depend on whether the token was accepted");
        assertFalse(wrong.body().contains("\"state\""), "a wrong token must degrade to the public shape");
        assertFalse(wrong.body().contains("\"site\""), wrong.body());
    }

    @Test
    void anUnmatchedHostIsNotAnsweredByTheCatchAllDefaultSite() throws Exception {
        // resolve() would fall back to the platform default site and report a DIFFERENT tenant green (false green);
        // the probe uses resolveExplicit, so a host that matches nothing is simply unknown.
        HttpResponse<String> res = probe("127.0.0.1", null, "GET");
        assertEquals(404, res.statusCode());
        assertTrue(res.body().contains("\"status\":\"unknown\""), res.body());
    }

    @Test
    void headCarriesTheRealContentLengthAndNoBody() throws Exception {
        HttpResponse<String> get = probe();
        HttpResponse<String> head = probe("localhost", null, "HEAD");

        assertEquals(200, head.statusCode());
        assertEquals("", head.body(), "a HEAD carries no body");
        long declared = head.headers().firstValueAsLong("Content-Length").orElse(-1L);
        assertEquals(get.body().getBytes(StandardCharsets.UTF_8).length, declared,
                "HEAD must report the length the GET would have carried (the beta10 rule)");
    }

    @Test
    void anUnsupportedMethodIsRejectedWithAllow() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/__health"))
                        .method("POST", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, res.statusCode());
        assertEquals("GET, HEAD", res.headers().firstValue("Allow").orElse(null));
    }
}
