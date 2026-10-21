package mx.quest.protection;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import mx.quest.runway.security.ProtectionScope;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * Makes a login PAGE render the CAPTCHA widget without putting any provider knowledge in the kernel login
 * controllers or templates. Registered by the plugin as a {@link HandlerInterceptor} scoped to the two login-view
 * GETs ({@code /app/login} and {@code /login}); on those requests it resolves the {@link ProtectionScope} exactly
 * as the {@link LoginProtectionFilter} does — the application scope for the operator login, the
 * {@link SiteResolver#current() current site} for a tenant login — and, when that scope's
 * {@link ProtectionSettings.Captcha} is {@link ProtectionSettings.Captcha#active() active}, adds the generic,
 * provider-agnostic model flags the kernel's "captcha goes here" template slot binds:
 *
 * <ul>
 *   <li>{@code captchaActive} — whether the widget/script should render at all;</li>
 *   <li>{@code captchaProvider} — the reCAPTCHA mode ({@code recaptcha-v2}/{@code recaptcha-v3}), plus the two
 *       booleans {@code captchaRecaptchaV2} / {@code captchaRecaptchaV3} so the template can pick the visible vs
 *       invisible widget shape without a string switch;</li>
 *   <li>{@code captchaSiteKey} — the <b>public</b> site key only;</li>
 *   <li>{@code captchaWidgetClass} — the widget CSS class the reCAPTCHA JS API looks for ({@code g-recaptcha}
 *       for v2; empty for the invisible v3);</li>
 *   <li>{@code captchaScriptUrl} — the provider's widget script {@code src} (for reCAPTCHA v3 it carries the
 *       {@code ?render=<siteKey>} the invisible-execute flow needs);</li>
 *   <li>{@code captchaTokenField} — the request parameter the widget posts its token under, so the v3 hidden
 *       field and the guard agree.</li>
 * </ul>
 *
 * <p><b>The CAPTCHA secret is never exposed.</b> Only {@link ProtectionSettings.Captcha#siteKey()} (public) and
 * the provider id reach the model — {@link ProtectionSettings.Captcha#secretKey()} is read solely server-side by
 * {@link CaptchaVerifier}. When the scope's captcha is inactive, the interceptor sets {@code captchaActive=false}
 * and nothing else, so the template renders no widget and no provider script.
 *
 * <p><b>Fail-safe.</b> A store fault degrades to {@link ProtectionSettings#defaults()} (captcha off) inside
 * {@link ProtectionConfigStore#resolve}, so a config problem hides the widget rather than breaking the login page.
 */
public final class CaptchaLoginViewContributor implements HandlerInterceptor {

    private static final String APP_LOGIN_PATH = "/app/login";
    private static final String TENANT_LOGIN_PATH = "/login";
    private static final String APP_FORGOT_PATH = "/app/forgot-password";
    private static final String TENANT_FORGOT_PATH = "/forgot-password";

    private final ProtectionConfigStore configStore;
    private final SiteResolver siteResolver;
    private final TrustedDeviceService trustedDeviceService;

    public CaptchaLoginViewContributor(ProtectionConfigStore configStore, SiteResolver siteResolver,
                                       TrustedDeviceService trustedDeviceService) {
        this.configStore = configStore;
        this.siteResolver = siteResolver;
        this.trustedDeviceService = trustedDeviceService;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView modelAndView) {
        if (modelAndView == null) {
            return; // a redirect or forward with no view/model — nothing to contribute to
        }
        // Only the GET view render gets the widget flags. A POST to a recovery path is a controller that returns a
        // "redirect:" — Spring would append every primitive model attribute we add to that redirect's query string
        // (captchaActive=…&captchaSiteKey=… leaking into the URL), so never contribute on a non-GET.
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return;
        }
        // Defensive: never contribute primitive model flags to a redirect/forward — Spring would splice them into
        // the target's query string. No mapped handler does this today (they all return a plain view), but a future
        // GET that bounced an already-authenticated user would otherwise leak captchaActive=…&rememberDeviceDays=….
        String viewName = modelAndView.getViewName();
        if (viewName != null && (viewName.startsWith("redirect:") || viewName.startsWith("forward:"))) {
            return;
        }
        String uri = LoginFormPolicyGuard.routedPath(request);
        ProtectionScope scope = scopeFor(uri);
        if (scope == null) {
            return; // not one of the two login-view GETs
        }
        ProtectionSettings.Captcha captcha = configStore.resolve(scope).captcha();
        // A trusted device skips the CAPTCHA entirely — the widget is not even rendered — but ONLY on the login
        // form (the forgot-password form always shows its CAPTCHA, matching the guard's login-only skip).
        boolean trusted = isLoginPath(uri) && trustedDeviceService.isTrusted(request, scope);
        apply(modelAndView, captcha, trusted, isLoginPath(uri));
    }

    /**
     * The scope for a login/recovery view GET path, or {@code null} if the interceptor does not serve it. The
     * password-recovery REQUEST form renders the same CAPTCHA as its realm's login (app scope for
     * {@code /app/forgot-password}, the current site's for {@code /forgot-password}), so a configured challenge
     * also guards the reset-email request against automated flooding.
     */
    private ProtectionScope scopeFor(String uri) {
        if (APP_LOGIN_PATH.equals(uri) || APP_FORGOT_PATH.equals(uri)) {
            return ProtectionScope.application();
        }
        if (TENANT_LOGIN_PATH.equals(uri) || TENANT_FORGOT_PATH.equals(uri)) {
            return ProtectionScope.forSite(siteResolver.current().map(Site::id).orElse(null));
        }
        return null;
    }

    /** Whether {@code uri} is a login form (not a forgot-password form): only there does "remember this device" apply. */
    private static boolean isLoginPath(String uri) {
        return APP_LOGIN_PATH.equals(uri) || TENANT_LOGIN_PATH.equals(uri);
    }

    /**
     * Populates the model with the generic captcha slot flags plus the "remember this device" offer. The CAPTCHA
     * renders only when active AND the device isn't already trusted (a trusted device sets {@code captchaActive=false}
     * so no widget/script/key is emitted and the guard's matching skip lines up). The remember-device checkbox is
     * offered only where a CAPTCHA is actually shown on a LOGIN form and the feature is usable. When the CAPTCHA is
     * hidden, only the two/three flags are set; the public site key is the only key that ever reaches the view.
     */
    private void apply(ModelAndView mav, ProtectionSettings.Captcha captcha, boolean trusted, boolean loginPath) {
        boolean showCaptcha = captcha.active() && !trusted;
        mav.addObject("captchaActive", showCaptcha);
        mav.addObject("rememberDeviceOffered", showCaptcha && loginPath && trustedDeviceService.isEnabled());
        mav.addObject("rememberDeviceDays", trustedDeviceService.rememberDays());
        if (!showCaptcha) {
            return;
        }
        // Both reCAPTCHA modes go through the same provider-shaped helpers (which normalize internally); the model
        // reduces to a single distinction — visible v2 vs invisible v3 — that the template branches on.
        boolean invisible = CaptchaVerifier.isInvisible(captcha.provider());
        mav.addObject("captchaProvider", invisible ? CaptchaVerifier.RECAPTCHA_V3 : CaptchaVerifier.DEFAULT_PROVIDER);
        mav.addObject("captchaRecaptchaV2", !invisible);
        mav.addObject("captchaRecaptchaV3", invisible);
        mav.addObject("captchaSiteKey", captcha.siteKey()); // PUBLIC key only — the secret never leaves the server
        mav.addObject("captchaWidgetClass", CaptchaVerifier.widgetClass(captcha.provider()));
        mav.addObject("captchaScriptUrl", CaptchaVerifier.scriptUrl(captcha.provider(), captcha.siteKey()));
        mav.addObject("captchaTokenField", CaptchaVerifier.tokenParam(captcha.provider()));
    }
}
