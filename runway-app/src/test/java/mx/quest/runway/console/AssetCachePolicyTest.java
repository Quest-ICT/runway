package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Pure-unit contract of {@link AssetCachePolicy} (the per-request {@code /__assets} {@code Cache-Control}) and
 * {@link AssetVersion} (the {@code ?v=} token). The over-the-wire behaviour — that the resource handler does not
 * overwrite the header, 304 revalidation, a 404 — is in {@code ConsoleCacheControlIntegrationTest}.
 */
class AssetCachePolicyTest {

    private static String policyFor(String uri, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (query != null) {
            request.setQueryString(query);
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                request.addParameter(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(new AssetCachePolicy().preHandle(request, response, new Object()), "never blocks the request");
        return response.getHeader("Cache-Control");
    }

    @Test
    void versionedRequestsAreCachedForADay() {
        assertEquals("max-age=86400", policyFor("/__assets/page-editor.js", "v=1760000000000"));
        assertEquals("max-age=86400", policyFor("/__assets/vendor/tabler.min.css", "x=1&v=2"));
    }

    @Test
    void unversionedRequestsMustRevalidate() {
        assertEquals("no-cache", policyFor("/__assets/page-editor.js", null));
        assertEquals("no-cache", policyFor("/__assets/page-editor.js", "v="), "a blank token is no token");
        assertEquals("no-cache", policyFor("/__assets/page-editor.js", "v= "), "nor is a whitespace one");
        assertEquals("no-cache", policyFor("/__assets/page-editor.js", "ver=1"), "only the v parameter counts");
        // What a template renders when its model lacks assetVersion: day-caching it would pin the URL forever.
        assertEquals("no-cache", policyFor("/__assets/page-editor.js", "v=null"), "not a token");
        assertEquals("no-cache", policyFor("/__assets/page-editor.js", "v=1.2.0"), "tokens are digits only");
        assertEquals("no-cache", policyFor("/__assets/runway.svg", null));
    }

    @Test
    void fontsKeepTheDayLongCacheBecauseStylesheetsReachThemRelatively() {
        for (String font : new String[] {"fa-solid-900.woff2", "x.woff", "x.ttf", "x.otf", "x.eot", "X.WOFF2"}) {
            assertEquals("max-age=86400", policyFor("/__assets/vendor/fontawesome/webfonts/" + font, null), font);
        }
        assertEquals("no-cache", policyFor("/__assets/woff2/readme.txt", null), "a directory named like a font");
        assertEquals("no-cache", policyFor("/__assets/fonts.woff2/x.js", null), "the extension is the file's");
        assertEquals("no-cache", policyFor("/__assets/woff2", null), "no extension at all");
    }

    @Test
    void theTokenComesFromTheBuildTimeElseOneProcessWideFallback() {
        Properties p = new Properties();
        p.setProperty("time", Instant.parse("2026-10-17T12:00:00Z").toString());
        assertEquals(Long.toString(Instant.parse("2026-10-17T12:00:00Z").toEpochMilli()),
                AssetVersion.of(new BuildProperties(p)));
        String fallback = AssetVersion.of(null);
        assertTrue(fallback.matches("\\d+"), "digits only: " + fallback);
        assertEquals(fallback, AssetVersion.of(new BuildProperties(new Properties())),
                "no build time is the same fallback");
        assertEquals(fallback, AssetVersion.of(null), "one value per JVM, so the shell and the bean always agree");
    }

    @Test
    void urlAppendsTheTokenAsTheVParameter() {
        AssetVersion version = AssetVersion.fixed("42");
        assertEquals("42", version.value());
        assertEquals("/__assets/runway.svg?v=42", version.url("/__assets/runway.svg"));
        assertEquals("/__assets/x.js?a=1&v=42", version.url("/__assets/x.js?a=1"));
    }
}
