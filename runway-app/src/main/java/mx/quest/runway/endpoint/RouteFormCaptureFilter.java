package mx.quest.runway.endpoint;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import mx.quest.runway.endpoint.EndpointContributorRegistry.Match;
import mx.quest.runway.endpoint.EndpointDispatchFilter.BodyTooLargeException;
import mx.quest.runway.site.SiteResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the {@code application/x-www-form-urlencoded} or {@code multipart/form-data} body of a request bound for a
 * plugin route <b>before</b> the security chain, once and under a small dedicated cap
 * ({@code runway.endpoints.max-form-bytes}), and passes the
 * request on as a {@link CapturedFormRequest} — so the CSRF check (which looks for the token with
 * {@code getParameter()}) and the route dispatcher (which builds {@code RouteRequest.form()} and {@code body()}) both
 * see the whole body. Without it, a token sent as the {@code _csrf} form field made the container consume the body
 * during the CSRF check and the plugin got an empty form.
 *
 * <h2>Every method, and multipart (SPI 1.0.0)</h2>
 * Two other readers sat in the way: Spring Boot's {@code FormContentFilter} (outermost)
 * parses a {@code PUT}/{@code PATCH}/{@code DELETE} form into parameters and so consumed it — it now skips plugin
 * routes ({@link RouteAwareFormContentFilter}) and the capture applies to a form body whatever the method; and the
 * kernel's early {@code MultipartFilter} (for console uploads) parsed a multipart body into parts — the capture now
 * keeps a multipart body raw for the route, and that filter skips a captured request ({@link #isCaptured}). A
 * multipart body is capped like a form (a body over {@code max-form-bytes} is {@code 413} — raise that key for a
 * plugin that takes uploads); its fields are not parsed for the plugin or for the CSRF check, so a CSRF-checked
 * multipart route takes the token in the {@code X-CSRF-TOKEN} header.
 *
 * <h2>Why capture, rather than rebuild {@code form()} from the parameter map</h2>
 * Rebuilding would mean taking the container's merged parameters and subtracting the query string's values, which
 * leaves {@code body()} empty, hands body limits to the container's own form limits instead of a Runway cap,
 * decodes with the container's rules, and relies on the merge order to subtract a name that appears in both. A
 * bounded capture keeps one reader of the bytes, one cap, one parser (the dispatcher's), and a raw {@code body()}.
 *
 * <h2>What it touches</h2>
 * Only a request that (a) matches a registered route by method and path (any method: with Boot's form filter
 * kept off plugin routes, capturing is what lets a {@code PATCH} or {@code DELETE} form carry its {@code _csrf}
 * field), (b) has a bound site — exactly the requests {@link EndpointDispatchFilter} serves — and (c) carries a form
 * or multipart content type. Anything else passes through untouched: a JSON or other raw body is not parsed by
 * {@code getParameter()} and is read by the dispatcher <em>after</em> security, under
 * {@code runway.endpoints.max-body-bytes} for a signed-in caller and {@code max-form-bytes} for anyone else.
 *
 * <h2>Why a separate, small cap</h2>
 * This filter runs before authentication, CSRF and the suspended/maintenance gates, so whatever it buffers, any
 * anonymous client can make it buffer. Using the 10 MiB raw-body cap here let one request hold 10 MiB of heap
 * before any check — far more than a form of text fields needs. (The container's own form limit was no better: it
 * is 16 MiB, raised by {@code JettyFormLimitsConfig} for the Messages editor; since rc1 a caller who is not signed in
 * is held to this same cap on every other path too, by {@link PreAuthBodyLimitFilter}.) The capture is therefore
 * bounded by {@code max-form-bytes} (default
 * {@value #DEFAULT_MAX_FORM_BYTES} bytes — a form of text fields), or by {@code max-body-bytes} if that is smaller.
 * A body over it is answered {@code 413} without reading past the cap, and a declared {@code Content-Length} over
 * it is refused without reading at all. The buffer is sized from the declared length, so it does not double while
 * it grows.
 *
 * <h2>Placement</h2>
 * {@link mx.quest.runway.web.FilterOrder#CORE_ROUTE_FORM_CAPTURE}: inner of site binding (it needs the site), outer
 * of the tenant gates and of the plugin TENANT window — so no plugin filter between site binding and security can
 * consume the body first. The consequence: a capped body is read before a suspended or maintenance-mode site answers
 * {@code 503}. Filters <em>outer</em> of it (the trusted-proxy gate, the protection floor, egress filters, plugin
 * EDGE filters) must never call {@code getParameter()} on a POST; the kernel's do not.
 */
public class RouteFormCaptureFilter extends OncePerRequestFilter {

    /** The default for {@code runway.endpoints.max-form-bytes}: 256 KiB. */
    public static final long DEFAULT_MAX_FORM_BYTES = 262_144;

    private static final Logger log = LoggerFactory.getLogger(RouteFormCaptureFilter.class);

    private final EndpointContributorRegistry registry;
    private final SiteResolver siteResolver;
    /** The effective pre-authentication cap: the smaller of max-form-bytes and max-body-bytes. */
    private final long captureCap;

    /** A filter whose form cap is {@link #DEFAULT_MAX_FORM_BYTES} (or {@code maxBodyBytes} if smaller). */
    public RouteFormCaptureFilter(EndpointContributorRegistry registry, SiteResolver siteResolver, long maxBodyBytes) {
        this(registry, siteResolver, maxBodyBytes, DEFAULT_MAX_FORM_BYTES);
    }

    /**
     * @param maxBodyBytes {@code runway.endpoints.max-body-bytes} — the dispatcher's cap for any route body
     * @param maxFormBytes {@code runway.endpoints.max-form-bytes} — the cap for a form body read before security;
     *                     the effective cap is the smaller of the two (a value {@code <= 0} means 0: every non-empty
     *                     form body to a route is refused)
     */
    public RouteFormCaptureFilter(EndpointContributorRegistry registry, SiteResolver siteResolver, long maxBodyBytes,
                                  long maxFormBytes) {
        this.registry = registry;
        this.siteResolver = siteResolver;
        this.captureCap = Math.max(0, Math.min(maxFormBytes, maxBodyBytes));
    }

    /** The cap actually applied to a captured form body (for diagnostics and tests). */
    public long captureCap() {
        return captureCap;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean form = EndpointDispatchFilter.isForm(request.getContentType());
        if (!form && !EndpointDispatchFilter.isMultipart(request.getContentType())) {
            chain.doFilter(request, response);
            return;
        }
        Optional<Match> matched = registry.match(request.getMethod(), request.getRequestURI());
        if (matched.isEmpty() || siteResolver.current().isEmpty()) {
            chain.doFilter(request, response); // not a route the dispatcher would serve
            return;
        }
        byte[] body;
        try {
            long declared = request.getContentLengthLong();
            if (declared > captureCap) {
                throw new BodyTooLargeException();
            }
            body = EndpointDispatchFilter.readBounded(request.getInputStream(), captureCap, declared);
        } catch (BodyTooLargeException tooLarge) {
            log.warn("endpoint '{}' rejected {} {}: form body exceeds the {}-byte cap (runway.endpoints.max-form-bytes)",
                    matched.get().contributor().key(), request.getMethod(), request.getRequestURI(), captureCap);
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE); // 413
            return;
        }
        chain.doFilter(new CapturedFormRequest(request, body, EndpointDispatchFilter.charsetOf(request), form),
                response);
    }

    /**
     * Whether {@code request} (or a request it wraps) carries a body this filter captured for a plugin route — the
     * signal for an inner body parser (the kernel's {@code MultipartFilter}) to leave it alone.
     */
    public static boolean isCaptured(jakarta.servlet.ServletRequest request) {
        return org.springframework.web.util.WebUtils.getNativeRequest(request, CapturedFormRequest.class) != null;
    }
}
