package mx.quest.contact;

import java.util.Locale;
import mx.quest.runway.spi.service.Messages;

/**
 * Builds the contact-form markup the {@link ContactWidget} renders inline on a page. The form <b>POSTs</b> an
 * {@code application/x-www-form-urlencoded} body to {@value ContactEndpoint#SUBMIT_PATH}, a route the
 * {@link ContactEndpoint} declares <b>anonymous</b> ({@code Route.anonymousPost}): the kernel exempts exactly that
 * method and path from CSRF, because a public form on a cacheable page cannot carry a per-session token. So the
 * visitor's name, email and message travel in the request body — never in a URL, browser history, a
 * {@code Referer} or a proxy log. A hidden honeypot field ({@code website}) catches naive bots; {@code captchaHtml}
 * (when non-empty) adds the site's CAPTCHA challenge from the platform's {@code CaptchaService} — trusted,
 * pre-rendered markup. Every label is looked up with {@link Messages#site} for the visitor's locale; every value
 * that came from a request or a setting is HTML-escaped.
 */
final class ContactForm {

    private ContactForm() {
    }

    /**
     * Renders the contact form. It submits to the anonymous {@code POST} handler, which stores + emails the message
     * and answers {@code 303} back to {@code returnPath} with a {@code ?contact=sent|invalid|captcha|queued} flag —
     * so the visible page stays a normal, editable site page (the widget re-renders the outcome from that flag).
     */
    static String render(Messages messages, String siteId, Locale locale, String error, String captchaHtml,
                         String returnPath) {
        String nameLbl = messages.site(siteId, locale, "contact.form.name", "Name");
        String emailLbl = messages.site(siteId, locale, "contact.form.email", "Email");
        String messageLbl = messages.site(siteId, locale, "contact.form.message", "Message");
        String submitLbl = messages.site(siteId, locale, "contact.form.submit", "Send");

        StringBuilder b = new StringBuilder(768);
        b.append("<link rel=\"stylesheet\" href=\"/__plugin/contact/static/contact.css\">")
                .append("<form class=\"rw-contact\" method=\"post\" action=\"").append(ContactEndpoint.SUBMIT_PATH)
                .append("\" enctype=\"application/x-www-form-urlencoded\">")
                // Where the submit handler redirects back to (this page); validated server-side as a local path.
                .append("<input type=\"hidden\" name=\"return\" value=\"").append(Html.escape(returnPath)).append("\">");
        if (error != null && !error.isBlank()) {
            b.append("<p class=\"rw-contact-error\">").append(Html.escape(error)).append("</p>");
        }
        b.append(field("text", "name", nameLbl))
                .append(field("email", "email", emailLbl))
                .append("<label class=\"rw-contact-label\">").append(Html.escape(messageLbl))
                .append("<textarea class=\"rw-contact-input\" name=\"message\" rows=\"5\" required></textarea></label>")
                // Honeypot: hidden from humans, left blank; a bot that fills it is dropped as spam.
                .append("<div class=\"rw-contact-hp\" aria-hidden=\"true\">")
                .append("<label>Website<input type=\"text\" name=\"website\" tabindex=\"-1\" autocomplete=\"off\"></label></div>");
        // Real CAPTCHA challenge (only when the site configured one — trusted markup from the platform seam).
        if (captchaHtml != null && !captchaHtml.isBlank()) {
            b.append("<div class=\"rw-contact-captcha\">").append(captchaHtml).append("</div>");
        }
        b.append("<button class=\"rw-contact-submit\" type=\"submit\">").append(Html.escape(submitLbl))
                .append("</button></form>");
        return b.toString();
    }

    private static String field(String type, String fieldName, String label) {
        return "<label class=\"rw-contact-label\">" + Html.escape(label)
                + "<input class=\"rw-contact-input\" type=\"" + type + "\" name=\"" + fieldName + "\" required></label>";
    }
}
