package mx.quest.protection;

import mx.quest.runway.console.AssetVersion;
import mx.quest.runway.security.ProtectionScope;
import mx.quest.runway.spi.CaptchaService;
import mx.quest.runway.spi.model.CaptchaWidget;

/**
 * The protection plugin's {@link CaptchaService} implementation — the reusable site-form CAPTCHA seam, backed by
 * each site's own per-site CAPTCHA settings (the same {@link ProtectionConfigStore} + {@link CaptchaVerifier} that
 * guard the login form). Registered {@code @Primary} so it wins by-type injection over the kernel's permissive
 * {@code NoCaptchaService} default wherever this essential plugin is present.
 *
 * <p>{@link #widgetForSite(String)} resolves the site scope and, when its challenge is
 * {@link ProtectionSettings.Captcha#active() active}, returns a self-contained widget: the visible
 * <b>reCAPTCHA v2</b> renders a challenge div the provider script auto-fills into a hidden field the form
 * submits; the invisible <b>reCAPTCHA v3</b> renders a hidden token field plus a small submit-time execute
 * script. Only the <b>public</b> site key is ever embedded — the secret is read solely server-side by
 * {@link CaptchaVerifier} in {@link #verify}. An inactive site yields {@link CaptchaWidget#inactive()} and
 * {@code verify} accepts unconditionally, so a form is never blocked by a CAPTCHA it did not configure.
 */
public class CaptchaWidgetService implements CaptchaService {

    /** The kernel-served reCAPTCHA v3 client (shared with the operator login pages). */
    private static final String V3_CLIENT = "/__assets/captcha-v3.js";

    private final ProtectionConfigStore configStore;
    private final CaptchaVerifier verifier;
    /** The kernel's v3 client URL, carrying the {@code ?v=} asset token when one was supplied. */
    private final String v3ClientUrl;

    /**
     * Without an asset version: the v3 client is referenced unversioned, which the kernel serves {@code no-cache}
     * (always revalidated) — correct, only less cache-efficient. Kept for callers built before the token existed.
     */
    public CaptchaWidgetService(ProtectionConfigStore configStore, CaptchaVerifier verifier) {
        this(configStore, verifier, null);
    }

    /**
     * @param assetVersion the kernel's asset token, appended as {@code ?v=} to the {@code /__assets/captcha-v3.js}
     *                     reference so an upgrade is never shadowed by a day-cached old client; {@code null} leaves
     *                     it unversioned
     */
    public CaptchaWidgetService(ProtectionConfigStore configStore, CaptchaVerifier verifier,
                                AssetVersion assetVersion) {
        this.configStore = configStore;
        this.verifier = verifier;
        this.v3ClientUrl = assetVersion == null ? V3_CLIENT : assetVersion.url(V3_CLIENT);
    }

    @Override
    public CaptchaWidget widgetForSite(String siteId) {
        ProtectionSettings.Captcha captcha = captchaFor(siteId);
        if (!captcha.active()) {
            return CaptchaWidget.inactive();
        }
        String provider = captcha.provider();
        return new CaptchaWidget(true, CaptchaVerifier.tokenParam(provider), renderHtml(captcha, provider, v3ClientUrl));
    }

    @Override
    public boolean verify(String siteId, String token, String clientIp) {
        ProtectionSettings.Captcha captcha = captchaFor(siteId);
        if (!captcha.active()) {
            return true; // no challenge configured for this site — nothing to prove
        }
        return verifier.verify(captcha, token, clientIp);
    }

    private ProtectionSettings.Captcha captchaFor(String siteId) {
        return configStore.resolve(ProtectionScope.forSite(siteId)).captcha();
    }

    /** The self-contained widget markup for an active challenge — div/hidden field + provider script. */
    private static String renderHtml(ProtectionSettings.Captcha captcha, String provider, String v3ClientUrl) {
        String siteKey = attr(captcha.siteKey());
        String script = attr(CaptchaVerifier.scriptUrl(provider, captcha.siteKey()));
        if (CaptchaVerifier.isInvisible(provider)) {
            // reCAPTCHA v3: invisible — a hidden token field + a submit-time execute for every form carrying one.
            String field = attr(CaptchaVerifier.tokenParam(provider));
            return "<input type=\"hidden\" class=\"rw-captcha-v3\" name=\"" + field + "\" data-sitekey=\"" + siteKey
                    + "\">"
                    + "<script src=\"" + script + "\" async defer></script>"
                    // The v3 client is served once from the kernel at /__assets/captcha-v3.js (shared with the
                    // operator login pages) — an inline <script> here would violate the tightened script-src.
                    + "<script src=\"" + attr(v3ClientUrl) + "\" async defer></script>";
        }
        // Visible providers auto-render into <div class="<widgetClass>" data-sitekey="…"> and post a hidden field.
        return "<div class=\"" + attr(CaptchaVerifier.widgetClass(provider)) + " rw-captcha\" data-sitekey=\""
                + siteKey + "\"></div>"
                + "<script src=\"" + script + "\" async defer></script>";
    }

    /** Minimal attribute escaping for the operator-configured values embedded in the widget markup. */
    private static String attr(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
