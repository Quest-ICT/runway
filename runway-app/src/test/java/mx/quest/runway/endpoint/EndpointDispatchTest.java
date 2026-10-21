package mx.quest.runway.endpoint;
import mx.quest.runway.platform.TestPlatformSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.ServletInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The dispatcher serves a matched route (writing body + posture), and passes through otherwise. */
class EndpointDispatchTest {

    private static SiteResolver resolver(java.nio.file.Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        return new SiteResolver(new SiteStore(StorageLayout.from(props), new YamlStore()), TestPlatformSettings.empty());
    }

    private static EndpointContributor robots(SurfacePosture posture) {
        return new EndpointContributor() {
            @Override
            public String key() {
                return "robots";
            }

            @Override
            public List<Route> routes() {
                return List.of(new Route("GET", "/robots.txt", Realm.TENANT, posture));
            }

            @Override
            public RouteResponse handle(RouteRequest request) {
                return RouteResponse.text(200, "hi");
            }
        };
    }

    private static MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    void dispatchesAMatchedRouteAndAppliesTheSafePosture(@TempDir java.nio.file.Path tmp) throws Exception {
        SiteResolver siteResolver = resolver(tmp);
        EndpointContributorRegistry registry = new EndpointContributorRegistry(List.of(robots(SurfacePosture.safeDefault())));
        EndpointDispatchFilter filter = new EndpointDispatchFilter(registry, siteResolver, 1_000_000);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        try {
            siteResolver.bind(new Site("acme", "Acme", null, Locale.ENGLISH, null, null, null));
            filter.doFilter(get("/robots.txt"), response, chain);
        } finally {
            siteResolver.clear();
        }
        assertEquals(200, response.getStatus());
        assertEquals("hi", response.getContentAsString());
        assertEquals("noindex, nofollow", response.getHeader("X-Robots-Tag"), "safe posture is not indexable");
        assertEquals("no-store", response.getHeader("Cache-Control"), "safe posture is not cacheable");
        assertNull(chain.getRequest(), "a matched route short-circuits the chain");
    }

    @Test
    void passesThroughWhenNoRouteMatches(@TempDir java.nio.file.Path tmp) throws Exception {
        SiteResolver siteResolver = resolver(tmp);
        EndpointDispatchFilter filter = new EndpointDispatchFilter(
                new EndpointContributorRegistry(List.of(robots(SurfacePosture.safeDefault()))), siteResolver, 1_000_000);
        MockFilterChain chain = new MockFilterChain();
        try {
            siteResolver.bind(new Site("acme", "Acme", null, Locale.ENGLISH, null, null, null));
            filter.doFilter(get("/not-a-route"), new MockHttpServletResponse(), chain);
        } finally {
            siteResolver.clear();
        }
        assertNotNull(chain.getRequest(), "an unmatched request falls through to the page controller");
    }

    @Test
    void passesThroughWhenNoSiteIsBound(@TempDir java.nio.file.Path tmp) throws Exception {
        SiteResolver siteResolver = resolver(tmp); // nothing bound
        EndpointDispatchFilter filter = new EndpointDispatchFilter(
                new EndpointContributorRegistry(List.of(robots(SurfacePosture.safeDefault()))), siteResolver, 1_000_000);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(get("/robots.txt"), new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest(), "routes are tenant-scoped — no bound site means not ours");
    }

    private static EndpointContributor echo() {
        return new EndpointContributor() {
            @Override
            public String key() {
                return "echo";
            }

            @Override
            public List<Route> routes() {
                return List.of(new Route("POST", "/echo", Realm.TENANT, SurfacePosture.safeDefault()));
            }

            @Override
            public RouteResponse handle(RouteRequest request) {
                return RouteResponse.text(200, "len=" + request.body().length);
            }
        };
    }

    private static MockHttpServletRequest post(String uri, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        request.setContent(body);
        return request;
    }

