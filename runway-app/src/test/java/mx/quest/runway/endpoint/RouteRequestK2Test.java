package mx.quest.runway.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import mx.quest.runway.platform.TestPlatformSettings;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SurfacePosture;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.CidrSet;
import mx.quest.runway.web.TrustedForwardedHeaderFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * SPI 0.5.0: what the dispatcher puts into a {@link RouteRequest} — the query string apart
 * from the form body, lowercase headers, a site-negotiated locale, the gated client IP and origin — and the
 * anonymous-route rules (form body only, no principal while the handler runs, registration refusals, the CSRF
 * matcher's exact scope).
 */
class RouteRequestK2Test {

    @TempDir
    Path tmp;
    private SiteResolver siteResolver;
    private final AtomicReference<RouteRequest> seen = new AtomicReference<>();
    private final AtomicReference<Authentication> principalDuringHandle = new AtomicReference<>();

    private static final Site SITE = new Site("acme", "Acme", List.of("acme.test"), Locale.ENGLISH,
            List.of(Locale.ENGLISH, Locale.forLanguageTag("es"), Locale.FRENCH), null, null);

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        siteResolver = new SiteResolver(new SiteStore(StorageLayout.from(props), new YamlStore()),
                TestPlatformSettings.empty());
        siteResolver.bind(SITE);
    }

    @AfterEach
    void tearDown() {
        siteResolver.clear();
        SecurityContextHolder.clearContext();
    }

    private EndpointContributor capture(Route... routes) {
        return new EndpointContributor() {
            @Override
            public String key() {
                return "capture";
            }

            @Override
            public List<Route> routes() {
                return List.of(routes);
            }

            @Override
            public RouteResponse handle(RouteRequest request) {
                seen.set(request);
                principalDuringHandle.set(SecurityContextHolder.getContext().getAuthentication());
                return RouteResponse.text(200, "ok");
            }
        };
    }

    private MockHttpServletResponse dispatch(EndpointContributor contributor, MockHttpServletRequest request)
            throws Exception {
        EndpointDispatchFilter filter = new EndpointDispatchFilter(
                new EndpointContributorRegistry(List.of(contributor)), siteResolver, 1_000_000);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest request(String method, String uri, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        request.setQueryString(query);
        request.setServerName("acme.test");
        request.setScheme("http");
        request.setServerPort(80);
        return request;
    }

    private static MockHttpServletRequest formPost(String uri, String query, String body) {
        MockHttpServletRequest request = request("POST", uri, query);
        request.setContentType("application/x-www-form-urlencoded; charset=UTF-8");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    // --- query vs form ------------------------------------------------------------------------------------

    @Test
    void theQueryIsTheUrlQueryStringOnlyAndTheFormBodyIsSeparate() throws Exception {
        dispatch(capture(new Route("POST", "/f", Realm.TENANT, null)),
                formPost("/f", "a=1&a=2&both=fromQuery&sp=x+y", "name=Ana+Mar%C3%ADa&both=fromForm&email=a%40b.c&flag"));
        RouteRequest r = seen.get();
        assertEquals(Map.of("a", List.of("1", "2"), "both", List.of("fromQuery"), "sp", List.of("x y")), r.query());
        assertEquals(List.of("Ana María"), r.form().get("name"), "UTF-8 percent-decoding, + as space");
        assertEquals(List.of(""), r.form().get("flag"), "a field without = has an empty value");
        assertEquals(Optional.of("fromQuery"), r.param("both"), "param prefers the query");
        assertEquals(Optional.of("a@b.c"), r.param("email"), "then the form");
        assertEquals(Optional.empty(), r.param("absent"));
        assertTrue(new String(r.body(), StandardCharsets.UTF_8).startsWith("name="), "the raw body is still there");
    }

    @Test
    void aNonFormBodyFillsNoFormFields() throws Exception {
        MockHttpServletRequest json = request("POST", "/f", null);
        json.setContentType("application/json");
        json.setContent("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        dispatch(capture(new Route("POST", "/f", Realm.TENANT, null)), json);
        assertEquals(Map.of(), seen.get().form());
        assertEquals(Map.of(), seen.get().query());
        assertEquals("{\"a\":1}", new String(seen.get().body(), StandardCharsets.UTF_8));
    }

    @Test
    void aMalformedEscapeOrTooManyFieldsIsA400AndTheHandlerNeverRuns() throws Exception {
        MockHttpServletResponse bad = dispatch(capture(Route.get("/g", Realm.TENANT)), request("GET", "/g", "q=%zz"));
        assertEquals(400, bad.getStatus());
        assertNull(seen.get());
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= EndpointDispatchFilter.MAX_PARAMETERS; i++) {
            many.append("f").append(i).append("=1&");
        }
        MockHttpServletResponse tooMany = dispatch(capture(new Route("POST", "/f", Realm.TENANT, null)),
                formPost("/f", null, many.toString()));
        assertEquals(400, tooMany.getStatus());
        assertEquals("no-store", tooMany.getHeader("Cache-Control"));
        assertNull(seen.get());
    }

    // --- headers ------------------------------------------------------------------------------------------

    @Test
    void headerNamesAreLowercaseAndLookupsIgnoreCase() throws Exception {
        MockHttpServletRequest request = request("GET", "/g", null);
        request.addHeader("User-Agent", "UA/1");
        request.addHeader("X-Custom", "v");
        dispatch(capture(Route.get("/g", Realm.TENANT)), request);
        Map<String, String> headers = seen.get().headers();
        assertTrue(headers.containsKey("user-agent"));
        assertTrue(headers.keySet().stream().allMatch(k -> k.equals(k.toLowerCase(Locale.ROOT))), headers.toString());
        assertEquals("UA/1", headers.get("User-Agent"), "an old plugin reading 'User-Agent' still finds it");
        assertEquals("v", headers.get("x-custom"));
    }

    // --- locale -------------------------------------------------------------------------------------------

    @Test
    void theLocaleFollowsLangThenCookieThenAcceptLanguageThenTheSiteDefault() throws Exception {
        EndpointContributor c = capture(Route.get("/g", Realm.TENANT));
        MockHttpServletRequest explicit = request("GET", "/g", "lang=fr");
        explicit.setCookies(new Cookie("runway_lang_acme", "es"));
        explicit.addHeader("Accept-Language", "es");
        dispatch(c, explicit);
        assertEquals(Locale.FRENCH, seen.get().locale(), "?lang wins");

        MockHttpServletRequest cookie = request("GET", "/g", "lang=de"); // not a site locale: ignored
        cookie.setCookies(new Cookie("runway_lang_acme", "es"));
        cookie.addHeader("Accept-Language", "fr");
        dispatch(c, cookie);
        assertEquals("es", seen.get().locale().getLanguage(), "then the site's preference cookie");

        MockHttpServletRequest browser = request("GET", "/g", null);
        browser.addHeader("Accept-Language", "de, fr;q=0.8");
        browser.setPreferredLocales(List.of(Locale.GERMAN, Locale.FRENCH));
        dispatch(c, browser);
        assertEquals(Locale.FRENCH, seen.get().locale(), "then the first Accept-Language the site serves");

        MockHttpServletRequest nothing = request("GET", "/g", null);
        nothing.setPreferredLocales(List.of(Locale.GERMAN)); // the container's default, with no header sent
        dispatch(c, nothing);
        assertEquals(Locale.ENGLISH, seen.get().locale(), "else the site default — never a locale it does not serve");
    }

    // --- client IP and origin through the real trusted-proxy gate ----------------------------------------

    private RouteRequest throughTheGate(String peer, boolean trusted, String scheme, int port) throws Exception {
        TrustedForwardedHeaderFilter gate = new TrustedForwardedHeaderFilter(
                CidrSet.of(trusted ? List.of("10.0.0.0/8") : List.of()), true);
        EndpointDispatchFilter dispatch = new EndpointDispatchFilter(
                new EndpointContributorRegistry(List.of(capture(Route.get("/g", Realm.TENANT)))), siteResolver,
                1_000_000);
        MockHttpServletRequest request = request("GET", "/g", null);
        request.setRemoteAddr(peer);
        request.setScheme(scheme);
        request.setServerPort(port);
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "public.example");
        gate.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> dispatch.doFilter(req, res, new MockFilterChain()));
        return seen.get();
    }

    @Test
    void fromATrustedProxyTheForwardedAddressSchemeAndHostApply() throws Exception {
        RouteRequest r = throughTheGate("10.1.2.3", true, "http", 8080);
        assertEquals("203.0.113.9", r.clientIp());
        assertEquals("https://public.example", r.origin());
        assertFalse(r.headers().containsKey("x-forwarded-for"), "forwarded headers never reach a plugin");
    }

    @Test
    void fromAnUntrustedPeerTheRawConnectionStandsAndTheSpoofedHeadersAreHidden() throws Exception {
        RouteRequest r = throughTheGate("198.51.100.7", false, "http", 8080);
        assertEquals("198.51.100.7", r.clientIp(), "never the X-Forwarded-For a client sent");
        assertEquals("http://acme.test:8080", r.origin(), "raw scheme, host and non-default port");
        assertFalse(r.headers().containsKey("x-forwarded-for"));
        assertFalse(r.headers().containsKey("x-forwarded-proto"));
    }

    @Test
    void theDispatcherTakesTheClientIpFromTheGatedSourceNeverFromAHeader() throws Exception {
        // Defence in depth: even if a forwarded header ever reached the dispatcher, it is not read.
        MockHttpServletRequest request = request("GET", "/g", null);
        request.setRemoteAddr("198.51.100.7");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        request.addHeader("X-Real-IP", "203.0.113.10");
        dispatch(capture(Route.get("/g", Realm.TENANT)), request);
        assertEquals("198.51.100.7", seen.get().clientIp());
    }

    @Test
    void theOriginOmitsTheDefaultPortAndBracketsIpv6() {
        MockHttpServletRequest https = request("GET", "/", null);
        https.setScheme("HTTPS");
        https.setServerName("Acme.Test");
        https.setServerPort(443);
        assertEquals("https://acme.test", EndpointDispatchFilter.origin(https));
        MockHttpServletRequest v6 = request("GET", "/", null);
        v6.setServerName("::1");
        v6.setServerPort(8443);
        assertEquals("http://[::1]:8443", EndpointDispatchFilter.origin(v6));
    }

    // --- anonymous routes ---------------------------------------------------------------------------------

    @Test
    void anAnonymousRouteRunsWithNoPrincipalAndTheCallersContextIsRestored() throws Exception {
        Authentication signedIn = new UsernamePasswordAuthenticationToken("admin", "n/a",
                List.of(() -> "ROLE_ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(signedIn);
        MockHttpServletResponse response = dispatch(
                capture(Route.anonymousPost("/contact/send", SurfacePosture.safeDefault())),
                formPost("/contact/send", null, "email=a%40b.c"));
        assertEquals(200, response.getStatus());
        assertNull(principalDuringHandle.get(), "the handler never sees the visitor's identity");
        assertEquals(Optional.of("a@b.c"), seen.get().param("email"));
        assertSame(signedIn, SecurityContextHolder.getContext().getAuthentication(), "restored afterwards");
    }

    @Test
    void anOrdinaryRouteKeepsThePrincipal() throws Exception {
        Authentication signedIn = new UsernamePasswordAuthenticationToken("admin", "n/a",
                List.of(() -> "ROLE_ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(signedIn);
        dispatch(capture(new Route("POST", "/f", Realm.TENANT, null)), formPost("/f", null, "a=1"));
        assertSame(signedIn, principalDuringHandle.get());
    }

    @Test
    void anAnonymousRouteAcceptsOnlyAFormBody() throws Exception {
        EndpointContributor c = capture(Route.anonymousPost("/contact/send", null));
        for (String type : new String[] {"application/json", "multipart/form-data; boundary=x", "text/plain", null}) {
            MockHttpServletRequest request = request("POST", "/contact/send", null);
            if (type != null) {
                request.setContentType(type);
            }
            request.setContent("email=a".getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse response = dispatch(c, request);
            assertEquals(415, response.getStatus(), String.valueOf(type));
            assertEquals("no-store", response.getHeader("Cache-Control"));
        }
        assertNull(seen.get(), "the handler never ran");
    }

    @Test
    void theRouteRecordKeepsItsOldShapeAndAnonymousIsOff() {
        Route old = new Route("POST", "/f", Realm.TENANT, null);
        assertFalse(old.anonymous());
        assertTrue(Route.anonymousPost("/f", null).anonymous());
        assertEquals("POST", Route.anonymousPost("/f", null).method());
    }

    @Test
    void registrationRefusesAnAnonymousRouteThatIsNotPost() {
        IllegalStateException kernel = assertThrows(IllegalStateException.class,
                () -> new EndpointContributorRegistry(List.of(capture(
                        new Route("GET", "/f", Realm.TENANT, null, true)))));
        assertTrue(kernel.getMessage().contains("must be POST"), kernel.getMessage());
        assertNotNull(new EndpointContributorRegistry(List.of(capture(
                new Route("post", "/f", Realm.TENANT, null, true)))).match("POST", "/f").orElse(null),
                "the method is compared case-insensitively");
    }

    @Test
    void noRouteMayClaimAKernelAuthOrRecoveryPath() {
        for (String path : List.of("/forgot-password", "/reset", "/login", "/logout", "/error")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new EndpointContributorRegistry(List.of(capture(Route.anonymousPost(path, null)))), path);
            assertTrue(failure.getMessage().contains("shadows"), failure.getMessage());
        }
        assertNotNull(new EndpointContributorRegistry(List.of(capture(Route.get("/resetting-guide", Realm.TENANT))))
                .match("GET", "/resetting-guide").orElse(null), "only the exact kernel routes are taken");
    }

    // --- the CSRF exemption's exact scope -----------------------------------------------------------------

    private AnonymousRouteMatcher matcherFor(EndpointContributor... contributors) {
        EndpointContributorRegistry registry = new EndpointContributorRegistry(List.of(contributors));
        org.springframework.beans.factory.support.StaticListableBeanFactory beans =
                new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("registry", registry);
        return new AnonymousRouteMatcher(beans.getBeanProvider(EndpointContributorRegistry.class), siteResolver);
    }

    @Test
    void theCsrfExemptionMatchesOnlyAPostToARegisteredAnonymousRouteOnABoundSite() {
        AnonymousRouteMatcher matcher = matcherFor(capture(Route.anonymousPost("/contact/send", null),
                new Route("POST", "/other", Realm.TENANT, null), Route.get("/contact/send", Realm.TENANT)));
        assertTrue(matcher.matches(request("POST", "/contact/send", null)));
        assertFalse(matcher.matches(request("GET", "/contact/send", null)), "GET is not exempt (and needs no token)");
        assertFalse(matcher.matches(request("POST", "/other", null)), "an ordinary POST route keeps CSRF");
        assertFalse(matcher.matches(request("POST", "/contact/send/x", null)), "exact path only");
        assertFalse(matcher.matches(request("POST", "/contact/SEND", null)));
        assertFalse(matcher.matches(request("POST", "/__admin/pages/home/disable", null)), "console forms keep CSRF");
        assertFalse(matcher.matches(request("POST", "/login", null)));
        assertFalse(matcher.matches(request("POST", "/__app/sites", null)));
        siteResolver.clear();
        assertFalse(matcher.matches(request("POST", "/contact/send", null)), "no bound site, no exemption");
    }

    @Test
    void withNoAnonymousRouteNothingIsExempt() {
        AnonymousRouteMatcher matcher = matcherFor(capture(new Route("POST", "/contact/send", Realm.TENANT, null)));
        assertFalse(matcher.matches(request("POST", "/contact/send", null)));
    }
}
