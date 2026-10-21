package mx.quest.protection;

import jakarta.servlet.http.HttpServletRequest;
import mx.quest.runway.security.LoginFormGuard;
import mx.quest.runway.security.LoginFormVerdict;
import mx.quest.runway.security.ProtectionScope;
import mx.quest.runway.web.ClientIp;

/**
 * The plugin's {@code @Primary} {@link LoginFormGuard} — the request-aware login deterrent that arms the kernel's
 * form-guard seam with two policy checks, in order, over the scope's resolved {@link ProtectionSettings}:
 *
 * <ol>
 *   <li><b>Honeypot.</b> When enabled, a non-blank {@link LoginFormGuard#HONEYPOT_FIELD} trap field is a
 *       form-filling bot — rejected with {@link LoginFormVerdict.Reason#HONEYPOT} (which the
 *       {@code LoginProtectionFilter} maps to the generic {@code ?error} flag, so the bot cannot tell the trap
 *       tripped).</li>
 *   <li><b>CAPTCHA.</b> When the scope's {@link ProtectionSettings.Captcha} is
 *       {@link ProtectionSettings.Captcha#active() active} (enabled with both keys present), the provider's token
 *       — the {@link CaptchaVerifier#tokenParam(String) per-provider request parameter} — is verified server-side
 *       via {@link CaptchaVerifier}. A missing or provider-rejected token is rejected with
 *       {@link LoginFormVerdict.Reason#CAPTCHA} (mapped to a distinguishable {@code ?captcha} flag, so a
 *       legitimate user who fails the challenge sees a specific message). The challenge runs on <em>every</em>
 *       login POST while active — the operator opts in per scope; the honeypot and per-IP lockout still apply
 *       alongside it.</li>
 * </ol>
 *
 * <p>Registered as {@code @Primary} in {@link ProtectionConfig}, it wins by-type injection over the kernel's
 * {@link mx.quest.runway.security.PermissiveLoginFormGuard} default. The scope's config is resolved through
 * {@link ProtectionConfigStore} (the app store for an {@code APPLICATION} scope, the per-site store for a
 * {@code TENANT} scope); the store decrypts the captcha {@code secretKey} it hands back, so the verifier receives
 * plaintext and the secret never leaves the server.
 *
 * <p><b>Trusted-device skip (login form only).</b> When a <b>login</b> request carries a valid trusted-device
 * cookie for this scope ({@link TrustedDeviceService#isTrusted}), the CAPTCHA check is skipped — the "remember this
 * device" opt-in that lets a device which already logged in successfully avoid the challenge for a while. The skip
 * is scoped to the login paths only: the password-recovery REQUEST form (also guarded here) keeps its CAPTCHA even
 * for a trusted device, so it stays a full reset-flood deterrent. The honeypot still runs on both (a bot filling
 * the trap is dropped regardless), and the skip only ever removes the CAPTCHA, never authentication or the per-IP
 * lockout.
 *
 * <p><b>Fail-safe.</b> {@link ProtectionConfigStore#resolve} never throws (it degrades to defaults), the honeypot
 * check is a pure string test, and {@link CaptchaVerifier} <em>fails open</em> on a provider transport error — so
 * a store fault, a misconfiguration, or a provider outage can never lock out a legitimate user. Only a
 * genuinely-missing or genuinely-rejected CAPTCHA token blocks a login while the challenge is active.
 */
public final class LoginFormPolicyGuard implements LoginFormGuard {

    private final ProtectionConfigStore configStore;
    private final CaptchaVerifier captchaVerifier;
    private final TrustedDeviceService trustedDeviceService;

    public LoginFormPolicyGuard(ProtectionConfigStore configStore, CaptchaVerifier captchaVerifier,
                                TrustedDeviceService trustedDeviceService) {
        this.configStore = configStore;
        this.captchaVerifier = captchaVerifier;
        this.trustedDeviceService = trustedDeviceService;
    }

    @Override
    public LoginFormVerdict check(HttpServletRequest request, ProtectionScope scope) {
        ProtectionSettings settings = configStore.resolve(scope);

        if (settings.honeypotEnabled()) {
            String trap = request.getParameter(HONEYPOT_FIELD);
            if (trap != null && !trap.isBlank()) {
                return LoginFormVerdict.reject(LoginFormVerdict.Reason.HONEYPOT);
            }
        }

        ProtectionSettings.Captcha captcha = settings.captcha();
        // A trusted device (one that logged in successfully and opted in) skips the CAPTCHA — but ONLY on the login
        // form, and nothing else. The forgot-password form always challenges (its CAPTCHA deters reset-flooding).
        if (captcha.active() && !(isLoginForm(request) && trustedDeviceService.isTrusted(request, scope))) {
            String token = request.getParameter(CaptchaVerifier.tokenParam(captcha.provider()));
            if (!captchaVerifier.verify(captcha, token, ClientIp.of(request))) {
                return LoginFormVerdict.reject(LoginFormVerdict.Reason.CAPTCHA);
            }
        }

        return LoginFormVerdict.ok();
    }

    /** Whether this is a login POST (not a password-recovery request) — the only place the trusted skip applies. */
    private static boolean isLoginForm(HttpServletRequest request) {
        String path = routedPath(request);
        return "/app/login".equals(path) || "/login".equals(path);
    }

    /**
     * The decoded path the dispatcher routes on (servlet path + path info), so {@code /app/%6Cogin} is the login path
     * as Spring Security and MVC see it. Computed here rather than through a kernel helper so this plugin keeps
     * loading on an older kernel during a rollback.
     */
    static String routedPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        String path = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        return path.isEmpty() ? String.valueOf(request.getRequestURI()) : path;
    }

}
