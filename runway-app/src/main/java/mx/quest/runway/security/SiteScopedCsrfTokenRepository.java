package mx.quest.runway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * A tenant-realm {@link CsrfTokenRepository} that stores each site's CSRF token under its <b>own</b> session
 * attribute ({@code <base>::<siteId>}), delegating to a per-site {@link HttpSessionCsrfTokenRepository}. The
 * companion of {@link SiteScopedSecurityContextRepository}: it completes per-site isolation for the case where
 * an operator runs all tenants under a <b>shared cookie {@code Domain}</b> (one session across sites). Without
 * it, a login/logout on one site — which rotates the CSRF token — would invalidate an open form on another
 * site's tab (the same cross-tab 403 the per-realm split fixed, one level down). Normal host-per-site
 * deployments already isolate sites by their host-scoped session cookies; this simply makes the shared-domain
 * topology safe as well.
 *
 * <p>Same negligible cost as its companion: an O(1) site-id lookup + a {@link ConcurrentHashMap} get per call.
 */
public final class SiteScopedCsrfTokenRepository implements CsrfTokenRepository {

    private final SiteResolver siteResolver;
    private final String baseAttribute;
    private final ConcurrentMap<String, HttpSessionCsrfTokenRepository> delegates = new ConcurrentHashMap<>();

    public SiteScopedCsrfTokenRepository(SiteResolver siteResolver, String baseAttribute) {
        this.siteResolver = siteResolver;
        this.baseAttribute = baseAttribute;
    }

    private HttpSessionCsrfTokenRepository delegate(HttpServletRequest request) {
        String siteId = currentSiteId(request);
        return delegates.computeIfAbsent(siteId, id -> {
            HttpSessionCsrfTokenRepository repo = new HttpSessionCsrfTokenRepository();
            repo.setSessionAttributeName(baseAttribute + "::" + id);
            return repo;
        });
    }

    private String currentSiteId(HttpServletRequest request) {
        Site site = siteResolver.current()
                .or(() -> siteResolver.resolve(request.getServerName()))
                .orElse(null);
        return site != null ? site.id() : SiteScopedSecurityContextRepository.NO_SITE;
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return delegate(request).generateToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        delegate(request).saveToken(token, request, response);
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return delegate(request).loadToken(request);
    }

    @Override
    public DeferredCsrfToken loadDeferredToken(HttpServletRequest request, HttpServletResponse response) {
        return delegate(request).loadDeferredToken(request, response);
    }
}
