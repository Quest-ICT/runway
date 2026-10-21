package mx.quest.runway.endpoint;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.endpoint.EndpointContributorRegistry.Match;
import mx.quest.runway.page.LocaleNegotiator;
import mx.quest.runway.plugin.ContributionGuard;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteViews;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SurfacePosture;
import mx.quest.runway.web.ClientIp;
import mx.quest.runway.web.RequestOrigin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

/**
 * Dispatches a request matching a registered {@link mx.quest.runway.spi.EndpointContributor} route to its
 * {@code handle(RouteRequest)}, then writes the {@link RouteResponse} and applies the route's
 * {@link SurfacePosture} ({@code X-Robots-Tag}/{@code Cache-Control}) — so a careless route can never silently
 * pollute the index or the cache (fail-safe: not indexable, not cacheable by default). It runs as a terminal
 * handler ({@code FilterOrder.CORE_ENDPOINT_DISPATCH}); a non-matching request, or one with no bound site (the
 * routes are tenant-scoped), passes straight through to the page controller.
 *
 * <h2>The {@link RouteRequest} it builds (SPI 0.5.0)</h2>
 * <ul>
 *   <li>{@code query} is parsed from the URL query string alone, and {@code form} from an
 *       {@code application/x-www-form-urlencoded} body <b>whatever the method</b> ({@code POST}, {@code PUT},
 *       {@code PATCH}, {@code DELETE}), both by this class (never the container's parameter map, which merges the two
 *       and consumes the body). Both are capped at {@value #MAX_PARAMETERS} fields; a malformed encoding or more
 *       fields answer {@code 400}. A form body — and a {@code multipart/form-data} body — has already been read, once
 *       and under the same cap, by {@link RouteFormCaptureFilter} before anything could consume it (the CSRF check
 *       looking for a token, Spring's form-content and multipart parsers); this class takes the captured bytes (and
 *       a form's parsed fields) from the {@link CapturedFormRequest}, and reads the stream itself only for any other
 *       body. A multipart body is handed over raw in {@code body()}: its fields are not parsed, so {@code form()} is
 *       empty and a CSRF-checked multipart route takes its token in the {@code X-CSRF-TOKEN} header.</li>
 *   <li>{@code locale} by {@link LocaleNegotiator#forSite} ({@code ?lang} → cookie → {@code Accept-Language} → site
 *       default).</li>
 *   <li>{@code clientIp} from {@link ClientIp} and {@code origin} from the request's scheme, host and port — both
 *       as the trusted-proxy gate ({@code TrustedForwardedHeaderFilter}) left them, never from a raw
 *       {@code X-Forwarded-*} header (which that gate has already applied or hidden).</li>
 *   <li>header names lowercased (the record does that).</li>
 * </ul>
 *
 * <h2>Anonymous routes</h2>
 * A route declared {@linkplain mx.quest.runway.spi.model.Route#anonymous() anonymous} is exempt from CSRF in the
 * tenant chain ({@link AnonymousRouteMatcher}). This filter enforces the other half of that bargain: such a route is
 * handed only an {@code application/x-www-form-urlencoded} body (anything else is {@code 415}), and its handler runs
 * with an <b>empty</b> {@code SecurityContext} — so whatever a forged cross-site submission makes it do, it cannot
 * do as the visitor's signed-in identity ({@code AccessCheck} answers {@code false}). The original context is
 * restored afterwards.
 *
 * <h2>Body caps: who is signed in</h2>
 * Security has run by the time this filter reads a body, but a plugin route is {@code permitAll} at the chain and
 * decides access itself, inside {@code handle} — so for a caller who is not signed in, this read is still
 * <em>before</em> any authentication. The cap therefore depends on the caller: a signed-in principal's body is read
 * under {@code runway.endpoints.max-body-bytes} (10 MiB by default); anyone else's — and any body on an
 * {@linkplain mx.quest.runway.spi.model.Route#anonymous() anonymous} route — under the pre-authentication budget,
 * {@code runway.endpoints.max-form-bytes} (256 KiB, or {@code max-body-bytes} if smaller), the same cap
 * {@link RouteFormCaptureFilter} puts on a form. A declared {@code Content-Length} over the applicable cap is
 * answered {@code 413} without reading the body.
 */
