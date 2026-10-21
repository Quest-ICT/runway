package mx.quest.runway.authz;

import java.util.Objects;
import mx.quest.runway.site.SiteResolver;
import org.springframework.stereotype.Component;

/**
 * Factory for {@code mayAccess}-backed request gates, injected into a {@code SecurityFilterChain} bean so a
 * matcher can require a concrete {@link Permission}: {@code .requestMatchers("/__app/styles/**")
 * .access(requestAuthorizers.requirePermission(Permissions.MANAGE_STYLES))}. Centralizing
 * construction keeps the {@link AuthorizationService} / {@link SiteResolver} wiring in one place so chains
 * stay declarative.
 */
@Component
public class RequestAuthorizers {

    private final AuthorizationService authz;
    private final SiteResolver siteResolver;

    public RequestAuthorizers(AuthorizationService authz, SiteResolver siteResolver) {
        this.authz = Objects.requireNonNull(authz, "authz");
        this.siteResolver = Objects.requireNonNull(siteResolver, "siteResolver");
    }

    /** A request gate that passes only when the current principal {@code mayAccess} {@code permission}. */
    public PermissionAuthorizationManager requirePermission(Permission permission) {
        return new PermissionAuthorizationManager(permission, authz, siteResolver);
    }
}
