package mx.quest.runway.authz;

import java.util.Objects;
import java.util.function.Supplier;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.spi.model.Realm;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * A request-gate {@link AuthorizationManager} that authorizes a matched request against one fixed
 * {@link Permission} via {@link AuthorizationService#mayAccess} — the {@code .access(...)} counterpart of the
 * {@code @authz.may(...)} method-security expression, sharing the same engine, precedence and tenancy
 * scoping. Build one with {@link RequestAuthorizers#requirePermission(Permission)} and attach it to a chain
 * matcher; compose it with the realm/tenancy-entry gate (e.g. {@code AuthorizationManagers.allOf(...)}) when a
 * path needs both "belongs to this tenancy" and "holds this permission".
 *
 * <p>Fail-closed: no/anonymous authentication or a non-{@link RunwayUserDetails} principal denies before the
 * engine is consulted. The tenancy is resolved from {@link SiteResolver#current()} for a tenant-realm
 * permission, or {@code null} for an application-realm one.
 */
public final class PermissionAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final Permission permission;
    private final AuthorizationService authz;
    private final SiteResolver siteResolver;

    PermissionAuthorizationManager(Permission permission, AuthorizationService authz, SiteResolver siteResolver) {
        this.permission = Objects.requireNonNull(permission, "permission");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.siteResolver = Objects.requireNonNull(siteResolver, "siteResolver");
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof RunwayUserDetails principal)) {
            return new AuthorizationDecision(false);
        }
        String tenancyId = permission.realm() == Realm.APPLICATION
                ? null
                : siteResolver.current().map(Site::id).orElse(null);
        return new AuthorizationDecision(authz.mayAccess(principal, permission, tenancyId));
    }
}
