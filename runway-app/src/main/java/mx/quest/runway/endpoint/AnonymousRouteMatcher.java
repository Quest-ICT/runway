package mx.quest.runway.endpoint;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.web.AppRealmPaths;
import mx.quest.runway.web.RequestPaths;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The tenant chain's CSRF exemption for {@linkplain mx.quest.runway.spi.model.Route#anonymous() anonymous} plugin
 * routes: it matches a request only when <b>all</b> of these hold —
 * <ol>
 *   <li>the method is {@code POST};</li>
 *   <li>a site is bound to the request (a plugin route is only ever dispatched on a site's host — with no site the
 *       request would fall through to the page controller, and must keep its CSRF check);</li>
 *   <li>neither spelling of the path is on the application realm ({@link RequestPaths#eitherMatches}; defence in
 *       depth: the registry already refuses such routes);</li>
 *   <li>the endpoint registry has an <b>anonymous</b> route for exactly this method and path — the same exact-match
 *       lookup the dispatcher uses ({@link EndpointContributorRegistry#isAnonymous}).</li>
 * </ol>
 * The route lookup compares the <b>raw</b> request URI, here and in every endpoint filter
 * ({@link EndpointDispatchFilter}, {@link RouteFormCaptureFilter}, {@link RouteAwareFormContentFilter}), on purpose:
 * all of them must agree on which request is which route, and they do because they all use the same string. A
 * route is a clean path, so an encoded or dotted spelling of it is simply not that route: no exemption, no dispatch,
 * and the request falls through to Spring MVC like any unknown path (a {@code 403} for a {@code POST} without a
 * CSRF token, a {@code 404} for a {@code GET}).
 * Every other request — every other route, every kernel path, every console form — keeps the CSRF check. The
 * dispatcher ({@link EndpointDispatchFilter}) then runs the exempted handler with no principal and a form body only,
 * which is what makes the exemption safe: a forged cross-site submission reaches nothing a signed-in user could do.
 *
 * <p>The registry is looked up lazily (at the first request), because the security chain is built before the
 * plugin contributions are; resolving it eagerly would force every endpoint contributor to be created early.
 */
public final class AnonymousRouteMatcher implements RequestMatcher {

    private final ObjectProvider<EndpointContributorRegistry> registry;
    private final SiteResolver siteResolver;

    public AnonymousRouteMatcher(ObjectProvider<EndpointContributorRegistry> registry, SiteResolver siteResolver) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.siteResolver = Objects.requireNonNull(siteResolver, "siteResolver");
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri == null || RequestPaths.eitherMatches(request, AppRealmPaths::isAppRealm)
                || siteResolver.current().isEmpty()) {
            return false;
        }
        EndpointContributorRegistry routes = registry.getIfAvailable();
        return routes != null && routes.isAnonymous(request.getMethod(), uri);
    }

    @Override
    public String toString() {
        return "AnonymousRouteMatcher[POST, anonymous plugin routes on a bound site]";
    }
}
