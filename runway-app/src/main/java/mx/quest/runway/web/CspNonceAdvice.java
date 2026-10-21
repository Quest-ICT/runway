package mx.quest.runway.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the per-request CSP nonce — minted by {@link CspFilter} and published as the
 * {@link CspFilter#NONCE_REQUEST_ATTRIBUTE} request attribute — to <b>every</b> view as the {@code cspNonce}
 * model attribute, so a template can render {@code <script nonce="${cspNonce}">} and match the {@code script-src}
 * nonce in the CSP header.
 *
 * <p><b>Deliberately global</b> (no package/type restriction) rather than folded into the console model seam: the
 * console advice ({@code OperatorModelAdvice}) is scoped to {@code mx.quest.runway.console} and would miss the
 * tenant login/recovery pages rendered from {@code mx.quest.runway.security} (and any plugin-rendered page) — every
 * realm's page needs the nonce. A {@code @ModelAttribute} on a global advice runs before every handler; for a
 * {@code @ResponseBody}/REST handler the model value is simply unused (negligible cost — one request-attribute read).
 *
 * <p>Returns {@code ""} when no nonce is present (CSP {@link RunwayCspProperties.Mode#OFF}, or a path
 * {@link CspFilter} skipped), so a template binding never renders the literal {@code null}.
 */
@ControllerAdvice
public class CspNonceAdvice {

    /** Publishes {@code cspNonce} to the model from the request attribute the {@link CspFilter} set, or {@code ""}. */
    @ModelAttribute("cspNonce")
    public String cspNonce(HttpServletRequest request) {
        Object nonce = request.getAttribute(CspFilter.NONCE_REQUEST_ATTRIBUTE);
        return nonce instanceof String s ? s : "";
    }
}
