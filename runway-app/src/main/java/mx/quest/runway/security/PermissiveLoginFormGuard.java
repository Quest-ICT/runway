package mx.quest.runway.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The kernel's default {@link LoginFormGuard}: it always returns {@link LoginFormVerdict#ok()}, applying no
 * form-level deterrent. Named (not an anonymous class) so the kernel can <b>positively detect</b> that an
 * essential protection plugin's {@code @Primary} override took effect — the same fail-closed check the platform
 * runs over {@link PermissiveLoginAttemptGate}: if an allowlisted-essential plugin loaded but the
 * resolved {@link LoginFormGuard} is still this class, the override silently failed.
 *
 * <p>Between now and the plugin's override there is no honeypot/CAPTCHA enforcement (degrade-don't-crash); the
 * harmless honeypot input rendered in the login templates is simply ignored.
 */
public final class PermissiveLoginFormGuard implements LoginFormGuard {

    @Override
    public LoginFormVerdict check(HttpServletRequest request, ProtectionScope scope) {
        return LoginFormVerdict.ok();
    }
}
