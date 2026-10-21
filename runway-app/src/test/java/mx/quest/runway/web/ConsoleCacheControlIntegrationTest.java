package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Locale;
import java.util.Set;
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
 * The authenticated console areas ({@code /__app/**}, {@code /__admin/**}) must never be cacheable — including the
 * <b>session-creating</b> entry-point response, which is the case that slipped.
 *
 * <p><b>Why this needs a real server.</b> The defect only appears when a request arrives with <b>no</b> session
 * cookie: the container mints a {@code JSESSIONID} and stamps a legacy {@code Expires: Thu, 01 Jan 1970 …}, and
 * Spring Security's {@code CacheControlHeadersWriter} <b>bails out entirely</b> when the response already carries
 * an {@code Expires}/{@code Pragma} (verified in its bytecode) — so nothing wrote {@code Cache-Control} at all.
 * Reusing an existing session restores it, which is why only a cold, cookie-less request reproduces it. Reported
 * from a live deployment against {@code 0.5.0-beta2}, where a browser's first cold visit hit exactly this shape.
 *
 * <p><b>Do NOT "simplify" these to a HEAD.</b> A {@code HEAD} does <b>not</b> mint a session, so it never walks
 * the failing path: measured on a live deployment, {@code HEAD /__app} returned the full
 * {@code no-cache, no-store, max-age=0, must-revalidate} on the <b>unfixed</b> and fixed builds <b>alike</b>. A
 * HEAD-based check therefore certifies a broken build as fixed — it is what hid this defect from a
 * {@code curl -I} verification habit for weeks. The cold call below is a real {@code GET} and asserts a
 * {@code Set-Cookie} came back, which is what proves it was the session-<em>creating</em> request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleCacheControlIntegrationTest {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-console-cache");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Console-Cache-9");
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"),
                new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @LocalServerPort
    private int port;

    /** A client that does NOT follow redirects, so the 302 entry-point response itself is inspected. */
    private static HttpClient client() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private HttpResponse<Void> get(HttpClient client, String path, String cookie) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (cookie != null) {
            req.header("Cookie", cookie);
        }
        return client.send(req.build(), HttpResponse.BodyHandlers.discarding());
    }

    private static void assertNoStore(HttpResponse<?> res, String what) {
        String cacheControl = res.headers().firstValue("Cache-Control").orElse(null);
        assertNotNull(cacheControl, what + " must carry a Cache-Control; headers were " + res.headers().map());
        assertTrue(cacheControl.toLowerCase(Locale.ROOT).contains("no-store"),
                what + " must forbid storing; got: " + cacheControl);
    }

    @Test
    void theSessionCreatingConsoleEntryPointIsNotCacheable() throws Exception {
        HttpClient client = client();

        // COLD: no cookie at all — the container mints a session (this is the shape that lost Cache-Control).
        HttpResponse<Void> cold = get(client, "/__app", null);
        assertEquals(302, cold.statusCode(), "an unauthenticated console hit redirects to the login");
        String setCookie = cold.headers().firstValue("Set-Cookie").orElse(null);
        assertNotNull(setCookie, "the cold request must be the session-CREATING one for this to be the real shape");
        assertNoStore(cold, "the session-creating /__app entry point");

        // WARM: same request reusing that session — this shape always worked; it must keep working.
        String jsessionId = setCookie.split(";", 2)[0];
        assertNoStore(get(client, "/__app", jsessionId), "the session-reusing /__app entry point");
    }

    @Test
    void theOtherAuthenticatedConsoleEntryPointsAreNotCacheableEither() throws Exception {
        // A live deployment showed the same shape on /__app/users and /__admin. Each cold (cookie-less) request mints its own session.
        for (String path : List.of("/__app/users", "/__admin")) {
            assertNoStore(get(client(), path, null), "the session-creating " + path + " entry point");
        }
    }

    @Test
    void consoleAssetsKeepTheirCacheability() throws Exception {
        // The guard must NOT reach the self-hosted console assets — they are cached on purpose (?v= busted), and
        // even an unversioned one may be STORED (no-cache = revalidate), never no-store.
        for (String path : List.of("/__assets/runway-chrome.js", "/__assets/runway-chrome.js?v=1")) {
            HttpResponse<Void> asset = get(client(), path, null);
            assertEquals(200, asset.statusCode());
            String cacheControl = asset.headers().firstValue("Cache-Control").orElse("");
            assertTrue(!cacheControl.toLowerCase(Locale.ROOT).contains("no-store"),
                    "console assets must stay cacheable; got: " + cacheControl + " on " + path);
        }
    }

    // --- /__assets cache policy (AssetCachePolicy) — over the real Jetty + filter chain + resource handler ------

    private String cacheControl(String path) throws Exception {
        HttpResponse<Void> res = get(client(), path, null);
        assertEquals(200, res.statusCode(), path);
        return res.headers().allValues("Cache-Control").toString();
    }

    @Test
    void aVersionedAssetIsCachedForADay() throws Exception {
        for (String path : List.of("/__assets/page-editor.js?v=1760000000000", "/__assets/vendor/tabler.min.css?v=7",
                "/__assets/runway.svg?v=7")) {
            assertEquals("[max-age=86400]", cacheControl(path), path);
        }
    }

    @Test
    void anUnversionedAssetMustBeRevalidatedSoAMissedReferenceIsNeverStaleForADay() throws Exception {
        // Any reference that forgot its ?v= (including a plugin's own template) degrades to correct-but-slower.
        for (String path : List.of("/__assets/page-editor.js", "/__assets/vendor/tabler.min.css",
                "/__assets/runway.svg", "/__assets/page-editor.js?v=", "/__assets/page-editor.js?x=1")) {
            assertEquals("[no-cache]", cacheControl(path), path);
        }
    }

    @Test
    void fontsKeepTheirDayLongCacheBecauseAStylesheetCannotVersionThem() throws Exception {
        // FontAwesome's CSS reaches its fonts through url(../webfonts/…), which can never carry ?v=.
        assertEquals("[max-age=86400]", cacheControl("/__assets/vendor/fontawesome/webfonts/fa-solid-900.woff2"));
    }

    @Test
    void anUnversionedAssetRevalidatesCheaply() throws Exception {
        HttpResponse<Void> first = get(client(), "/__assets/page-editor.js", null);
        String lastModified = first.headers().firstValue("Last-Modified").orElse(null);
        assertNotNull(lastModified, "a validator is what makes no-cache cheap");
        HttpResponse<Void> again = client().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/__assets/page-editor.js"))
                .header("If-Modified-Since", lastModified).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(304, again.statusCode(), "unchanged → 304, no body");
    }

    @Test
    void aMissingVersionedAssetIsNotCachedForADay() throws Exception {
        // A 404 must not tell a browser to keep the miss for a day (e.g. mid-upgrade, before the file exists).
        HttpResponse<Void> res = get(client(), "/__assets/no-such-file.js?v=1", null);
        assertEquals(404, res.statusCode());
        String cacheControl = res.headers().allValues("Cache-Control").toString();
        assertTrue(!cacheControl.contains("max-age=86400"), "a 404 is not day-cached; got " + cacheControl);
    }
}
