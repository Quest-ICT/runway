package mx.quest.runway.security;

import java.util.Objects;
import java.util.function.Supplier;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Authorizes a tenant-admin request against the <b>currently bound tenancy</b>, not by role alone.
 * Authentication is site-scoped, but a tenant {@code SecurityContext} carries no site identity, so a
 * stolen/replayed session would otherwise pass {@code /__admin} on any tenant's host (role-only check).
 * This manager re-binds the authenticated principal to {@link SiteResolver#current()} on every request:
 *
 * <ul>
 *   <li>a site must be bound;</li>
 *   <li>the principal must hold a tenant role; and</li>
 *   <li>the principal must belong to <em>this</em> tenancy — a tenant-master {@linkplain
 *       RunwayUserDetails#assignedTo(String) assigned} to it, or a sub-user whose record (matched by
 *       stable principal id) lives in this site's own store.</li>
 * </ul>
 *
 * It is the coarse-band stand-in for the {@code mayAccess} re-resolution; it closes the
 * cross-tenant session-replay vector.
 */
@Component
public class TenantSiteAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final SiteResolver siteResolver;
    private final SiteUserStore siteUsers;

    public TenantSiteAuthorizationManager(SiteResolver siteResolver, SiteUserStore siteUsers) {
        this.siteResolver = Objects.requireNonNull(siteResolver, "siteResolver");
        this.siteUsers = Objects.requireNonNull(siteUsers, "siteUsers");
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof RunwayUserDetails principal)) {
            return new AuthorizationDecision(false);
        }
        Site site = siteResolver.current().orElse(null);
        if (site == null || !hasTenantRole(principal)) {
            return new AuthorizationDecision(false);
        }
        return new AuthorizationDecision(assignedToCurrentSite(principal, site.id()));
    }

    private static boolean hasTenantRole(RunwayUserDetails principal) {
        return principal.getAuthorities().stream().map(a -> a.getAuthority()).anyMatch(authority ->
                authority.equals(Roles.AUTHORITY_PREFIX + Roles.SUPERADMIN)
                        || authority.equals(Roles.AUTHORITY_PREFIX + Roles.ADMIN)
                        || authority.equals(Roles.AUTHORITY_PREFIX + Roles.OPERATOR));
    }

    private boolean assignedToCurrentSite(RunwayUserDetails principal, String siteId) {
        if (principal.master()) {
            return principal.assignedTo(siteId); // a global tenant-master: its explicit assignment
        }
        // A sub-user belongs to the tenancy only if this site's own store holds it under the same id.
        return siteUsers.findByUsername(siteId, principal.getUsername())
                .map(stored -> stored.principalId().equals(principal.principalId()))
                .orElse(false);
    }
}
