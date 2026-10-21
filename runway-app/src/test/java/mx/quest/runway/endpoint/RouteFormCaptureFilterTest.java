package mx.quest.runway.endpoint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.platform.TestPlatformSettings;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link RouteFormCaptureFilter} captures exactly the requests the dispatcher serves with a form body, under the cap,
 * and {@link CapturedFormRequest} answers both readers of that body — the parameter view (query first, then body,
 * tolerant) and the dispatcher's {@code bodyFields()} (body alone, strict). The end-to-end proof through real Jetty is
 * {@link RouteFormBodyE2ETest}.
 */
class RouteFormCaptureFilterTest {

    private static final String FORM = "application/x-www-form-urlencoded";

    private static SiteResolver resolver(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        return new SiteResolver(new SiteStore(StorageLayout.from(props), new YamlStore()), TestPlatformSettings.empty());
    }

    private static EndpointContributorRegistry registry() {
        return new EndpointContributorRegistry(List.of(new EndpointContributor() {
            @Override
            public String key() {
                return "probe";
            }

            @Override
            public List<Route> routes() {
                return List.of(new Route("POST", "/probe", Realm.TENANT, null));
            }

            @Override
            public RouteResponse handle(RouteRequest request) {
                return RouteResponse.text(200, "ok");
            }
        }));
    }

    private static MockHttpServletRequest post(String uri, String query, String contentType, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        request.setQueryString(query);
        request.setContentType(contentType);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /** Runs the filter with (or without) a bound site; answers the request the chain was handed, or null. */
    private static HttpServletRequest run(Path tmp, boolean bound, HttpServletRequest request,
                                          MockHttpServletResponse response, long cap) throws Exception {
        SiteResolver sites = resolver(tmp);
        RouteFormCaptureFilter filter = new RouteFormCaptureFilter(registry(), sites, cap);
        MockFilterChain chain = new MockFilterChain();
        try {
            if (bound) {
                sites.bind(new Site("acme", "Acme", null, Locale.ENGLISH, null, null, null));
            }
            filter.doFilter(request, response, chain);
        } finally {
            sites.clear();
        }
        return (HttpServletRequest) chain.getRequest();
    }

    @Test
    void aRouteFormIsCapturedAndReplayed(@TempDir Path tmp) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpServletRequest seen = run(tmp, true, post("/probe", null, FORM, "a=1&b=2"), response, 1000);
        CapturedFormRequest captured = assertInstanceOf(CapturedFormRequest.class, seen);
        assertEquals(Map.of("a", List.of("1"), "b", List.of("2")), captured.bodyFields());
        assertArrayEquals("a=1&b=2".getBytes(StandardCharsets.UTF_8), captured.body());
        assertArrayEquals("a=1&b=2".getBytes(StandardCharsets.UTF_8), captured.getInputStream().readAllBytes(),
                "the stream replays the captured body");
        ServletInputStream stream = captured.getInputStream();
        assertSame(stream, captured.getInputStream(), "one stream per request");
        assertEquals(-1, stream.read(), "and it is consumed once");
    }

    @Test
    void anythingTheDispatcherWouldNotServeWithAFormPassesThroughUntouched(@TempDir Path tmp) throws Exception {
        MockHttpServletRequest json = post("/probe", null, "application/json", "{}");
        assertSame(json, run(tmp, true, json, new MockHttpServletResponse(), 1000), "not a form");
        MockHttpServletRequest noContentType = post("/probe", null, null, "a=1");
        assertSame(noContentType, run(tmp, true, noContentType, new MockHttpServletResponse(), 1000));
        MockHttpServletRequest otherPath = post("/elsewhere", null, FORM, "a=1");
        assertSame(otherPath, run(tmp, true, otherPath, new MockHttpServletResponse(), 1000), "not a route");
        MockHttpServletRequest noSite = post("/probe", null, FORM, "a=1");
        assertSame(noSite, run(tmp, false, noSite, new MockHttpServletResponse(), 1000), "no bound site");
        MockHttpServletRequest get = post("/probe", null, FORM, "a=1");
        get.setMethod("GET");
        assertSame(get, run(tmp, true, get, new MockHttpServletResponse(), 1000), "the route is POST only");
    }

