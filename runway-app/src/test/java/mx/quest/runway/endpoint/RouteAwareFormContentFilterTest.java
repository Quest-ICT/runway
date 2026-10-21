package mx.quest.runway.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Boot's form-content parsing ({@code PUT}/{@code PATCH}/{@code DELETE} form bodies → request parameters) still works
 * for every request that is not a plugin route, and stands aside — leaving the body unread — for one that is.
 */
class RouteAwareFormContentFilterTest {

    private static EndpointContributorRegistry registry() {
        EndpointContributor contributor = new EndpointContributor() {
            @Override
            public String key() {
                return "probe";
            }

            @Override
            public List<Route> routes() {
                return List.of(new Route("PUT", "/probe", Realm.TENANT, null));
            }

            @Override
            public RouteResponse handle(RouteRequest request) {
                return RouteResponse.text(200, "ok");
            }
        };
        return new EndpointContributorRegistry(List.of(contributor));
    }

    @SuppressWarnings("unchecked")
    private static RouteAwareFormContentFilter filter(EndpointContributorRegistry registry) {
        ObjectProvider<EndpointContributorRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new RouteAwareFormContentFilter(provider);
    }

    private static MockHttpServletRequest put(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", path);
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent("to=ops%40example.com".getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void aPluginRoutePassesThroughUnread() throws Exception {
        MockHttpServletRequest request = put("/probe");
        AtomicReference<ServletRequest> seen = new AtomicReference<>();
        filter(registry()).doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(req));
        assertSame(request, seen.get(), "not wrapped: the route form capture reads the body itself");
        assertNull(seen.get().getParameter("to"), "nothing parsed the body into parameters");
        assertEquals("to=ops%40example.com", new String(request.getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void anyOtherPutKeepsBootsBehaviour() throws Exception {
        AtomicReference<ServletRequest> seen = new AtomicReference<>();
        filter(registry()).doFilter(put("/__admin/other"), new MockHttpServletResponse(), (req, res) -> seen.set(req));
        assertEquals("ops@example.com", seen.get().getParameter("to"), "Boot's form-content parsing applies");
    }

    @Test
    void withNoRegistryYetEverythingKeepsBootsBehaviour() throws Exception {
        AtomicReference<ServletRequest> seen = new AtomicReference<>();
        filter(null).doFilter(put("/probe"), new MockHttpServletResponse(), (req, res) -> seen.set(req));
        assertEquals("ops@example.com", seen.get().getParameter("to"));
    }

    @Test
    void itKeepsBootsOrder() {
        assertEquals(org.springframework.boot.servlet.filter.OrderedFormContentFilter.DEFAULT_ORDER,
                filter(registry()).getOrder());
    }
}
