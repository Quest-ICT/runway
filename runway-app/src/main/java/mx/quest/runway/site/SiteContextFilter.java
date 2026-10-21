package mx.quest.runway.site;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the request's host to a site and binds it for the duration of the request, then clears
 * the binding. Runs early — in the TENANT band, before any security chain — so every downstream
 * decision can read {@link SiteResolver#current()}. A request whose host matches no site (an
 * application-realm host, or an unconfigured server) simply proceeds with no site bound.
 */
public class SiteContextFilter extends OncePerRequestFilter {

    private final SiteResolver resolver;

    /** The request attribute holding the id of the site that answered the request. */
    public static final String SITE_ID_ATTRIBUTE = "runway.siteId";

    public SiteContextFilter(SiteResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        resolver.resolve(request.getServerName()).ifPresent(site -> {
            resolver.bind(site);
            // Kept on the request too: the thread binding ends with the chain, but an egress filter that stamps a
            // header at the very end (the CSP filter, on a 304) still needs to know which site answered.
            request.setAttribute(SITE_ID_ATTRIBUTE, site.id());
        });
        try {
            chain.doFilter(request, response);
        } finally {
            resolver.clear();
        }
    }
}
