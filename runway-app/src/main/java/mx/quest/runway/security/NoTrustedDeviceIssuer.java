package mx.quest.runway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The kernel's default {@link TrustedDeviceIssuer}: it never issues a cookie, so without the essential protection
 * plugin the "remember this device" feature is simply off and the CAPTCHA is shown on every login. Named (not an
 * anonymous class) to match the other permissive defaults; unlike them it needs no positive-override detection,
 * because a failed override degrades to "CAPTCHA always shown" — the safe direction.
 */
public final class NoTrustedDeviceIssuer implements TrustedDeviceIssuer {

    @Override
    public void rememberIfRequested(HttpServletRequest request, HttpServletResponse response, ProtectionScope scope) {
        // no-op until the protection plugin supplies a policy issuer
    }
}
