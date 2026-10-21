package mx.quest.runway.endpoint;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SurfacePosture;
import org.junit.jupiter.api.Test;

/** The registry fails fast on a duplicate key, a route collision, or a reserved-prefix shadow; matches exactly. */
class EndpointContributorRegistryTest {

    private static EndpointContributor endpoint(String key, Route... routes) {
        return new EndpointContributor() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public List<Route> routes() {
                return List.of(routes);
            }

            @Override
            public RouteResponse handle(RouteRequest request) {
                return RouteResponse.text(200, "ok");
            }
        };
    }

    private static Route route(String method, String path) {
        return new Route(method, path, Realm.TENANT, SurfacePosture.safeDefault());
    }

    @Test
    void aDuplicateKeyFailsFast() {
        assertThrows(IllegalStateException.class, () -> new EndpointContributorRegistry(
                List.of(endpoint("dup", route("GET", "/a")), endpoint("dup", route("GET", "/b")))));
    }

    @Test
    void aRouteCollisionFailsFast() {
        assertThrows(IllegalStateException.class, () -> new EndpointContributorRegistry(
                List.of(endpoint("x", route("GET", "/same")), endpoint("y", route("GET", "/same")))));
    }

    @Test
    void theKernelsOwnTenantHostRoutesCannotBeClaimedByAPlugin() {
        // Both are real kernel routes served on a TENANT host, so a plugin route on either would be a shadow. The
        // health probe additionally runs far outer of endpoint dispatch, so a plugin claiming it could never even
        // serve it — failing loudly at startup says so instead of failing mysteriously at request time.
        assertThrows(IllegalStateException.class, () -> new EndpointContributorRegistry(
                List.of(endpoint("evil", route("GET", "/__health")))));
        assertThrows(IllegalStateException.class, () -> new EndpointContributorRegistry(
                List.of(endpoint("evil", route("GET", "/__ui-lang")))));
    }

    @Test
    void aReservedPrefixIsRefused() {
        assertThrows(IllegalStateException.class, () -> new EndpointContributorRegistry(
                List.of(endpoint("evil", route("GET", "/__app/hijack")))));
        assertThrows(IllegalStateException.class, () -> new EndpointContributorRegistry(
                List.of(endpoint("evil", route("GET", "/actuator/health")))));
    }

    @Test
    void theRealmLoginSurfaceCannotBeShadowed() {
        // A plugin route at /login or /logout could replace the tenant login page (credential harvesting).
        for (String reserved : new String[] {"/login", "/logout", "/error"}) {
            assertThrows(IllegalStateException.class, () -> new EndpointContributorRegistry(
                    List.of(endpoint("evil", route("GET", reserved)))), reserved + " must be reserved");
        }
    }

    @Test
    void matchesExactlyAndCaseInsensitivelyOnMethod() {
        EndpointContributorRegistry registry = new EndpointContributorRegistry(
                List.of(endpoint("robots", route("GET", "/robots.txt"))));
        assertTrue(registry.match("get", "/robots.txt").isPresent(), "method match is case-insensitive");
        assertTrue(registry.match("POST", "/robots.txt").isEmpty(), "wrong method does not match");
        assertTrue(registry.match("GET", "/other").isEmpty(), "wrong path does not match");
    }
}
