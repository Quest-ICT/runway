package mx.quest.protection;

import java.util.List;
import mx.quest.runway.web.CspHostContributor;

/**
 * Allowlists <b>Google reCAPTCHA</b>'s domains in the kernel Content-Security-Policy (via the {@link
 * CspHostContributor} seam), so a configured challenge renders and verifies cleanly — and so a strict
 * (enforce-mode) CSP and a CAPTCHA can coexist instead of the policy blocking the widget's script, iframe and
 * verification beacon.
 *
 * <p>The plugin supports exactly one provider (reCAPTCHA v2/v3), so the allowlist is a <b>static, minimal</b>
 * pair — {@code www.google.com} (the widget script + challenge iframe) and {@code www.gstatic.com} (its static
 * assets) — with no host that never loads. (Before 0.4.0 the plugin shipped Turnstile + hCaptcha too and
 * contributed the union of all providers' hosts; collapsing to one provider removed that always-present-but-idle
 * surface. A future provider returning as an override-plugin would contribute its own hosts through this same
 * seam, and — being the active provider — would replace, not widen, the set.) The hosts widen {@code script-src}
 * (the widget script), {@code frame-src} (the challenge iframe) and {@code connect-src} (verification/telemetry).
 *
 * <p>Under the kernel's {@code script-src} {@code 'strict-dynamic'} (the nonce migration), the {@code script-src}
 * contribution becomes an <b>old-browser fallback</b>: on a modern browser the login page's nonced reCAPTCHA
 * loader is trusted directly and pulls its {@code gstatic} chunks transitively, so the host allowlist is ignored
 * there. The {@code connect-src} (verify beacon) and {@code frame-src} (challenge iframe) contributions stay
 * <b>load-bearing</b> — {@code 'strict-dynamic'} governs {@code script-src} only, never those — so the hosts must
 * remain on all three; do not drop them.
 *
 * <p>The public site keys and the secret never touch this: it is a static host allowlist only.
 */
public final class CaptchaCspContributor implements CspHostContributor {

    private static final List<String> HOSTS = List.of(
            "https://www.google.com", "https://www.gstatic.com"); // Google reCAPTCHA v2/v3 (script + iframe + connect)

    @Override
    public List<String> cspHosts() {
        return HOSTS;
    }
}
