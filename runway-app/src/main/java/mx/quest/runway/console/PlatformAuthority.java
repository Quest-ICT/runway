package mx.quest.runway.console;

import mx.quest.runway.authz.AuthorizationService;
import mx.quest.runway.security.RunwayUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The one place the operator console asks "may this caller perform a <b>platform</b> operation?" — plugin
 * management, app-level plugin settings, the restart, application configuration. These are reserved to the
 * application <b>master</b> or a <b>wildcard</b> admin and checked through {@link AuthorizationService}
 * ({@code isMaster}/{@code isWildcard}, application realm with no tenancy) — <b>not</b> a grantable permission
 * (the master-only operations are a target-based guard). Controllers call {@link #require} on every such
 * mutation so the gate can't be forgotten; it fails closed with {@code 403}.
 */
@Component
public class PlatformAuthority {

    private final AuthorizationService authorization;

    public PlatformAuthority(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    /** Whether {@code authentication} is the application master or a wildcard admin. */
    public boolean isPlatformAdmin(Authentication authentication) {
        return authentication != null
                && authentication.getPrincipal() instanceof RunwayUserDetails principal
                && (authorization.isMaster(principal, null) || authorization.isWildcard(principal, null));
    }

    /**
     * Fails closed with {@code 403} unless the caller is a platform admin. A {@link ResponseStatusException} so
     * the status is clean in every harness (mapped by the dispatcher, not only the security access-denied path).
     */
    public void require(Authentication authentication) {
        if (!isPlatformAdmin(authentication)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "this operation is restricted to the application master or a wildcard administrator");
        }
    }
}