    @Test
    void aBodyOverTheCapIs413AndNeverReachesTheChain(@TempDir Path tmp) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertNull(run(tmp, true, post("/probe", null, FORM, "a=" + "x".repeat(20)), response, 10));
        assertEquals(413, response.getStatus());
        MockHttpServletResponse exact = new MockHttpServletResponse();
        HttpServletRequest atCap = run(tmp, true, post("/probe", null, FORM, "a=" + "x".repeat(8)), exact, 10);
        assertInstanceOf(CapturedFormRequest.class, atCap, "a body exactly at the cap is accepted");
    }

    @Test
    void aDeclaredLengthOverTheCapIsRefusedWithoutReading(@TempDir Path tmp) throws Exception {
        MockHttpServletRequest small = post("/probe", null, FORM, "a=1");
        HttpServletRequest lying = new HttpServletRequestWrapper(small) {
            @Override
            public long getContentLengthLong() {
                return 1_000_000;
            }

            @Override
            public ServletInputStream getInputStream() throws IOException {
                throw new AssertionError("must not read a body whose declared length is over the cap");
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertNull(run(tmp, true, lying, response, 1000));
        assertEquals(413, response.getStatus());
    }

    // ---- the pre-authentication bound is the small form cap, not the raw-body cap ----------------------

    /** Routes for every method the capture must (POST, PUT) or must not (DELETE, PATCH) read before security. */
    private static EndpointContributorRegistry methodRegistry() {
        return new EndpointContributorRegistry(List.of(new EndpointContributor() {
            @Override
            public String key() {
                return "methods";
            }

            @Override
            public List<Route> routes() {
                return List.of(new Route("POST", "/m", Realm.TENANT, null), new Route("PUT", "/m", Realm.TENANT, null),
                        new Route("DELETE", "/m", Realm.TENANT, null), new Route("PATCH", "/m", Realm.TENANT, null));
            }

            @Override
            public RouteResponse handle(RouteRequest request) {
                return RouteResponse.text(200, "ok");
            }
        }));
    }

    private static HttpServletRequest runWith(Path tmp, RouteFormCaptureFilter filter, SiteResolver sites,
                                              HttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        MockFilterChain chain = new MockFilterChain();
        try {
            sites.bind(new Site("acme", "Acme", null, Locale.ENGLISH, null, null, null));
            filter.doFilter(request, response, chain);
        } finally {
            sites.clear();
        }
        return (HttpServletRequest) chain.getRequest();
    }

    @Test
    void theCaptureIsBoundedByTheFormCapNotTheBodyCap(@TempDir Path tmp) throws Exception {
        SiteResolver sites = resolver(tmp);
        RouteFormCaptureFilter filter = new RouteFormCaptureFilter(registry(), sites, 1_000_000, 16);
        assertEquals(16, filter.captureCap());
        MockHttpServletResponse over = new MockHttpServletResponse();
        assertNull(runWith(tmp, filter, sites, post("/probe", null, FORM, "a=" + "x".repeat(15)), over),
                "17 bytes: over the 16-byte form cap although far under the body cap");
        assertEquals(413, over.getStatus());
        assertInstanceOf(CapturedFormRequest.class, runWith(tmp, filter, sites,
                post("/probe", null, FORM, "a=" + "x".repeat(14)), new MockHttpServletResponse()), "16 bytes: at the cap");
    }

    @Test
    void theEffectiveCapIsTheSmallerOfTheTwoAndDefaultsTo256KiB(@TempDir Path tmp) {
        SiteResolver sites = resolver(tmp);
        assertEquals(10, new RouteFormCaptureFilter(registry(), sites, 10, 1000).captureCap(), "body cap smaller");
        assertEquals(262_144, new RouteFormCaptureFilter(registry(), sites, 10_485_760).captureCap(),
                "default form cap under the default 10 MiB body cap");
        assertEquals(RouteFormCaptureFilter.DEFAULT_MAX_FORM_BYTES, 262_144);
        assertEquals(0, new RouteFormCaptureFilter(registry(), sites, 10, -5).captureCap(), "negative means 0");
    }

    @Test
    void aZeroCapRefusesEveryNonEmptyFormButAcceptsAnEmptyOne(@TempDir Path tmp) throws Exception {
        SiteResolver sites = resolver(tmp);
        RouteFormCaptureFilter filter = new RouteFormCaptureFilter(registry(), sites, 1000, 0);
        MockHttpServletResponse refused = new MockHttpServletResponse();
        assertNull(runWith(tmp, filter, sites, post("/probe", null, FORM, "a"), refused));
        assertEquals(413, refused.getStatus());
        assertInstanceOf(CapturedFormRequest.class, runWith(tmp, filter, sites, post("/probe", null, FORM, ""),
                new MockHttpServletResponse()));
    }

    /** Only the methods whose form body the container parses (and CSRF could consume) are read before security. */
    @Test
    void everyMethodIsCapturedBeforeSecurityUnderTheSmallFormCap(@TempDir Path tmp) throws Exception {
        // SPI 1.0.0: Boot's form filter skips plugin routes, so the capture is what lets a PATCH or DELETE form carry
        // its _csrf field; every method therefore obeys the pre-authentication max-form-bytes cap, never the
        // larger max-body-bytes.
        SiteResolver sites = resolver(tmp);
        RouteFormCaptureFilter filter = new RouteFormCaptureFilter(methodRegistry(), sites, 100_000, 1000);
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            MockHttpServletRequest request = post("/m", null, FORM, "a=1");
            request.setMethod(method);
            assertInstanceOf(CapturedFormRequest.class,
                    runWith(tmp, filter, sites, request, new MockHttpServletResponse()), method);
            MockHttpServletRequest big = post("/m", null, FORM, "a=" + "x".repeat(5000));
            big.setMethod(method);
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertNull(runWith(tmp, filter, sites, big, response), method + " over the form cap never reaches the chain");
            assertEquals(413, response.getStatus(), method + " over max-form-bytes, although under max-body-bytes");
        }
    }

    @Test
    void theDeclaredLengthOnlySizesTheBufferTheCapStillCountsBytesRead() throws Exception {
        byte[] five = "a=123".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(five, EndpointDispatchFilter.readBounded(new java.io.ByteArrayInputStream(five), 10, 1000),
                "a declared length larger than the body is only a hint");
        assertArrayEquals(five, EndpointDispatchFilter.readBounded(new java.io.ByteArrayInputStream(five), 10, 2));
        assertThrows(EndpointDispatchFilter.BodyTooLargeException.class, () -> EndpointDispatchFilter.readBounded(
                new java.io.ByteArrayInputStream(new byte[11]), 10, 3), "an understated length cannot sneak past the cap");
    }

    @Test
    void theParameterViewIsQueryFirstThenBodyAndTheBodyFieldsAreTheBodyAlone() throws Exception {
        CapturedFormRequest req = new CapturedFormRequest(post("/probe", "to=q&only=q", FORM, ""),
                "to=b1&_csrf=tok&to=b2".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertArrayEquals(new String[] {"q", "b1", "b2"}, req.getParameterValues("to"), "servlet order");
        assertEquals("q", req.getParameter("to"));
        assertEquals("tok", req.getParameter("_csrf"), "the CSRF check finds a token in the body");
        assertEquals(List.of("to", "only", "_csrf"), Collections.list(req.getParameterNames()));
        assertEquals(Map.of("to", List.of("b1", "b2"), "_csrf", List.of("tok")), req.bodyFields(),
                "form() never sees the query string");
        assertNull(req.getParameter("absent"));
        assertNull(req.getParameterValues("absent"));
        String[] values = req.getParameterValues("to");
        values[0] = "tampered";
        assertEquals("q", req.getParameter("to"), "callers get a copy");
        assertThrows(UnsupportedOperationException.class, () -> req.getParameterMap().put("x", new String[0]));
    }

    @Test
    void theBodyIsDecodedInItsDeclaredCharset() throws Exception {
        byte[] latin1 = "n=se%F1or&raw=café".getBytes(StandardCharsets.ISO_8859_1);
        CapturedFormRequest req = new CapturedFormRequest(post("/probe", null, FORM, ""), latin1,
                StandardCharsets.ISO_8859_1);
        assertEquals("señor", req.getParameter("n"));
        assertEquals(List.of("café"), req.bodyFields().get("raw"));
        assertEquals("n=se%F1or&raw=café", req.getReader().readLine());
    }

    @Test
    void aMalformedHalfContributesNothingToTheViewButTheBodyFieldsStayStrict() {
        CapturedFormRequest badBody = new CapturedFormRequest(post("/probe", "q=1", FORM, ""),
                "_csrf=tok&x=%zz".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertEquals("1", badBody.getParameter("q"));
        assertNull(badBody.getParameter("_csrf"), "a malformed body offers no token, so CSRF refuses");
        assertThrows(EndpointDispatchFilter.MalformedParametersException.class, badBody::bodyFields);
        assertThrows(EndpointDispatchFilter.MalformedParametersException.class, badBody::bodyFields,
                "the failure is remembered, not re-parsed into a success");

        CapturedFormRequest badQuery = new CapturedFormRequest(post("/probe", "q=%zz", FORM, ""),
                "_csrf=tok".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertNull(badQuery.getParameter("q"));
        assertEquals("tok", badQuery.getParameter("_csrf"));
    }

    @Test
    void tooManyBodyFieldsAreStrictlyRefused() {
        String many = "f=1&".repeat(EndpointDispatchFilter.MAX_PARAMETERS + 1);
        CapturedFormRequest req = new CapturedFormRequest(post("/probe", null, FORM, ""),
                many.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThrows(EndpointDispatchFilter.MalformedParametersException.class, req::bodyFields);
        assertNull(req.getParameter("f"));
    }

    @Test
    void theDispatcherUsesTheCapturedBodyEvenIfAnInnerFilterDrainedTheStream(@TempDir Path tmp) throws Exception {
        SiteResolver sites = resolver(tmp);
        RouteRequest[] seen = new RouteRequest[1];
        EndpointContributorRegistry registry = new EndpointContributorRegistry(List.of(new EndpointContributor() {
            @Override
            public String key() {
                return "probe";
            }

            @Override
            public List<Route> routes() {
                return List.of(new Route("POST", "/probe", Realm.TENANT, null));
            }

            @Override
            public RouteResponse handle(RouteRequest request) {
                seen[0] = request;
                return RouteResponse.text(200, "ok");
            }
        }));
        CapturedFormRequest captured = new CapturedFormRequest(post("/probe", "q=1", FORM, ""),
                "a=1".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        captured.getInputStream().readAllBytes(); // e.g. a plugin APPLICATION filter that read the body
        HttpServletRequest wrapped = new HttpServletRequestWrapper(captured) { }; // e.g. Spring Security's wrappers
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            sites.bind(new Site("acme", "Acme", null, Locale.ENGLISH, null, null, null));
            new EndpointDispatchFilter(registry, sites, 1000).doFilter(wrapped, response, new MockFilterChain());
        } finally {
            sites.clear();
        }
        assertEquals(200, response.getStatus());
        assertEquals(Map.of("a", List.of("1")), seen[0].form());
        assertEquals(Map.of("q", List.of("1")), seen[0].query());
        assertArrayEquals("a=1".getBytes(StandardCharsets.UTF_8), seen[0].body());
    }
}
