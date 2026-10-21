package mx.quest.runway.authz;

import java.util.Objects;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.service.AccessCheck;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The method-security expression root, registered as the bean {@code authz} so guarded methods can write
 * {@code @PreAuthorize("@authz.may('EDIT_PAGES')")}. It bridges Spring's per-method check to the single
 * {@link AuthorizationService#mayAccess} engine, resolving both halves of the call from request state:
 *
 * <ul>
 *   <li>the <b>principal</b> from the active {@code SecurityContext} (the app or tenant context the matched
 *       chain installed — only one is current per request, so this picks the right realm automatically);</li>
 *   <li>the <b>tenancy</b> from {@link SiteResolver#current()} for a tenant-realm permission, or {@code null}
 *       for an application-realm one (application permissions are not tenancy-scoped).</li>
 * </ul>
 *
 * <p>It is <b>fail-closed</b> end to end: no authentication, a non-{@link RunwayUserDetails} principal, or an
 * undeclared permission key all yield {@code false} before the engine is consulted. This is the
 * <em>per-operation</em> layer; it runs <em>behind</em> the realm/tenancy-entry gates in the security chains
 * (which establish that the principal legitimately belongs to the current realm and tenancy), so trusting the
 * principal's role claims here is sound.
 *
 * <p>It is also the kernel's implementation of the SPI {@link AccessCheck} (since SPI 0.5.0): a plugin injects
 * {@code AccessCheck} by type and gets this bean, so plugins use exactly the check the console uses. Off a request
 * thread there is no {@code SecurityContext}, so every check answers {@code false}; an anonymous endpoint route is
 * dispatched with an empty context for the same effect.
 */
@Component("authz")
public class AuthzExpressions implements AccessCheck {

    private final AuthorizationService authz;
    private final PermissionCatalog catalog;
    private final SiteResolver siteResolver;

    public AuthzExpressions(AuthorizationService authz, PermissionCatalog catalog, SiteResolver siteResolver) {
        this.authz = Objects.requireNonNull(authz, "authz");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.siteResolver = Objects.requireNonNull(siteResolver, "siteResolver");
    }

    /**
     * Whether the current principal may exercise the permission named {@code permissionKey} in the current
     * scope. The permission's realm is taken from the catalog (so the key alone is enough); an undeclared key
     * fails closed.
     */
    @Override
    public boolean may(String permissionKey) {
        RunwayUserDetails principal = currentPrincipal();
        if (principal == null) {
            return false;
        }
        Permission permission = catalog.find(permissionKey).orElse(null);
        if (permission == null) {
            return false; // an undeclared key can never authorize
        }
        String tenancyId = permission.realm() == Realm.APPLICATION ? null : currentTenancyId();
        return authz.mayAccess(principal, permission, tenancyId);
    }

    /** Whether the current principal may exercise {@code permissionKey} in an <b>explicitly named</b> tenancy. */
    public boolean mayInTenancy(String permissionKey, String tenancyId) {
        RunwayUserDetails principal = currentPrincipal();
        if (principal == null) {
            return false;
        }
        Permission permission = catalog.find(permissionKey).orElse(null);
        if (permission == null || permission.realm() == Realm.APPLICATION) {
            return false; // tenancy-scoped form is for tenant-realm permissions only
        }
        return authz.mayAccess(principal, permission, tenancyId);
    }

    private static RunwayUserDetails currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof RunwayUserDetails principal ? principal : null;
    }

    private String currentTenancyId() {
        return siteResolver.current().map(Site::id).orElse(null);
    }
}