public class EndpointDispatchFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(EndpointDispatchFilter.class);

    /** The most query or form fields one request may carry (Jetty's own form default). */
    static final int MAX_PARAMETERS = 1000;

    static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

    static final String MULTIPART_CONTENT_TYPE = "multipart/form-data";

    private final EndpointContributorRegistry registry;
    private final SiteResolver siteResolver;
    /** Cap on the request body a plugin endpoint is handed, so a large body can't exhaust the heap. */
    private final long maxBodyBytes;
    /** The cap for a caller who is not signed in (or an anonymous route): the smaller of max-form and max-body. */
    private final long preAuthBytes;

    /** The containment policy around {@code handle}. */
    private final ContributionGuard guard;

    /** Unit-test form: failures are logged and answered 500 without plugin attribution or metrics. */
    public EndpointDispatchFilter(EndpointContributorRegistry registry, SiteResolver siteResolver, long maxBodyBytes) {
        this(registry, siteResolver, maxBodyBytes, ContributionGuard.standalone());
    }

    /** A dispatcher whose pre-authentication cap is {@link RouteFormCaptureFilter#DEFAULT_MAX_FORM_BYTES}. */
    public EndpointDispatchFilter(EndpointContributorRegistry registry, SiteResolver siteResolver, long maxBodyBytes,
                                  ContributionGuard guard) {
        this(registry, siteResolver, maxBodyBytes, RouteFormCaptureFilter.DEFAULT_MAX_FORM_BYTES, guard);
    }

    /**
     * @param maxBodyBytes {@code runway.endpoints.max-body-bytes} — the cap on a signed-in caller's body
     * @param maxFormBytes {@code runway.endpoints.max-form-bytes} — with {@code maxBodyBytes}, the smaller of the two
     *                     caps the body of a caller who is not signed in, and any anonymous route's
     */
    public EndpointDispatchFilter(EndpointContributorRegistry registry, SiteResolver siteResolver, long maxBodyBytes,
                                  long maxFormBytes, ContributionGuard guard) {
        this.registry = registry;
        this.siteResolver = siteResolver;
        this.maxBodyBytes = maxBodyBytes;
        this.preAuthBytes = PreAuthBodyLimit.of(maxBodyBytes, maxFormBytes).cap();
        this.guard = guard;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // HEAD is answered like GET without the body (RFC 9110 §9.3.2), as Spring MVC answers it for its own GET
        // routes: a route declared GET only is matched for HEAD too, unless the plugin declared HEAD itself. Before
        // 1.0.0 a HEAD fell through to the page controller and was told 404 (a CDN probing /favicon.svg saw none).
        boolean head = "HEAD".equalsIgnoreCase(request.getMethod());
        Optional<Match> matched = registry.match(request.getMethod(), request.getRequestURI());
        String routeMethod = request.getMethod();
        if (matched.isEmpty() && head) {
            matched = registry.match("GET", request.getRequestURI());
            routeMethod = "GET"; // the handler sees the GET it declared; only the answer drops the body
        }
        Site site = siteResolver.current().orElse(null);
        if (matched.isEmpty() || site == null) {
            chain.doFilter(request, response); // not a route, or no tenant context — let the page controller try
            return;
        }
        Match match = matched.get();
        boolean anonymous = match.route().anonymous();
        if (anonymous && !isForm(request.getContentType())) {
            refuse(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE); // 415: an anonymous route takes forms only
            return;
        }
        // Read before the handler decides access, so a caller who is not signed in gets the pre-authentication cap.
        boolean preAuth = anonymous || !PreAuthBodyLimitFilter.signedIn();
        long cap = preAuth ? preAuthBytes : maxBodyBytes;
        RouteRequest routeRequest;
        try {
            routeRequest = materialize(request, site, cap, routeMethod);
        } catch (BodyTooLargeException tooLarge) {
            log.warn("endpoint '{}' rejected {} {}: request body exceeds the {}-byte cap ({})",
                    match.contributor().key(), request.getMethod(), request.getRequestURI(), cap,
                    preAuth ? "not signed in: runway.endpoints.max-form-bytes" : "runway.endpoints.max-body-bytes");
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE); // 413
            return;
        } catch (MalformedParametersException malformed) {
            log.debug("endpoint '{}' rejected {} {}: {}", match.contributor().key(), request.getMethod(),
                    request.getRequestURI(), malformed.getMessage());
            refuse(response, HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        // Contained: a handler that throws (or breaks the contract with null) is answered 500, no-store, with nothing of
        // its output — logged with the stack once per minute per route and counted against its plugin. Only a
        // RuntimeException or LinkageError is contained; any other Error propagates (the JVM is not healthy).
        RouteResponse result;
        SecurityContext original = anonymous ? SecurityContextHolder.getContext() : null;
        if (anonymous) {
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext()); // no principal, by contract
        }
        try {
            result = guard.callNonNull(match.contributor(), ContributionGuard.Axis.ENDPOINT,
                    match.contributor().key() + " " + match.route().method() + " " + match.route().pathPattern(),
                    () -> match.contributor().handle(routeRequest), () -> null);
        } finally {
            if (anonymous) {
                SecurityContextHolder.setContext(original);
            }
        }
        if (result == null) {
            refuse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        write(response, result, match.route().posture(), head);
    }

    private static void refuse(HttpServletResponse response, int status) {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
    }

    private RouteRequest materialize(HttpServletRequest request, Site site, long cap, String method)
            throws IOException {
        Charset charset = charsetOf(request);
        Map<String, List<String>> query = parse(request.getQueryString(), StandardCharsets.UTF_8); // URIs are UTF-8
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.putIfAbsent(name, request.getHeader(name));
        }
        CapturedFormRequest captured = WebUtils.getNativeRequest(request, CapturedFormRequest.class);
        byte[] body;
        Map<String, List<String>> form;
        if (captured != null) {
            body = captured.body();
            form = captured.bodyFields();
        } else {
            long declared = request.getContentLengthLong();
            if (declared > cap) {
                throw new BodyTooLargeException(); // refused on the declared length, nothing read
            }
            body = readBounded(request.getInputStream(), cap, declared);
            form = isForm(request.getContentType()) ? parse(new String(body, charset), charset) : Map.of();
            if (body.length == 0 && request.getContentLengthLong() > 0) {
                // Something outer of the capture consumed the body (getParameter on a form): the route sees none.
                log.warn("{} {}: the request declared a {}-byte body but none was left to read; a filter outer of "
                        + "the route form capture consumed it", request.getMethod(), request.getRequestURI(),
                        request.getContentLengthLong());
            }
        }
        String lang = query.getOrDefault("lang", List.of()).stream().findFirst().orElse(null);
        List<Locale> accepted = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE) == null
                ? List.of() : Collections.list(request.getLocales());
        Locale locale = LocaleNegotiator.forSite(site, lang, cookie(request, site.id()), accepted);
        return new RouteRequest(SiteViews.toView(site), locale, method, request.getRequestURI(), query,
                headers, body, form, ClientIp.of(request), origin(request));
    }

    /** {@code scheme://host[:port]} as the trusted-proxy gate left the request ({@link RequestOrigin#of}). */
    static String origin(HttpServletRequest request) {
        return RequestOrigin.of(request);
    }

    private static String cookie(HttpServletRequest request, String siteId) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = LocaleNegotiator.preferenceCookieName(siteId);
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    static boolean isForm(String contentType) {
        return FORM_CONTENT_TYPE.equalsIgnoreCase(mediaType(contentType));
    }

    /** Whether {@code contentType} is {@code multipart/form-data} (parameters ignored). */
    static boolean isMultipart(String contentType) {
        return MULTIPART_CONTENT_TYPE.equalsIgnoreCase(mediaType(contentType));
    }

    private static String mediaType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        return (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType).trim();
    }

    /** The charset a request's form body is decoded with: its declared encoding, else UTF-8. */
    static Charset charsetOf(HttpServletRequest request) {
        String encoding = request.getCharacterEncoding();
        if (encoding != null) {
            try {
                return Charset.forName(encoding);
            } catch (RuntimeException unsupported) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8; // the platform serves every page in UTF-8, so its forms submit UTF-8
    }

    /**
     * Parses {@code name=value&…} (URL-encoded, {@code +} as space), keeping order and repeated names. A field with
     * no {@code =} has an empty value. Empty pairs ({@code &&}) are skipped.
     *
     * @throws MalformedParametersException on a malformed escape or more than {@link #MAX_PARAMETERS} fields
     */
    static Map<String, List<String>> parse(String encoded, Charset charset) throws MalformedParametersException {
        if (encoded == null || encoded.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        int fields = 0;
        for (String pair : encoded.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            if (++fields > MAX_PARAMETERS) {
                throw new MalformedParametersException("more than " + MAX_PARAMETERS + " fields");
            }
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                out.computeIfAbsent(URLDecoder.decode(name, charset), k -> new ArrayList<>())
                        .add(URLDecoder.decode(value, charset));
            } catch (IllegalArgumentException badEscape) {
                throw new MalformedParametersException("malformed percent-encoding");
            }
        }
        return out;
    }

    /**
     * Reads a request body into memory, aborting past {@code maxBodyBytes} ({@code runway.endpoints.max-body-bytes}).
     * A plugin route may declare any HTTP method, and a non-multipart body skips Spring's multipart size limit
     * entirely, so this cap is the only guard between a hostile/oversized body and a heap-exhaustion DoS. Shared
     * with {@link RouteFormCaptureFilter}, which reads a form body before security under its own, smaller cap
     * ({@code runway.endpoints.max-form-bytes}).
     *
     * @throws BodyTooLargeException past the cap (answered {@code 413}); nothing beyond it is buffered
     */
    static byte[] readBounded(InputStream in, long maxBodyBytes) throws IOException {
        return readBounded(in, maxBodyBytes, -1);
    }

    /**
     * {@link #readBounded(InputStream, long)} with the buffer pre-sized to {@code declaredLength} (when it is known
     * and within the cap), so a body of the declared size is read without the buffer doubling as it grows. The
     * declared length is only a size hint: the cap is still enforced on the bytes actually read.
     */
    static byte[] readBounded(InputStream in, long maxBodyBytes, long declaredLength) throws IOException {
        int initial = declaredLength > 0 && declaredLength <= maxBodyBytes ? (int) Math.min(declaredLength,
                Integer.MAX_VALUE - 8) : 32;
        ByteArrayOutputStream out = new ByteArrayOutputStream(initial);
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBodyBytes) {
                throw new BodyTooLargeException();
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Signals that the request body exceeded {@link #maxBodyBytes}; mapped to {@code 413} by the dispatch. */
    static final class BodyTooLargeException extends IOException {
    }

    /** A query string or form body this class will not hand to a plugin; mapped to {@code 400}. */
    static final class MalformedParametersException extends IOException {
        MalformedParametersException(String message) {
            super(message);
        }
    }

    /** Writes the handler's answer; for a HEAD, the status and headers (with the body's length) but no body. */
    private static void write(HttpServletResponse response, RouteResponse result, SurfacePosture posture,
                              boolean head) throws IOException {
        response.setStatus(result.status());
        new ArrayList<>(result.headers().entrySet()).forEach(e -> response.setHeader(e.getKey(), e.getValue()));
        if (!posture.indexable()) {
            response.setHeader("X-Robots-Tag", "noindex, nofollow");
        }
        if (!posture.cacheable()) {
            response.setHeader("Cache-Control", "no-store");
        }
        byte[] bytes = result.body();
        response.setContentLengthLong(bytes.length);
        if (!head) {
            response.getOutputStream().write(bytes);
        }
    }
}
