package mx.quest.runway.spi;

import mx.quest.runway.spi.model.CaptchaWidget;

/**
 * A reusable CAPTCHA seam for <b>site forms</b> — the contact form and any other plugin/page form that wants a
 * challenge. It exposes the two halves a form needs while keeping every provider detail (the provider — currently
 * Google reCAPTCHA v2 &amp; v3 —, the site's keys, the score threshold) behind this interface:
 *
 * <ol>
 *   <li>{@link #widgetForSite(String)} — the ready-to-embed {@link CaptchaWidget} for a site's currently-configured
 *       challenge (or an inactive one when none is set);</li>
 *   <li>{@link #verify(String, String, String)} — server-side verification of the token the widget posted back.</li>
 * </ol>
 *
 * <p><b>Provided by the platform, armed by a plugin.</b> The kernel ships a permissive default (no challenge is
 * ever configured, so widgets are inactive and verification always accepts) so a form works with or without a
 * CAPTCHA provider installed. The essential <b>protection</b> plugin supplies the real implementation
 * ({@code @Primary}), reading each site's own per-site CAPTCHA settings — so a form gets a live challenge exactly
 * when the tenant has configured one, and nothing otherwise. A consumer therefore depends only on this SPI, never
 * on the protection plugin.
 *
 * <p><b>Fail-safe.</b> When a site has no active challenge, {@link #widgetForSite} returns
 * {@link CaptchaWidget#inactive()} and {@link #verify} returns {@code true} — a form is never blocked by a CAPTCHA
 * it did not ask for.
 *
 * <h2>Limits &amp; failure</h2>
 * {@link #verify} contacts the configured provider (currently Google reCAPTCHA v2/v3) and may block for several seconds
 * (the protection plugin allows 5 s to connect and 5 s for the answer, so up to about 10 s);
 * when the provider cannot be reached it <b>fails open</b> (returns {@code true}) — a provider outage must not take
 * every site form down with it. Implementations are thread-safe.
 *
 * <p><b>Threading.</b> The host calls this concurrently from many request threads; an implementation must be
 * stateless or internally synchronized.
 */
public interface CaptchaService {

    /**
     * The challenge widget for {@code siteId}'s current configuration, or {@link CaptchaWidget#inactive()} when the
     * site has no active CAPTCHA. Safe to call per render.
     *
     * @param siteId the tenant whose CAPTCHA settings apply
     * @return the embeddable widget (never {@code null})
     */
    CaptchaWidget widgetForSite(String siteId);

    /**
     * Verifies a solved CAPTCHA token for {@code siteId} server-side.
     *
     * @param siteId   the tenant whose CAPTCHA settings (provider, secret, threshold) apply
     * @param token    the token the widget posted back (the {@link CaptchaWidget#tokenField()} request parameter);
     *                 may be {@code null}/blank if the challenge was not solved
     * @param clientIp the requester's IP to pass as the provider's optional {@code remoteip} hint, or {@code null}
     * @return {@code true} if the site has no active challenge OR the token is valid (including a provider-outage
     *         fail-open); {@code false} if a challenge is active and the token is missing or rejected
     */
    boolean verify(String siteId, String token, String clientIp);
}
