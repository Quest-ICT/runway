package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * The decisive strict-dynamic test: end-to-end against a REAL Jetty + the full egress filter chain + the real Thymeleaf
 * render of a kernel login page — the ONLY layer that proves the CSP header's nonce equals the nonce on every
 * rendered {@code <script>} across the {@code CspFilter}↔Thymeleaf boundary. (A mocked single-filter unit can't;
 * an earlier HEAD bug is the standing reminder.) It asserts: {@code /app/login}'s {@code script-src} carries a
 * nonce + {@code 'strict-dynamic'} + the {@code 'self'} fallback; every {@code <script>} in the body carries that
 * exact nonce; the page is {@code no-store}; and a second request mints a different nonce.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CspNonceE2ETest {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        // ACTIVE-mode boot (an app superadmin exists) so /app/login is the real app-realm login page. Force ENFORCE
        // so the emitted header is the enforcing Content-Security-Policy — exactly what production runs.
        Path external = Files.createTempDirectory("runway-csp-nonce-e2e");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Csp-E2E-Secret-9");
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"),
                new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.csp.mode", () -> "enforce");
    }

    @LocalServerPort
    private int port;

    private static final Pattern NONCE = Pattern.compile("'nonce-([A-Za-z0-9_-]{16,})'");

    private static String nonceOf(String csp) {
        Matcher m = NONCE.matcher(csp == null ? "" : csp);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void aHeadOnANoncedKernelPageStillCarriesTheCacheControlNoStore() throws Exception {
        // A reproduction with `curl -sI` (a HEAD) against a live deployment saw NO Cache-Control on /app/login. A HEAD
        // takes a different commit path (no body written), so the no-store must not depend on a body write.
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        URI uri = URI.create("http://localhost:" + port + "/app/login");

        HttpResponse<Void> head = client.send(
                HttpRequest.newBuilder(uri).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(200, head.statusCode());
        assertEquals("no-store", head.headers().firstValue("Cache-Control").orElse(null),
                "HEAD on a nonced kernel page must carry no-store too; got headers: " + head.headers().map());
    }

    @Test
    void theLoginPageHeaderNonceMatchesEveryBodyScriptAndStrictDynamicIsOn() throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        URI uri = URI.create("http://localhost:" + port + "/app/login");

        HttpResponse<String> res = client.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), "the app login page renders");

        String csp = res.headers().firstValue("Content-Security-Policy").orElse(null);
        assertNotNull(csp, "enforce-mode CSP header present");
        String nonce = nonceOf(csp);
        assertNotNull(nonce, "script-src carries a substituted nonce: " + csp);
        assertTrue(csp.contains("'strict-dynamic'"), "a nonced kernel login page gets strict-dynamic: " + csp);
        assertTrue(csp.contains("'self'"), "the 'self' old-browser fallback is retained: " + csp);
        // (The reCAPTCHA host fallback is contributed by the protection plugin, which this bare boot doesn't load;
        // the 'self' fallback above is what matters for the strict-dynamic-vs-fallback shape.)

        // Strip HTML comments first — one login-page comment literally contains the text "<script>" (explaining the
        // no-inline-script rule), which would otherwise be miscounted as an un-nonced script.
        String body = res.body().replaceAll("(?s)<!--.*?-->", "");
        long scripts = body.split("<script", -1).length - 1;
        long nonced = body.split("nonce=\"" + nonce + "\"", -1).length - 1;
        assertTrue(scripts >= 2, "the login page renders its scripts (captcha-v3, tabler, runway-chrome); got " + scripts);
        assertEquals(scripts, nonced,
                "every <script> in the rendered body must carry the header's nonce (" + scripts + " scripts, " + nonced + " nonced)");

        // A nonced strict-dynamic page must not be shared-cached and replayed with a stale nonce.
        assertEquals("no-store", res.headers().firstValue("Cache-Control").orElse(null), "kernel page is no-store");

        // A second request mints a DIFFERENT nonce — proving per-request generation end-to-end, not a cached string.
        HttpResponse<String> res2 = client.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String nonce2 = nonceOf(res2.headers().firstValue("Content-Security-Policy").orElse(null));
        assertNotNull(nonce2);
        assertNotEquals(nonce, nonce2, "each response mints a fresh nonce");
    }
}
