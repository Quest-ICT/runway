package mx.quest.demodatabase;

import java.util.Locale;
import mx.quest.runway.spi.CaptchaService;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.WidgetView;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * The guestbook widget ({@code data-widget="guestbook/<instance>"}): the signing form and an empty list that the
 * plugin's script fills from {@code /guestbook/entries.json}.
 *
 * <p><b>Why the list is not rendered here.</b> A widget's output must depend only on what the page is rendered from
 * (the site, the locale, the query, the widget's settings): the kernel caches pages and answers {@code 304} while
 * their ETag holds. Rows read from the database here would hide new entries behind cached pages. So the page carries
 * a static shell and the entries arrive from an endpoint that is never cached.
 */
public class GuestbookWidget implements ContentWidget {

    static final String STATIC = "/__plugin/runway-demo-database/static";

    private final PluginSettings settings;
    private final Messages messages;
    private final CaptchaService captcha;

    public GuestbookWidget(PluginSettings settings, Messages messages, CaptchaService captcha) {
        this.settings = settings;
        this.messages = messages;
        this.captcha = captcha;
    }

    @Override
    public String key() {
        return "guestbook";
    }

    @Override
    public RenderFragment render(WidgetView view) {
        String siteId = view.site().id();
        if (!GuestbookEndpoints.enabled(settings, siteId)) {
            return RenderFragment.empty();
        }
        Locale locale = view.locale();
        String id = "rw-guestbook-" + view.instanceId().replaceAll("[^A-Za-z0-9_-]", "-");
        String back = view.path().isBlank() ? "/" : view.path();
        StringBuilder html = new StringBuilder()
                .append("<link rel=\"stylesheet\" href=\"").append(STATIC).append("/guestbook.css\">")
                .append("<section class=\"rw-guestbook\">");
        String outcome = outcome(siteId, locale, view.query().get(GuestbookEndpoints.OUTCOME_PARAM));
        if (!outcome.isEmpty()) {
            html.append("<p class=\"rw-guestbook-notice\" role=\"status\">").append(Html.escape(outcome))
                    .append("</p>");
        }
        html.append("<form method=\"post\" action=\"").append(GuestbookEndpoints.SIGN_PATH).append("\">")
                .append("<input type=\"hidden\" name=\"return\" value=\"").append(Html.escape(back)).append("\">")
                .append("<label for=\"").append(id).append("-name\">")
                .append(Html.escape(text(siteId, locale, "guestbook.name", "Your name"))).append("</label>")
                .append("<input id=\"").append(id).append("-name\" name=\"name\" type=\"text\" maxlength=\"")
                .append(GuestbookStore.NAME_MAX).append("\" required>")
                .append("<label for=\"").append(id).append("-message\">")
                .append(Html.escape(text(siteId, locale, "guestbook.message", "Message"))).append("</label>")
                .append("<textarea id=\"").append(id).append("-message\" name=\"message\" rows=\"3\" maxlength=\"")
                .append(GuestbookStore.MESSAGE_MAX).append("\" required></textarea>")
                // The honeypot: off-screen for people and assistive technology, tempting for a form-filling bot.
                .append("<div class=\"rw-guestbook-hp\" aria-hidden=\"true\"><label>Website <input type=\"text\" ")
                .append("name=\"website\" tabindex=\"-1\" autocomplete=\"off\"></label></div>")
                // The site's CAPTCHA challenge when it has one: trusted markup from the platform, empty otherwise.
                .append(captcha.widgetForSite(siteId).html())
                .append("<button type=\"submit\">")
                .append(Html.escape(text(siteId, locale, "guestbook.sign", "Sign the guestbook")))
                .append("</button></form>")
                .append("<ol class=\"rw-guestbook-list\" data-src=\"").append(GuestbookEndpoints.ENTRIES_PATH)
                .append("\" data-empty=\"").append(Html.escape(text(siteId, locale, "guestbook.empty",
                        "No entries yet. Be the first!")))
                .append("\" data-unavailable=\"").append(Html.escape(text(siteId, locale, "guestbook.unavailable",
                        "This example needs the database, which is not available right now.")))
                .append("\" data-more=\"").append(Html.escape(text(siteId, locale, "guestbook.more", "Older entries")))
                .append("\" data-lang=\"").append(Html.escape(locale.toLanguageTag())).append("\"></ol>")
                .append("</section><script src=\"").append(STATIC).append("/guestbook.js\" defer></script>");
        return RenderFragment.of(html.toString());
    }

    private String outcome(String siteId, Locale locale, String outcome) {
        if (outcome == null) {
            return "";
        }
        return switch (outcome) {
            case "thanks" -> text(siteId, locale, "guestbook.thanks", "Thank you for signing the guestbook.");
            case "invalid" -> text(siteId, locale, "guestbook.invalid",
                    "Please give your name and a message (up to 80 and 1000 characters).");
            case "limited" -> text(siteId, locale, "guestbook.limited",
                    "Too many entries from your address — please try again in a minute.");
            case "captcha" -> text(siteId, locale, "guestbook.captcha", "Please complete the challenge and try again.");
            case "unavailable" -> text(siteId, locale, "guestbook.unavailable",
                    "This example needs the database, which is not available right now.");
            default -> "";
        };
    }

    private String text(String siteId, Locale locale, String code, String fallback) {
        return messages.site(siteId, locale, code, fallback);
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("widget.form");
    }
}
