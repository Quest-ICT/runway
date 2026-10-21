package mx.quest.runway.endpoint;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.servlet.filter.OrderedFormContentFilter;

/**
 * Spring Boot's {@code FormContentFilter}, minus plugin routes. Boot registers that filter outermost (order
 * {@code -9900}) so a {@code PUT}/{@code PATCH}/{@code DELETE} form body becomes request parameters for
 * {@code @RequestParam}; it does that by <b>reading the body</b>, so a plugin route used to receive an empty
 * {@code RouteRequest.form()} and {@code body()} for those methods — and a {@code _csrf} field in such a body was not
 * found either ({@code RouteBodyMethodsE2ETest}). A request whose method and path match a registered route
 * now passes through untouched, and {@link RouteFormCaptureFilter} reads the body for the route as it does for a
 * {@code POST}; every other request (a console controller's) keeps Boot's behaviour.
 *
 * <p>Declared as the {@code formContentFilter} bean, which makes Boot's auto-configured one stand down
 * ({@code @ConditionalOnMissingBean(FormContentFilter.class)}). The route registry is looked up lazily, at the first
 * request: it depends on every plugin contribution, and filters are created early, while the web server starts.
 *
 * <h2>Bounded</h2>
 * Boot's filter reads the whole body into memory with no limit of its own, and it runs before anything else — before
 * the security chain, for every path, signed in or not. A {@code PUT}/{@code PATCH}/{@code DELETE} form body it
 * parses is therefore held to the pre-authentication budget ({@link PreAuthBodyLimit},
 * {@code runway.endpoints.max-form-bytes}): a declared length over it is {@code 413} unread, and a chunked body is
 * stopped at it ({@code 413}). The kernel's own controllers take no such forms; a plugin MVC controller that does
 * gets this cap too.
 */
public class RouteAwareFormContentFilter extends OrderedFormContentFilter {

    private static final Logger log = LoggerFactory.getLogger(RouteAwareFormContentFilter.class);

    /** The methods Boot's filter parses a form body for. */
    private static final Set<String> PARSED_METHODS = Set.of("PUT", "PATCH", "DELETE");

    private final ObjectProvider<EndpointContributorRegistry> registry;
    private final long cap;

    /** A filter bounded by {@link RouteFormCaptureFilter#DEFAULT_MAX_FORM_BYTES}. */
    public RouteAwareFormContentFilter(ObjectProvider<EndpointContributorRegistry> registry) {
        this(registry, RouteFormCaptureFilter.DEFAULT_MAX_FORM_BYTES);
    }

    /** @param cap the most form-body bytes it reads ({@link PreAuthBodyLimit#cap()}) */
    public RouteAwareFormContentFilter(ObjectProvider<EndpointContributorRegistry> registry, long cap) {
        this.registry = registry;
        this.cap = Math.max(0, cap);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!PARSED_METHODS.contains(request.getMethod()) || !EndpointDispatchFilter.isForm(request.getContentType())) {
            super.doFilterInternal(request, response, chain); // nothing Boot's filter would read
            return;
        }
        long declared = request.getContentLengthLong();
        if (declared > cap) {
            refuse(request, response);
            return;
        }
        BoundedBodyRequest bounded = new BoundedBodyRequest(request, cap);
        try {
            super.doFilterInternal(bounded, response, chain);
        } catch (IOException | ServletException | RuntimeException failure) {
            if (bounded.exceeded() && !response.isCommitted()) {
                refuse(request, response);
                return;
            }
            throw failure;
        }
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response) {
        log.warn("{} {} refused: the form body is over the {}-byte cap (runway.endpoints.max-form-bytes)",
                request.getMethod(), request.getRequestURI(), cap);
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE); // 413
        response.setHeader("Cache-Control", "no-store");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        EndpointContributorRegistry routes = registry.getIfAvailable();
        return routes != null && routes.match(request.getMethod(), request.getRequestURI()).isPresent();
    }
}
