package mx.quest.contact;

import java.util.Locale;
import mx.quest.runway.spi.CaptchaService;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.SettingsView;
import mx.quest.runway.spi.model.WidgetView;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * The {@code contact} content widget: a site manager drops
 * {@code <div id="contact" data-widget="contact/form"></div>} on a page and the host renders the contact form
 * inline (it POSTs to the {@link ContactEndpoint}). Honors the per-site {@code enabled} setting (disabled ⇒
 * {@link RenderFragment#empty()}). The markup is the shared {@link ContactForm}; public text comes from
 * {@link Messages#site} in the visitor's locale.
 */
public class ContactWidget implements ContentWidget {

    private final PluginSettings settings;
    private final Messages messages;
    private final CaptchaService captcha;

    public ContactWidget(PluginSettings settings, Messages messages, CaptchaService captcha) {
        this.settings = settings;
        this.messages = messages;
        this.captcha = captcha;
    }

    @Override
    public String key() {
        return "contact";
    }

    @Override
    public RenderFragment render(WidgetView view) {
        String siteId = view.site().id();
        SettingsView config = settings.site(siteId);
        if (!config.bool("enabled", true)) {
            return RenderFragment.empty();
        }
        Locale locale = view.locale();
        // The submit handler redirects back here with ?contact=sent|invalid|captcha; the widget re-renders the
        // outcome from that flag. A pure read of the request — the store/send side effects live in the endpoint.
        String outcome = view.query().get("contact");
        if ("sent".equals(outcome) || "queued".equals(outcome)) {
            String success = config.string("successMessage",
                    messages.site(siteId, locale, "contact.success", "Thanks — your message has been sent."));
            String html = "<p class=\"rw-contact-success\">" + Html.escape(success.strip()) + "</p>";
            if ("queued".equals(outcome)) {
                // Stored, but the site's relay failed: say so quietly, so the visitor does not send it again.
                html += "<p class=\"rw-contact-note\">" + Html.escape(messages.site(siteId, locale,
                        "contact.note.stored", "Your message was received; email delivery is delayed.")) + "</p>";
            }
            return RenderFragment.of(html);
        }
        String error = null;
        if ("invalid".equals(outcome)) {
            error = messages.site(siteId, locale, "contact.error.invalid", "Please enter your email and a message.");
        } else if ("captcha".equals(outcome)) {
            error = messages.site(siteId, locale, "contact.error.captcha",
                    "Please complete the challenge and try again.");
        }
        String captchaHtml = captcha.widgetForSite(siteId).html();
        // Land the visitor back on THIS page after submit: the request path as served (locale prefix and all),
        // from the view — NEVER built from the slug, which for a content source's page (a blog entry rendering
        // this widget in the master chrome) is a composite identity like "blog:my-post", not a path. The slug
        // form remains only as the legacy fallback for a host that predates WidgetView.path.
        String returnPath = view.path().isEmpty() ? "/" + view.page().slug() : view.path();
        return RenderFragment.of(ContactForm.render(messages, siteId, locale, error, captchaHtml, returnPath));
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("widget.contact");
    }
}
