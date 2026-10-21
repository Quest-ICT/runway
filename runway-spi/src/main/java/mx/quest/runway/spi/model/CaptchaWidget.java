package mx.quest.runway.spi.model;

/**
 * A ready-to-embed CAPTCHA challenge for one site's form — the output of
 * {@link mx.quest.runway.spi.CaptchaService#widgetForSite(String)}. A consuming form (the contact form, or any
 * plugin/page form) just drops {@link #html()} into its markup and, on submit, reads the token from the
 * {@link #tokenField()} request parameter to verify it. Everything provider-specific (which vendor, the public
 * site key, the widget CSS class, the script URL, the invisible-v3 handling) is already baked into {@link #html()}
 * by the implementation, so the consumer needs no CAPTCHA-provider knowledge.
 *
 * @param active     whether a challenge is actually configured for the site — when {@code false}, {@link #html()}
 *                   is empty and {@link mx.quest.runway.spi.CaptchaService#verify verify} accepts any request, so a
 *                   form can render + verify unconditionally and simply has no CAPTCHA when none is set
 * @param tokenField the request-parameter name the widget posts its solved token under (empty when inactive)
 * @param html       the self-contained widget markup to insert into the form (the challenge div/hidden field plus
 *                   the provider script); empty when inactive. Contains only operator-configured values, safe to
 *                   embed as trusted markup
 */
public record CaptchaWidget(boolean active, String tokenField, String html) {

    public CaptchaWidget {
        tokenField = tokenField == null ? "" : tokenField;
        html = html == null ? "" : html;
    }

    /** The "no challenge configured" widget: renders nothing and never blocks a submission. */
    public static CaptchaWidget inactive() {
        return new CaptchaWidget(false, "", "");
    }
}
