package mx.quest.runway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The kernel's "remember this device" <b>hook</b> — the seam a realm's {@link TrustedDeviceSuccessHandler} calls
 * on a successful login to optionally mint a trusted-device cookie, so a later login from the same device may skip
 * the CAPTCHA challenge. This is the same "floor in kernel, policy in plugin" boundary as {@link LoginAttemptGate}
 * and {@link LoginFormGuard}: the kernel owns only the hook (the success handler wired into each realm chain); the
 * <b>policy</b> — whether the feature is enabled, the cookie's signing/scope/expiry, and the matching CAPTCHA-skip
 * decision on the next login — is the essential protection plugin's implementation.
 *
 * <p>The kernel default ({@link NoTrustedDeviceIssuer}) never issues a cookie, so with no plugin the CAPTCHA is
 * shown on every login exactly as before (degrade-don't-crash). Crucially, a failed {@code @Primary} override is
 * <b>fail-safe by construction</b>: the resolved issuer stays the no-op, so the only consequence is "more CAPTCHA,
 * never less" — so, unlike the lockout/form-guard seams, this one needs no essential-override integrity check.
 *
 * <p>Implementations must be side-effect-light and never throw out to the caller (the handler still guards with a
 * try/catch): cookie issuance must never break the post-login redirect.
 */
public interface TrustedDeviceIssuer {

    /**
     * On a successful login, issue a trusted-device cookie <b>iff</b> the request opted in (the "remember this
     * device" checkbox) and the feature is enabled for {@code scope}. A no-op otherwise. The cookie must be scoped
     * to {@code scope} (realm, and for a tenant its site) so it can never skip a CAPTCHA in another realm/site.
     *
     * @param request  the login POST (carries the opt-in field), already authenticated at this point
     * @param response the response the cookie is set on (before the redirect is written)
     * @param scope    the realm + resolved site this login belongs to
     */
    void rememberIfRequested(HttpServletRequest request, HttpServletResponse response, ProtectionScope scope);
}
