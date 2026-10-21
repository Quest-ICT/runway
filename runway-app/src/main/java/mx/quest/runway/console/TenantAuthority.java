package mx.quest.runway.console;

import mx.quest.runway.authz.AuthorizationService;
import mx.quest.runway.security.RunwayUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The tenant-console authority gate: "may this caller administer <b>this tenancy</b>?" — the tenant superadmin
 * (master of the tenancy) or a tenant wildcard admin, checked through {@link AuthorizationService} scoped to the
 * bound {@code tenancyId}. The tenant-realm analogue of {@link PlatformAuthority}; per-tenant plugin config and
 * the permissions surface gate on it. Fails closed with {@code 403}.
 */
@Component
public class TenantAuthority {

    private final AuthorizationService authorization;

    public TenantAuthority(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    /** Whether {@code authentication} administers {@code tenancyId} (its master or a wildcard admin there). */
    public boolean isTenantAdmin(Authentication authentication, String tenancyId) {
        return authentication != null
                && authentication.getPrincipal() instanceof RunwayUserDetails principal
                && (authorization.isMaster(principal, tenancyId) || authorization.isWildcard(principal, tenancyId));
    }

    /** Fails closed with {@code 403} unless the caller administers {@code tenancyId}. */
    public void require(Authentication authentication, String tenancyId) {
        if (!isTenantAdmin(authentication, tenancyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "this operation is restricted to the tenancy's administrators");
        }
    }
}
