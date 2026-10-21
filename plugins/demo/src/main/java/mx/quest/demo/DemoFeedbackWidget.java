package mx.quest.demo;

import java.util.Locale;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.WidgetView;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * {@code ContentWidget} — a public "leave a message" form at a {@code data-widget="demo-feedback/<instance>"} anchor.
 * It posts to the demo's <b>anonymous</b> route {@code POST /demo/feedback} ({@link DemoEndpoints}), which stores the
 * message as a note and answers {@code 303} back to this page with a {@code ?demo-feedback=<outcome>} flag; the widget
 * reads that flag from {@link WidgetView#query()} and shows the outcome (post/redirect/get, so a reload never
 * resubmits).
 *
 * <p>The form carries <b>no CSRF token</b> on purpose: it sits on a page every visitor shares (and caches), and an
 * anonymous visitor has no session. That is exactly the case the anonymous-route flag exists for — see
 * {@link DemoEndpoints}. Its abuse guards are a honeypot field (hidden from people, filled by naive bots), a length
 * cap and a per-address rate limit on the server.
 *
 * <p>A widget stays a pure function of its view: it only <i>reads</i> the query; storing the message is the
 * endpoint's job. All text comes from {@link Messages#site} in the visitor's locale and is escaped here.
 */
public class DemoFeedbackWidget implements ContentWidget {

    /** The query flag the endpoint redirects back with. */
    static final String OUTCOME_PARAM = "demo-feedback";

    private final PluginSettings settings;
    private final Messages messages;

    public DemoFeedbackWidget(PluginSettings settings, Messages messages) {
        this.settings = settings;
        this.messages = messages;
    }

    @Override
    public String key() {
        return "demo-feedback";
    }

    @Override
    public RenderFragment render(WidgetView view) {
        String siteId = view.site().id();
        if (!settings.site(siteId).bool("feedback.enabled", true)) {
            return RenderFragment.empty();
        }
        Locale locale = view.locale();
        String fieldId = "rw-demo-feedback-" + view.instanceId().replaceAll("[^A-Za-z0-9_-]", "-");
        String back = view.path().isBlank() ? "/" : view.path();
        StringBuilder html = new StringBuilder()
                .append("<link rel=\"stylesheet\" href=\"/__plugin/runway-demo/static/css/demo.css\">")
                .append("<div class=\"rw-demo-feedback\">");
        String outcome = outcomeText(siteId, locale, view.query().get(OUTCOME_PARAM));
        if (!outcome.isEmpty()) {
            html.append("<p class=\"rw-demo-notice rw-demo-notice-info\" role=\"status\">")
                    .append(Html.escape(outcome)).append("</p>");
        }
        html.append("<form method=\"post\" action=\"").append(DemoEndpoints.FEEDBACK_PATH).append("\">")
                .append("<input type=\"hidden\" name=\"return\" value=\"").append(Html.escape(back)).append("\">")
                .append("<label for=\"").append(fieldId).append("\">")
                .append(Html.escape(text(siteId, locale, "runwaydemo.feedback.label", "Leave a message")))
                .append("</label>")
                .append("<textarea id=\"").append(fieldId).append("\" name=\"message\" rows=\"3\" maxlength=\"")
                .append(DemoNotesStore.MAX_TEXT).append("\" required></textarea>")
                // The honeypot: off-screen for people and assistive technology, tempting for a form-filling bot.
                .append("<div class=\"rw-demo-hp\" aria-hidden=\"true\"><label>Website <input type=\"text\" ")
                .append("name=\"website\" tabindex=\"-1\" autocomplete=\"off\"></label></div>")
                .append("<button type=\"submit\">")
                .append(Html.escape(text(siteId, locale, "runwaydemo.feedback.send", "Send")))
                .append("</button></form></div>");
        return RenderFragment.of(html.toString());
    }

    private String outcomeText(String siteId, Locale locale, String outcome) {
        if (outcome == null) {
            return "";
        }
        return switch (outcome) {
            case "thanks" -> text(siteId, locale, "runwaydemo.feedback.thanks", "Thank you — your message was kept.");
            case "invalid" -> text(siteId, locale, "runwaydemo.feedback.invalid", "Please write a message first.");
            case "limited" -> text(siteId, locale, "runwaydemo.feedback.limited",
                    "Too many messages from your address — please try again in a minute.");
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
