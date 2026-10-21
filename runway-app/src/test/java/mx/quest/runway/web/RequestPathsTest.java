package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The path helper and the realm definitions built on it: decoding, the two spellings, and that the application
 * chain's matcher patterns and {@link AppRealmPaths#isAppRealm} describe one set.
 */
class RequestPathsTest {

    private static MockHttpServletRequest request(String uri, String servletPath, String pathInfo) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (servletPath != null) {
            request.setServletPath(servletPath);
        }
        request.setPathInfo(pathInfo);
        return request;
    }

    @Test
    void normalizedIsTheContainersDecodedServletPathPlusPathInfo() {
        assertEquals("/__app/sites", RequestPaths.normalized(request("/%5F%5Fapp/sites", "/__app/sites", null)));
        assertEquals("/__app/sites", RequestPaths.normalized(request("/%5F%5Fapp/sites", "", "/__app/sites")));
        assertEquals("/__app/sites", RequestPaths.normalized(request("/%5F%5Fapp/sites", "/__app", "/sites")));
    }

    @Test
    void withNoServletPathItDecodesTheUriItself() {
        assertEquals("/__app/sites", RequestPaths.normalized(request("/%5F%5Fapp/sites", null, null)));
        assertEquals("/app/login", RequestPaths.normalized(request("/app/%6Cogin", null, null)));
    }

    @Test
    void rawIsTheUriAsSentWithoutTheContextPath() {
        MockHttpServletRequest request = request("/ctx/%5F%5Fapp", "/__app", null);
        request.setContextPath("/ctx");
        assertEquals("/%5F%5Fapp", RequestPaths.raw(request));
        assertEquals("/__app", RequestPaths.normalized(request));
    }

    @Test
    void decodeMirrorsTheContainer() {
        assertEquals("/__app/sites", RequestPaths.decode("/%5F%5Fapp/sites"));
        assertEquals("/__app/sites", RequestPaths.decode("/%5f%5fapp/sites"));
        assertEquals("/__app/sites", RequestPaths.decode("/__app;jsessionid=x/sites"));
        assertEquals("/__app/sites", RequestPaths.decode("/__app/sites;x=y"));
        assertEquals("/__app/sites", RequestPaths.decode("/__app/./sites"));
        assertEquals("/page", RequestPaths.decode("/__app/../page"));
        assertEquals("/x", RequestPaths.decode("/../x"));
        assertEquals("/__app/", RequestPaths.decode("/__app/x/.."));
        assertEquals("/", RequestPaths.decode("/"));
        assertEquals("/café", RequestPaths.decode("/caf%C3%A9"));
        assertEquals("/a%zz", RequestPaths.decode("/a%zz"), "a malformed escape is kept as written");
        assertEquals("/__app;x/sites", RequestPaths.decode("/__app%3Bx/sites"), "an encoded ; is data, not a param");
        assertNull(RequestPaths.decode(null));
    }

    @Test
    void eitherMatchesAsksBothSpellings() {
        // Raw: the console. Decoded: a public page. A protective check sees the console; an exemption must not.
        MockHttpServletRequest dotted = request("/__app/../page", "/page", null);
        assertTrue(RequestPaths.eitherMatches(dotted, AppRealmPaths::isAppRealm));
        assertFalse(AppRealmPaths.isAppRealm(RequestPaths.normalized(dotted)));
        // Raw: nothing. Decoded: the console.
        MockHttpServletRequest encoded = request("/%5F%5Fadmin/pages", "/__admin/pages", null);
        assertTrue(RequestPaths.eitherMatches(encoded, TenantConsolePaths::isConsolePage));
        assertFalse(RequestPaths.eitherMatches(request("/about", "/about", null), AppRealmPaths::isAppRealm));
    }

    @Test
    void theAppRealmIncludesPasswordRecovery() {
        for (String path : List.of("/__app", "/__app/", "/__app/sites", "/app/login", "/app/logout",
                "/app/forgot-password", "/app/reset", "/actuator", "/actuator/health")) {
            assertTrue(AppRealmPaths.isAppRealm(path), path);
        }
        for (String path : List.of("/__apple", "/actuatorX", "/app/login2", "/app/other", "/app/", "/login", "/",
                "/__admin")) {
            assertFalse(AppRealmPaths.isAppRealm(path), path);
        }
        assertFalse(AppRealmPaths.isAppRealm(null));
        assertTrue(AppRealmPaths.isOperatorConsole("/app/reset"));
        assertFalse(AppRealmPaths.isOperatorConsole("/actuator/health"), "monitoring is not the operator console");
    }

    @Test
    void theAppChainsMatcherAndTheRealmPredicateAreOneSet() {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        List<String> samples = List.of("/__app", "/__app/", "/__app/sites", "/__app/a/b/c", "/__apple", "/app/login",
                "/app/logout", "/app/forgot-password", "/app/reset", "/app/login2", "/app/other", "/app",
                "/actuator", "/actuator/health", "/actuator/health/liveness", "/actuatorX", "/", "/login",
                "/forgot-password", "/__admin", "/x/__app");
        for (String path : samples) {
            boolean chain = Arrays.stream(AppRealmPaths.securityMatchers())
                    .anyMatch(pattern -> parser.parse(pattern).matches(PathContainer.parsePath(path)));
            assertEquals(chain, AppRealmPaths.isAppRealm(path), path);
        }
    }

    @Test
    void theTenantConsoleIncludesPasswordRecovery() {
        for (String path : List.of("/__admin", "/__admin/pages", "/login", "/logout", "/forgot-password", "/reset",
                "/__assets/app.css")) {
            assertTrue(TenantConsolePaths.isConsole(path), path);
        }
        assertFalse(TenantConsolePaths.isConsolePage("/__assets/app.css"), "an asset is not a console page");
        for (String path : List.of("/__adminx", "/resets", "/about", "/", "/__assetsx")) {
            assertFalse(TenantConsolePaths.isConsole(path), path);
        }
        assertFalse(TenantConsolePaths.isConsole(null));
    }
}
