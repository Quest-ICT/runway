package mx.quest.runway.authz;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Turns on Spring's annotation-driven method security so service and controller methods can carry
 * {@code @PreAuthorize("@authz.may('…')")} (via {@link AuthzExpressions}). This is the per-operation
 * enforcement layer that complements the request-gate {@link PermissionAuthorizationManager}: business
 * operations declare the permission they require right at the method, and the check routes through the same
 * {@link AuthorizationService#mayAccess} engine.
 *
 * <p>It runs <em>behind</em> the realm/tenancy-entry gates in the {@code SecurityFilterChain}s — those
 * establish that the caller legitimately belongs to the current realm and tenancy; this decides what, within
 * that scope, the caller may do.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class MethodSecurityConfig {
}
