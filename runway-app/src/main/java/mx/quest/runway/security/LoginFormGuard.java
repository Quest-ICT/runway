package mx.quest.runway.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The kernel's SECURITY-band login <b>request-aware</b> hook — the per-request seam consulted on a login POST,
 * after the per-IP {@link LoginAttemptGate} lockout check and before authentication runs. It is the second half
 * of the "floor in kernel, policy in plugin" boundary for login protection: {@link LoginAttemptGate} sees only
 * the client IP (it cannot read the form body), so deterrents that inspect the submitted request — the honeypot
 * trap field today, a CAPTCHA token next — need this richer seam that receives the whole
 * {@link HttpServletRequest} plus its resolved {@link ProtectionScope}.
 *
 * <p>The kernel default ({@link PermissiveLoginFormGuard}) always returns {@link LoginFormVerdict#ok()}: between
 * now and the essential protection plugin's {@code @Primary} override there is no form-level deterrent
 * (degrade-don't-crash). Implementations must be thread-safe, must resolve their policy solely from the
 * <b>scope</b> (never from anything the client supplies), and must fail <b>safe</b> — any internal error should
 * degrade to {@link LoginFormVerdict#ok()} so a misconfiguration or store fault never locks every user out.
 */
public interface LoginFormGuard {

    /**
     * The hidden trap field a human never fills but a form-filling bot does. Shared by the guard (which rejects a
     * non-blank value) and the login templates (which render an off-screen input of this name), so the two can
     * never drift.
     */
    String HONEYPOT_FIELD = "website";

    /**
     * Evaluates the login POST for the given scope.
     *
     * @param request the login POST request (its parameters carry the honeypot field and, later, the CAPTCHA token)
     * @param scope   the resolved realm/site the login belongs to — selects which protection config governs it
     * @return {@link LoginFormVerdict#ok()} to let authentication proceed, or a rejection carrying a reason code
     */
    LoginFormVerdict check(HttpServletRequest request, ProtectionScope scope);
}
