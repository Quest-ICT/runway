package mx.quest.runway.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The per-site request timer: records site+outcome for tenant traffic, and skips app-realm / unbound hosts. */
class SiteRequestMetricsFilterTest {

    private static Site site(String id) {
        return new Site(id, id, List.of("localhost"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE);
    }

    private static MockHttpServletRequest request(String host, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", uri);
        r.setServerName(host);
        return r;
    }

    @Test
    void recordsATimerTaggedBySiteAndOutcome() throws Exception {
        SiteResolver resolver = mock(SiteResolver.class);
        when(resolver.resolve("acme.test")).thenReturn(Optional.of(site("acme")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SiteRequestMetricsFilter filter = new SiteRequestMetricsFilter(resolver, registry);

        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        filter.doFilter(request("acme.test", "/about"), response, new MockFilterChain());

        Timer timer = registry.find("runway.http.server.requests").tag("site", "acme").tag("outcome", "success")
                .timer();
        assertEquals(1, timer.count());
    }

    @Test
    void aServerErrorIsTaggedServerError() throws Exception {
        SiteResolver resolver = mock(SiteResolver.class);
        when(resolver.resolve("acme.test")).thenReturn(Optional.of(site("acme")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SiteRequestMetricsFilter filter = new SiteRequestMetricsFilter(resolver, registry);

        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);
        filter.doFilter(request("acme.test", "/boom"), response, new MockFilterChain());

        assertEquals(1, registry.find("runway.http.server.requests")
                .tag("site", "acme").tag("outcome", "server_error").timer().count());
    }

    @Test
    void appRealmTrafficIsNotRecorded() throws Exception {
        SiteResolver resolver = mock(SiteResolver.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SiteRequestMetricsFilter filter = new SiteRequestMetricsFilter(resolver, registry);

        filter.doFilter(request("admin.test", "/__app/dashboard"), new MockHttpServletResponse(),
                new MockFilterChain());
        filter.doFilter(request("admin.test", "/actuator/prometheus"), new MockHttpServletResponse(),
                new MockFilterChain());

        assertNull(registry.find("runway.http.server.requests").timer(), "app-realm/actuator traffic is excluded");
    }

    @Test
    void anUnboundHostIsNotRecorded() throws Exception {
        SiteResolver resolver = mock(SiteResolver.class);
        when(resolver.resolve("nobody.test")).thenReturn(Optional.empty());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SiteRequestMetricsFilter filter = new SiteRequestMetricsFilter(resolver, registry);

        filter.doFilter(request("nobody.test", "/about"), new MockHttpServletResponse(), new MockFilterChain());
        assertNull(registry.find("runway.http.server.requests").timer(), "no site → no unbounded 'unknown' bucket");
    }
}
