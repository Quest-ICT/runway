package mx.quest.runway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * A tenant-realm {@link SecurityContextRepository} that stores each site's {@code SecurityContext} under its
 * <b>own</b> session attribute ({@code <base>::<siteId>}), rather than one shared attribute for the whole tenant
 * chain. It resolves the request's site (host → site) and delegates to a per-site
 * {@link HttpSessionSecurityContextRepository} — so the real repository's load/save/logout semantics are reused
 * verbatim, only the attribute key varies.
 *
 * <h2>Why</h2>
 * The two realms already avoid crossing via distinct context keys, and two sites normally live on distinct
 * hosts → distinct host-scoped session cookies → distinct sessions, so they never share state. But an operator
 * who deliberately configures a <b>shared cookie {@code Domain}</b> (all tenants under one parent domain) would
 * put every site in <em>one</em> session; a single tenant context key would then allow only one tenant login at
 * a time (logging into site B would overwrite site A's principal). Keying the context by site id makes that
 * topology fully isolated too: independent, simultaneous logins per site in one browser. The per-request
 * cross-tenant authorization check ({@link TenantSiteAuthorizationManager}) still applies on top — this only
 * changes <em>where</em> each site's context is stored, never who may access what.
 *
 * <h2>Cost</h2>
 * Per operation: one {@code ThreadLocal}/host lookup for the site id plus one {@link ConcurrentHashMap} get —
 * both O(1), no I/O, no locking on the hot path. The delegate cache is bounded by the number of sites.
 */
public final class SiteScopedSecurityContextRepository implements SecurityContextRepository {

    /** Session-attribute key for a request that resolves to no site (e.g. an unmatched host / 404). */
    static final String NO_SITE = "__nosite";

    private final SiteResolver siteResolver;
    private final String baseKey;
    private final ConcurrentMap<String, HttpSessionSecurityContextRepository> delegates = new ConcurrentHashMap<>();

    public SiteScopedSecurityContextRepository(SiteResolver siteResolver, String baseKey) {
        this.siteResolver = siteResolver;
        this.baseKey = baseKey;
    }

    private HttpSessionSecurityContextRepository delegate(HttpServletRequest request) {
        String siteId = currentSiteId(request);
        return delegates.computeIfAbsent(siteId, id -> {
            HttpSessionSecurityContextRepository repo = new HttpSessionSecurityContextRepository();
            repo.setSpringSecurityContextKey(baseKey + "::" + id);
            return repo;
        });
    }

    /**
     * The current request's site id. Prefers the {@link SiteResolver#current() bound} site (the forwarded-aware
     * resolution the whole app uses, available because the security chain runs inside the site-context filter);
     * falls back to a direct host resolution, then to {@link #NO_SITE}. Load and save happen within the same
     * bound window, so both see the same id.
     */
    private String currentSiteId(HttpServletRequest request) {
        Site site = siteResolver.current()
                .or(() -> siteResolver.resolve(request.getServerName()))
                .orElse(null);
        return site != null ? site.id() : NO_SITE;
    }

    @Override
    @SuppressWarnings("deprecation")
    public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
        return delegate(requestResponseHolder.getRequest()).loadContext(requestResponseHolder);
    }

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return delegate(request).loadDeferredContext(request);
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        delegate(request).saveContext(context, request, response);
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        return delegate(request).containsContext(request);
    }
}
