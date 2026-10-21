package mx.quest.runway.endpoint;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import mx.quest.runway.endpoint.EndpointDispatchFilter.BodyTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds the request body of a caller who is <b>not signed in</b>, on every kernel path, by the pre-authentication
 * cap ({@code runway.endpoints.max-form-bytes}, 256 KiB by default).
 *
 * <h2>Why</h2>
 * The container's own form limit is 16 MiB ({@code JettyFormLimitsConfig}: the Messages editor's no-JS fallback posts
 * a whole site's message grid as one form), and the first reader of a form body is the CSRF check, which looks for
 * the {@code _csrf} field with {@code getParameter()} <em>before</em> authorization. So any visitor could make the
 * server parse a 16 MiB form on any path — {@code POST /login} of either realm, {@code /setup}, the password-recovery
 * forms, or a console path that answers {@code 403} only afterwards. Plugin routes were already bounded
 * ({@link RouteFormCaptureFilter}); this filter gives every other path the same budget, so the most any anonymous
 * request can make the server buffer is one documented number.
 *
 * <h2>What it does</h2>
 * It sits inside each realm's security chain, just before the CSRF check (and the login filters), where the
 * session's {@code SecurityContext} is already loaded and {@code AccountStateFilter} (registered just before it) has
 * already dropped the sign-in of a suspended, deleted or password-changed account — so "signed in" means signed in
 * <em>now</em>, not "was signed in when the session was made". A request with a signed-in
 * principal passes untouched — an authenticated console keeps the container's limit for its large forms. For anyone
 * else:
 * <ul>
 *   <li>a declared {@code Content-Length} over the cap is answered {@code 413} at once, without reading the body;</li>
 *   <li>an {@code application/x-www-form-urlencoded} body of <em>unknown</em> length (chunked) is read here, once,
 *       under the cap ({@code 413} past it), and handed on as a {@link CapturedFormRequest} whose parameters come
 *       from those bytes — the container never parses it; a form with a declared length within the cap is left to
 *       the container, whose read is bounded by that length;</li>
 *   <li>any other body is wrapped in a {@link BoundedBodyRequest}, so a reader that streams it (a message
 *       converter, the route dispatcher) is stopped at the cap; such an overflow surfacing here is answered
 *       {@code 413}, while a controller's message converter answers it {@code 400} as unreadable.</li>
 * </ul>
 * Left alone: a body {@link RouteFormCaptureFilter} already captured for a plugin route (same cap), and a
 * {@code multipart/form-data} body, which the kernel's early multipart filter parses before security under
 * {@code spring.servlet.multipart.*}.
 *
 * <h2>The cap</h2>
 * The same {@code runway.endpoints.max-form-bytes} as plugin forms (or {@code max-body-bytes}, if smaller), not a
 * key of its own: it is the platform's one pre-authentication body budget. The kernel's anonymous forms (sign-in,
 * setup, password recovery) are well under a kilobyte, so it never constrains them; and a smaller cap for those
 * forms alone would not lower what an anonymous client can make the server hold, because any plugin form already
 * accepts this much.
 *
 * <p>Built per chain by {@link PreAuthBodyLimit#filter()}; never a servlet filter bean of its own (it needs the
 * chain's loaded security context).
 */
public final class PreAuthBodyLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PreAuthBodyLimitFilter.class);

    private final long cap;

    /** @param cap the most body bytes a caller who is not signed in may send ({@code < 0} means 0) */
    public PreAuthBodyLimitFilter(long cap) {
        this.cap = Math.max(0, cap);
    }

    /** The cap applied (for diagnostics and tests). */
    public long cap() {
        return cap;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contentType = request.getContentType();
        if (signedIn() || RouteFormCaptureFilter.isCaptured(request)
                || EndpointDispatchFilter.isMultipart(contentType)) {
            chain.doFilter(request, response);
            return;
        }
        long declared = request.getContentLengthLong();
        if (declared > cap) {
            refuse(request, response, "declares " + declared + " bytes");
            return;
        }
        if (EndpointDispatchFilter.isForm(contentType) && declared < 0) {
            byte[] body;
            try {
                body = EndpointDispatchFilter.readBounded(request.getInputStream(), cap);
            } catch (BodyTooLargeException tooLarge) {
                refuse(request, response, "streams a form past the cap");
                return;
            }
            // An empty body has nothing to parse: the container's view (the query string) is the right one.
            chain.doFilter(body.length == 0 ? request
                    : new CapturedFormRequest(request, body, EndpointDispatchFilter.charsetOf(request)), response);
            return;
        }
        BoundedBodyRequest bounded = new BoundedBodyRequest(request, cap);
        try {
            chain.doFilter(bounded, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            if (bounded.exceeded() && !response.isCommitted()) {
                refuse(request, response, "streams a body past the cap");
                return;
            }
            throw failure;
        }
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, String why) {
        log.warn("{} {} refused before sign-in: the body {} — over the {}-byte cap (runway.endpoints.max-form-bytes)",
                request.getMethod(), request.getRequestURI(), why, cap);
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE); // 413
        response.setHeader("Cache-Control", "no-store");
    }

    /**
     * Whether the current thread's security context holds a signed-in principal — not {@code null}, authenticated,
     * and not Spring's anonymous token. The contract both this filter and the route dispatcher use for "may send a
     * body up to the authenticated limits".
     */
    static boolean signedIn() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
