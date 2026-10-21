package mx.quest.runway.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

/**
 * The realm login-success handler that (1) offers the {@link TrustedDeviceIssuer} a chance to mint a
 * "remember this device" cookie, then (2) performs the realm's normal post-login redirect. It replaces the plain
 * {@code defaultSuccessUrl(url, true)} in each realm chain — the ONLY response-aware hook that runs on a
 * successful login, which is where a cookie can still be set before the redirect commits.
 *
 * <p>The cookie must be issued <b>before</b> the redirect is written, so the issuer is called first and the
 * delegate ({@link SimpleUrlAuthenticationSuccessHandler} with {@code alwaysUseDefaultTargetUrl}, matching the old
 * {@code defaultSuccessUrl(url, true)} semantics) writes the redirect afterwards. Issuance is wrapped in a
 * try/catch so a cookie fault can never break the login — the user is redirected regardless.
 *
 * <p>The {@link ProtectionScope} is built at success time exactly as {@link LoginProtectionFilter#scope()} does:
 * the app realm is always {@link ProtectionScope#application()}; a tenant realm resolves the current site
 * ({@link SiteResolver#current()}) — still bound on this request by the site context filter — so the cookie is
 * scoped to the site the login belongs to. A tenant login whose site can't be resolved yields a site-less scope
 * (the issuer then declines to mint an unscoped cookie).
 */
public final class TrustedDeviceSuccessHandler implements AuthenticationSuccessHandler {

    private final TrustedDeviceIssuer issuer;
    private final ProtectionScope.Realm realm;
    private final SiteResolver siteResolver; // null for the application realm
    private final SimpleUrlAuthenticationSuccessHandler delegate;

    /**
     * @param issuer          the trusted-device cookie policy seam (kernel no-op by default; plugin {@code @Primary})
     * @param realm           the realm this chain authenticates
     * @param siteResolver    resolves the bound site for a {@link ProtectionScope.Realm#TENANT} chain; {@code null}
     *                        for the application realm
     * @param defaultTargetUrl where to redirect after login (e.g. {@code /__app} or {@code /__admin}); always used,
     *                        mirroring {@code defaultSuccessUrl(url, true)}
     */
    public TrustedDeviceSuccessHandler(TrustedDeviceIssuer issuer, ProtectionScope.Realm realm,
                                       SiteResolver siteResolver, String defaultTargetUrl) {
        this.issuer = issuer;
        this.realm = realm;
        this.siteResolver = siteResolver;
        this.delegate = new SimpleUrlAuthenticationSuccessHandler(defaultTargetUrl);
        this.delegate.setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        try {
            issuer.rememberIfRequested(request, response, scope());
        } catch (RuntimeException e) {
            // Fail-safe: a cookie-issuance fault must never break the login redirect.
        }
        delegate.onAuthenticationSuccess(request, response, authentication);
    }

    /** The realm + resolved-site scope for this success, mirroring {@link LoginProtectionFilter#scope()}. */
    private ProtectionScope scope() {
        if (realm == ProtectionScope.Realm.APPLICATION) {
            return ProtectionScope.application();
        }
        return ProtectionScope.forSite(siteResolver == null ? null
                : siteResolver.current().map(Site::id).orElse(null));
    }
}
