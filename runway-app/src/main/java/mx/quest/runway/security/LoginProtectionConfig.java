package mx.quest.runway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the login brute-force hooks: the kernel's permissive default {@link LoginAttemptGate} (the per-IP
 * lockout seam), the permissive default {@link LoginFormGuard} (the request-aware honeypot/CAPTCHA seam), and the
 * {@link LoginAttemptEventListener} that feeds the gate from authentication events. The per-realm
 * {@link LoginProtectionFilter} shells are added inside the realm chains (see {@code AppSecurityConfig} /
 * {@code TenantSecurityConfig}), never the wizard chain. The essential protection plugin supplies its
 * own {@code @Primary} implementations of both seams, which win by-type injection over these always-present
 * defaults (a robust override independent of bean-definition ordering, unlike {@code @ConditionalOnMissingBean}
 * in a user config).
 */
@Configuration(proxyBeanMethods = false)
public class LoginProtectionConfig {

    /** The kernel default: never locks, ignores records. The essential plugin's {@code @Primary} gate wins. */
    @Bean
    public LoginAttemptGate permissiveLoginAttemptGate() {
        return new PermissiveLoginAttemptGate();
    }

    /**
     * The kernel default: always passes, no form-level deterrent. The essential plugin's {@code @Primary}
     * {@link LoginFormGuard} (the honeypot/CAPTCHA policy) wins by-type injection over this.
     */
    @Bean
    public LoginFormGuard permissiveLoginFormGuard() {
        return new PermissiveLoginFormGuard();
    }

    @Bean
    public LoginAttemptEventListener loginAttemptEventListener(LoginAttemptGate gate) {
        return new LoginAttemptEventListener(gate);
    }

    /**
     * The kernel default: never issues a trusted-device cookie, so the CAPTCHA is shown on every login. The
     * essential protection plugin's {@code @Primary} {@link TrustedDeviceIssuer} wins by-type injection over this;
     * a failed override degrades safely (CAPTCHA always shown), so no integrity check gates it.
     */
    @Bean
    public TrustedDeviceIssuer noTrustedDeviceIssuer() {
        return new NoTrustedDeviceIssuer();
    }
}
