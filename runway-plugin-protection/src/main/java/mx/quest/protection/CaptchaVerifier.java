package mx.quest.protection;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies an external CAPTCHA token server-side against the provider's {@code siteverify} endpoint, using the
 * scope's own secret. It is the second, request-aware half of the login form deterrent (after the honeypot):
 * {@link LoginFormPolicyGuard} calls it on a login POST when the scope's {@link ProtectionSettings.Captcha} is
 * {@link ProtectionSettings.Captcha#active() active}.
 *
 * <p>The supported provider is <b>Google reCAPTCHA</b> in both modes — <b>v2</b> (the visible checkbox) and
 * <b>v3</b> (invisible, score-based). Both share one {@code siteverify} shape: an
 * {@code application/x-www-form-urlencoded} POST of {@code secret}/{@code response}(/{@code remoteip}) to
 * {@value #RECAPTCHA_URL}, answered by a JSON body carrying a boolean {@code success}. Only <b>v3</b> additionally
 * returns a 0..1 risk {@code score}, which this verifier requires to meet the configured
 * {@link ProtectionSettings.Captcha#scoreThreshold() threshold}; v2 treats {@code success:true} as a pass.
 *
 * <p><b>One provider by design.</b> Cloudflare Turnstile and hCaptcha were dropped in 0.4.0: a single provider
 * keeps the kernel CSP's {@code script-src}/{@code frame-src}/{@code connect-src} allowlist minimal and static
 * ({@code www.google.com www.gstatic.com}) with nothing that never loads. The provider-shaped static helpers
 * ({@link #tokenParam}, {@link #widgetClass}, {@link #scriptUrl}, {@link #isInvisible}) are kept as a thin seam so
 * a future provider can return as an override-plugin without reshaping the call sites.
 *
 * <p><b>Two deliberate failure stances</b> keep the deterrent from ever locking out a legitimate user while still
 * rejecting a real bot:
 * <ul>
 *   <li><b>Fail on a missing/rejected token.</b> A blank token (the widget never ran, or the field was stripped)
 *       and a {@code success:false} response both return {@code false} — the challenge was <em>not</em> proven.</li>
 *   <li><b>Fail <u>open</u> on a transport error.</b> If the provider cannot be reached (network fault, timeout,
 *       non-2xx status, unparseable body), verification returns {@code true} with a warning — a provider outage
 *       must not lock every user out. The always-on honeypot and per-IP lockout still apply.</li>
 * </ul>
 *
 * <p>The provider HTTP call is injected as an {@link Exchange} seam so the request-building and response-parsing
 * (including the v3 score gate and the fail-open path) are unit-testable without touching the network; the
 * production seam is a short-timeout JDK {@link HttpClient}. This class is stateless and thread-safe.
 */
public class CaptchaVerifier {

    private static final Logger log = LoggerFactory.getLogger(CaptchaVerifier.class);

    /** The Google reCAPTCHA {@code siteverify} endpoint — shared by both v2 and v3. */
    static final String RECAPTCHA_URL = "https://www.google.com/recaptcha/api/siteverify";

    /** The reCAPTCHA v3 (invisible, score-based) provider id. */
    static final String RECAPTCHA_V3 = "recaptcha-v3";

    /** The default provider — reCAPTCHA v2 (checkbox). Any blank/unknown/legacy value normalizes to this. */
    static final String DEFAULT_PROVIDER = "recaptcha-v2";

    private final Exchange exchange;

    /** Production verifier: a JDK {@link HttpClient} with a short connect/read timeout as the {@link Exchange}. */
    public CaptchaVerifier() {
        this(defaultExchange());
    }

    /**
     * @param exchange the HTTP seam that performs the {@code siteverify} POST — injectable so tests exercise the
     *                 request-building, response-parsing and fail-open behaviour without a real network call
     */
    public CaptchaVerifier(Exchange exchange) {
        this.exchange = exchange;
    }

    /**
     * The request parameter the reCAPTCHA widget posts its solved token under ({@code g-recaptcha-response} — the
     * same for v2 and v3). Shared by the guard (which reads this parameter from the login POST) and the login-view
     * contributor (which names the widget's hidden field), so the two can never drift.
     *
     * @param provider the configured provider id; retained for call-site stability (both reCAPTCHA modes use the
     *                 same field, so the value does not change the result today)
     */
    public static String tokenParam(String provider) {
        return "g-recaptcha-response";
    }

    /**
     * The provider's widget CSS class the client script scans for to auto-render the challenge; empty for
     * reCAPTCHA v3 (invisible — no rendered widget div). Shared by the login-view contributor and the site-form
     * {@code CaptchaService} so the two render identically.
     */
    public static String widgetClass(String provider) {
        return isInvisible(provider) ? "" : "g-recaptcha";
    }

    /**
     * The provider's widget script {@code src}. reCAPTCHA v3 needs the site key spliced into {@code ?render=} so
     * the invisible flow can execute for that key on submit; v2 loads the script keyless and reads the key from
     * the widget div's {@code data-sitekey}.
     */
    public static String scriptUrl(String provider, String siteKey) {
        return isInvisible(provider)
                ? "https://www.google.com/recaptcha/api.js?render=" + enc(siteKey == null ? "" : siteKey)
                : "https://www.google.com/recaptcha/api.js";
    }

    /** Whether the provider is the invisible reCAPTCHA v3 (needs a submit-time execute rather than a visible widget). */
    public static boolean isInvisible(String provider) {
        return RECAPTCHA_V3.equals(normalizeProvider(provider));
    }

    /**
     * Verifies {@code token} for the given captcha {@code config}, calling reCAPTCHA's {@code siteverify}
     * endpoint through the injected {@link Exchange}.
     *
     * @param config   the scope's captcha policy (provider, secret, score threshold); the public site key is not
     *                 used here — verification is entirely secret-side
     * @param token    the solved token the widget posted back (the {@link #tokenParam(String)} request parameter)
     * @param remoteIp the gated client IP to pass as the optional {@code remoteip} hint, or {@code null}/blank to
     *                 omit it
     * @return {@code true} if reCAPTCHA confirmed the challenge (and, for v3, the score met the threshold), OR if
     *         the provider could not be reached (fail-open); {@code false} if the token is missing or rejected
     */
    public boolean verify(ProtectionSettings.Captcha config, String token, String remoteIp) {
        if (token == null || token.isBlank()) {
            // No token proven — the widget never ran or the field was stripped. Not verified.
            return false;
        }
        String provider = normalizeProvider(config.provider());
        String body = "secret=" + enc(config.secretKey())
                + "&response=" + enc(token)
                + (remoteIp != null && !remoteIp.isBlank() ? "&remoteip=" + enc(remoteIp) : "");
        String responseBody;
        try {
            Exchange.Response resp = exchange.post(RECAPTCHA_URL, body);
            if (resp == null || resp.statusCode() / 100 != 2) {
                log.warn("reCAPTCHA provider '{}' returned HTTP {} — failing open.",
                        provider, resp == null ? "no response" : resp.statusCode());
                return true; // fail-open: a provider outage never locks everyone out
            }
            responseBody = resp.body();
        } catch (IOException | RuntimeException e) {
            log.warn("reCAPTCHA verification could not reach the provider ({}) — failing open.", e.toString());
            return true; // fail-open on any transport fault
        }

        if (!SiteVerifyResponse.success(responseBody)) {
            return false; // provider actively rejected the token
        }
        if (RECAPTCHA_V3.equals(provider)) {
            double score = SiteVerifyResponse.score(responseBody);
            double threshold = config.scoreThreshold();
            if (score < threshold) {
                log.info("reCAPTCHA v3 score {} below threshold {} — rejecting.", score, threshold);
                return false;
            }
        }
        return true;
    }

    /**
     * Normalizes any stored provider id to one of the two supported reCAPTCHA modes: {@link #RECAPTCHA_V3} when it
     * names v3, else {@link #DEFAULT_PROVIDER} ({@code recaptcha-v2}). A legacy value from a dropped provider
     * (Turnstile / hCaptcha) therefore migrates to v2 — the site keeps working after upgrade, though its operator
     * must supply a reCAPTCHA key for the challenge to actually pass. Blank/null also maps to v2.
     */
    private static String normalizeProvider(String provider) {
        if (provider != null && RECAPTCHA_V3.equals(provider.trim().toLowerCase(Locale.ROOT))) {
            return RECAPTCHA_V3;
        }
        return DEFAULT_PROVIDER;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static Exchange defaultExchange() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return (url, body) -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                return new Exchange.Response(resp.statusCode(), resp.body());
            } catch (InterruptedException e) {
                // Restore the interrupt flag and surface as an IOException so the verifier fails open like any
                // other transport fault (a provider hiccup must never lock everyone out).
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while verifying CAPTCHA", e);
            }
        };
    }

    /**
     * The injectable HTTP seam — a single form POST to reCAPTCHA's {@code siteverify} endpoint. Real deployments
     * use the JDK {@link HttpClient} exchange ({@link #defaultExchange()}); tests supply a stub that records the
     * URL/body and returns a canned {@link Response} (or throws) to exercise every branch without a network.
     */
    @FunctionalInterface
    public interface Exchange {

        /**
         * POSTs {@code formBody} ({@code application/x-www-form-urlencoded}) to {@code url}.
         *
         * @return the provider's response (status + body)
         * @throws IOException if the request cannot be completed (the verifier treats this as fail-open)
         */
        Response post(String url, String formBody) throws IOException;

        /**
         * A minimal HTTP response view — only the status code and textual body the verifier needs.
         *
         * @param statusCode the HTTP status code
         * @param body       the response body (the provider's {@code siteverify} JSON)
         */
        record Response(int statusCode, String body) {
        }
    }
}