    @Test
    void rejectsARequestBodyOverTheCapWith413(@TempDir java.nio.file.Path tmp) throws Exception {
        SiteResolver siteResolver = resolver(tmp);
        EndpointDispatchFilter filter = new EndpointDispatchFilter(
                new EndpointContributorRegistry(List.of(echo())), siteResolver, 8); // 8-byte cap
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        try {
            siteResolver.bind(new Site("acme", "Acme", null, Locale.ENGLISH, null, null, null));
            filter.doFilter(post("/echo", "0123456789".getBytes()), response, chain); // 10 bytes > 8
        } finally {
            siteResolver.clear();
        }
        assertEquals(413, response.getStatus(), "an over-cap plugin-endpoint body is refused, not read into the heap");
        assertNull(chain.getRequest(), "the request never reaches the page controller");
    }

    @Test
    void servesARequestBodyWithinTheCap(@TempDir java.nio.file.Path tmp) throws Exception {
        SiteResolver siteResolver = resolver(tmp);
        EndpointDispatchFilter filter = new EndpointDispatchFilter(
                new EndpointContributorRegistry(List.of(echo())), siteResolver, 1_000_000);
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            siteResolver.bind(new Site("acme", "Acme", null, Locale.ENGLISH, null, null, null));
            filter.doFilter(post("/echo", "hello".getBytes()), response, new MockFilterChain());
        } finally {
            siteResolver.clear();
        }
        assertEquals(200, response.getStatus());
        assertEquals("len=5", response.getContentAsString(), "a within-cap body reaches the route intact");
    }

    // --- a throwing (or null-returning) handler is a 500, no-store, and the next request still works ----

    private static EndpointContributor failing(java.util.function.Supplier<RouteResponse> behaviour) {
        return new EndpointContributor() {
            @Override
            public String key() {
                return "flaky";
            }

            @Override
            public List<Route> routes() {
                return List.of(new Route("GET", "/flaky", Realm.TENANT, SurfacePosture.discoverable()));
            }

            @Override
            public RouteResponse handle(RouteRequest request) {
                return behaviour.get();
            }
        };
    }

    private static MockHttpServletResponse dispatch(java.nio.file.Path tmp, EndpointContributor contributor,
                                                    mx.quest.runway.plugin.ContributionGuard guard) throws Exception {
        SiteResolver siteResolver = resolver(tmp);
        EndpointDispatchFilter filter = new EndpointDispatchFilter(
                new EndpointContributorRegistry(List.of(contributor)), siteResolver, 1_000_000, guard);
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            siteResolver.bind(new Site("acme", "Acme", null, Locale.ENGLISH, null, null, null));
            filter.doFilter(get("/flaky"), response, new MockFilterChain());
        } finally {
            siteResolver.clear();
        }
        return response;
    }

    @Test
    void aThrowingHandlerIsA500NoStoreAndIsCounted(@TempDir java.nio.file.Path tmp) throws Exception {
        mx.quest.runway.plugin.ContributionGuard guard = mx.quest.runway.plugin.ContributionGuard.standalone();
        MockHttpServletResponse response = dispatch(tmp, failing(() -> {
            throw new IllegalStateException("boom");
        }), guard);
        assertEquals(500, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"),
                "even a cacheable route's error must not be cached");
        assertEquals("", response.getContentAsString());
        assertEquals(1, guard.health("kernel").failures());
    }

    @Test
    void aNullResponseIsTreatedAsAFailure(@TempDir java.nio.file.Path tmp) throws Exception {
        mx.quest.runway.plugin.ContributionGuard guard = mx.quest.runway.plugin.ContributionGuard.standalone();
        MockHttpServletResponse response = dispatch(tmp, failing(() -> null), guard);
        assertEquals(500, response.getStatus());
        assertEquals(1, guard.health("kernel").failures());
    }

    @Test
    void aNonLinkageErrorIsNotSwallowed(@TempDir java.nio.file.Path tmp) {
        org.junit.jupiter.api.Assertions.assertThrows(OutOfMemoryError.class, () -> dispatch(tmp,
                failing(() -> {
                    throw new OutOfMemoryError("simulated");
                }), mx.quest.runway.plugin.ContributionGuard.standalone()));
    }

    // --- the cap depends on who is calling ---------------------------------------------------------

    /** Dispatches {@code request} through a filter with max-body 1000 and max-form 100; returns the response. */
    private static MockHttpServletResponse dispatchCapped(java.nio.file.Path tmp, EndpointContributor contributor,
                                                          MockHttpServletRequest request) throws Exception {
        SiteResolver siteResolver = resolver(tmp);
        EndpointDispatchFilter filter = new EndpointDispatchFilter(new EndpointContributorRegistry(List.of(contributor)),
                siteResolver, 1000, 100, mx.quest.runway.plugin.ContributionGuard.standalone());
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            siteResolver.bind(new Site("acme", "Acme", null, Locale.ENGLISH, null, null, null));
            filter.doFilter(request, response, new MockFilterChain());
        } finally {
            siteResolver.clear();
        }
        return response;
    }

    private static void signIn() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("admin", null, List.of()));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aCallerWhoIsNotSignedInIsHeldToThePreAuthCapAtItsBoundary(@TempDir java.nio.file.Path tmp) throws Exception {
        MockHttpServletResponse exact = dispatchCapped(tmp, echo(), post("/echo", new byte[100]));
        assertEquals(200, exact.getStatus());
        assertEquals("len=100", exact.getContentAsString());
        assertEquals(413, dispatchCapped(tmp, echo(), post("/echo", new byte[101])).getStatus(), "one byte over");
        // Spring's anonymous token is "not signed in" too.
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("k", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertEquals(413, dispatchCapped(tmp, echo(), post("/echo", new byte[101])).getStatus());
    }

    @Test
    void aSignedInCallerKeepsTheBodyCap(@TempDir java.nio.file.Path tmp) throws Exception {
        signIn();
        MockHttpServletResponse ok = dispatchCapped(tmp, echo(), post("/echo", new byte[1000]));
        assertEquals(200, ok.getStatus());
        assertEquals("len=1000", ok.getContentAsString());
        assertEquals(413, dispatchCapped(tmp, echo(), post("/echo", new byte[1001])).getStatus(),
                "max-body-bytes still applies");
    }

    /** A declared length over the cap is refused without touching the stream. */
    @Test
    void aDeclaredLengthOverTheCapIsRefusedUnread(@TempDir java.nio.file.Path tmp) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/echo") {
            @Override
            public long getContentLengthLong() {
                return 101;
            }

            @Override
            public ServletInputStream getInputStream() {
                throw new AssertionError("the body must not be read");
            }
        };
        request.setRequestURI("/echo");
        assertEquals(413, dispatchCapped(tmp, echo(), request).getStatus());
    }

    /** An anonymous route is held to the pre-auth cap even for a signed-in visitor (it runs with no principal). */
    @Test
    void anAnonymousRouteIsHeldToThePreAuthCapEvenWhenSignedIn(@TempDir java.nio.file.Path tmp) throws Exception {
        EndpointContributor anon = new EndpointContributor() {
            @Override
            public String key() {
                return "anon";
            }

            @Override
            public List<Route> routes() {
                return List.of(Route.anonymousPost("/anon", null));
            }

            @Override
            public RouteResponse handle(RouteRequest request) {
                return RouteResponse.text(200, "len=" + request.body().length);
            }
        };
        signIn();
        MockHttpServletRequest big = post("/anon", ("a=" + "b".repeat(199)).getBytes(StandardCharsets.UTF_8));
        big.setContentType("application/x-www-form-urlencoded");
        assertEquals(413, dispatchCapped(tmp, anon, big).getStatus());
        MockHttpServletRequest small = post("/anon", "a=b".getBytes(StandardCharsets.UTF_8));
        small.setContentType("application/x-www-form-urlencoded");
        assertEquals(200, dispatchCapped(tmp, anon, small).getStatus());
    }
}
