package mx.quest.runway.web;

import java.util.List;

/**
 * A seam by which a plugin widens the kernel's Content-Security-Policy <b>without editing code</b> — it
 * contributes extra allowlisted source hosts that are folded into the fetch directives ({@code default/script/
 * style/img/font/connect}) and {@code frame-src}, exactly like the operator's {@code runway.csp.extra-hosts}.
 * The kernel {@link CspConfig} collects every {@code CspHostContributor} bean at startup and merges the union
 * into the single policy {@link CspFilter} stamps.
 *
 * <p>Its reason for existing: a plugin that renders a third-party widget (a CAPTCHA provider's script + iframe +
 * verification beacon) needs those provider hosts in the CSP, or a strict ({@link RunwayCspProperties.Mode#ENFORCE
 * enforce}-mode) policy would block the widget outright and even a report-only policy would spam violation
 * reports. The essential <b>protection</b> plugin implements this to allowlist the CAPTCHA providers' domains, so
 * a configured challenge and a strict CSP coexist. The kernel stays provider-agnostic — it knows only "some
 * bean asked to widen the CSP", never which provider.
 *
 * <p>Collected once at context build (plugin {@code @Configuration}s register before refresh), so a contributor's
 * hosts are static for the process lifetime — the same "built once, cached" contract as the rest of the policy.
 * Implementations must return a stable, non-{@code null} list; a blank/duplicate entry is ignored by the merge.
 */
public interface CspHostContributor {

    /**
     * The extra CSP source hosts to allowlist — each a full source expression the browser accepts in a fetch
     * directive, e.g. {@code https://challenges.cloudflare.com} or {@code https://*.hcaptcha.com}. Never
     * {@code null} (return an empty list to contribute nothing).
     */
    List<String> cspHosts();
}
