package mx.quest.runway.spi;

/**
 * An <b>application-realm</b> control over a single site's login protection — the operator's rescue seam for a
 * tenant that has locked itself out of its own console with a misconfigured CAPTCHA.
 *
 * <p>A site manager configures its own login CAPTCHA from the site console; if those keys are wrong (an invalid
 * or provider-mismatched site key), the challenge can never be solved and the manager can no longer sign in to
 * fix it. Because per-site login protection is honeypot + CAPTCHA only (the per-IP lockout and IP blocklist are
 * application-scope, already operator-managed), the <b>only</b> site-level lock-out vector is CAPTCHA — so this
 * seam lets an application operator switch a specific site's CAPTCHA off from the app-realm Sites screen, without
 * needing to authenticate into that tenant.
 *
 * <p><b>Provided by the platform, armed by a plugin.</b> The kernel ships a no-op default (no protection plugin ⇒
 * a site never has a CAPTCHA to disable), so the Sites screen compiles and runs with or without the protection
 * plugin installed. The essential <b>protection</b> plugin supplies the real implementation ({@code @Primary}),
 * reading and rewriting each site's own per-site protection config. The app console therefore depends only on this
 * SPI, never on the protection plugin.
 *
 * <p><b>Non-destructive.</b> {@link #disableCaptcha(String)} only flips the site's CAPTCHA <i>off</i>; it preserves
 * the site's stored provider and keys (the secret stays in its encrypted form), so once the manager is back in they
 * can correct the keys and re-enable. It does not touch the honeypot, the app-scope lockout, or the IP blocklist.
 *
 * <h2>Limits &amp; failure</h2>
 * Thread-safe: the app console may call it from several operator requests at once. A failure to persist the change
 * surfaces as an exception to the calling console action; nothing is half-written.
 *
 * <p><b>Threading.</b> The host calls this concurrently from many request threads; an implementation must be
 * stateless or internally synchronized.
 */
public interface SiteLoginProtectionControl {

    /**
     * Whether {@code siteId}'s login CAPTCHA is switched on (it may still be misconfigured — this reflects the
     * enable flag, not whether the keys are valid). Drives the app Sites screen's rescue affordance.
     *
     * @param siteId the tenant to inspect
     * @return {@code true} if the site has login CAPTCHA enabled; {@code false} otherwise (including when no
     *         protection plugin is installed)
     */
    boolean captchaEnabled(String siteId);

    /**
     * Force {@code siteId}'s login CAPTCHA off — an operator rescue for a site locked out by a broken CAPTCHA.
     * Preserves the site's stored provider/keys and every other protection setting; a no-op when the site already
     * has CAPTCHA off, has no protection config, or no protection plugin is installed. Applies without a restart.
     *
     * @param siteId the tenant to rescue
     */
    void disableCaptcha(String siteId);
}
