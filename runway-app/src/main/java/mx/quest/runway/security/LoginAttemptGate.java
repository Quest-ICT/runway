package mx.quest.runway.security;

/**
 * The kernel's SECURITY-band login brute-force <b>hook</b> — the per-IP attempt seam consulted before a login
 * is authenticated. This is the "floor in kernel, policy in plugin" boundary: the kernel owns
 * the hook (the {@link LoginProtectionFilter} that blocks a locked IP before authentication, and the event
 * feeding that calls {@link #recordFailure}/{@link #recordSuccess}); the <b>policy</b> — the sliding-window
 * thresholds, lockout duration, honeypot field, and CAPTCHA verification, configured per scope
 * ({@code app-protection.yml} / per-site {@code protection.yml}) — is the essential protection plugin's
 * implementation.
 *
 * <p>The kernel default ({@code PermissiveLoginAttemptGate}) never locks and ignores the records: without the
 * essential plugin there is no brute-force lockout (degrade-don't-crash). Implementations must be thread-safe and
 * keyed by the <b>gated</b> client IP ({@code ClientIp.of(request)}), never a raw forwarded header.
 */
public interface LoginAttemptGate {

    /** Whether logins from {@code clientIp} are currently locked out (checked before authentication). */
    boolean isLocked(String clientIp);

    /** Records a failed authentication from {@code clientIp} (fed from Spring Security auth-failure events). */
    void recordFailure(String clientIp);

    /** Records a successful authentication from {@code clientIp} (typically resets the counter). */
    void recordSuccess(String clientIp);
}
