package mx.quest.demo;

import java.util.Set;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.SettingsView;
import mx.quest.runway.spi.model.WidgetView;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * {@code ContentWidget} — renders a styled callout wherever a tenant page carries the anchor
 * {@code <div id="..." data-widget="demo-notice/<instance>">} (the {@code id} AND {@code data-widget} on the same
 * element). {@link #key()} {@code "demo-notice"} is the {@code <type>} half of that anchor.
 *
 * <p>Reads the per-site {@code notice.*} settings through {@link PluginSettings}: disabled →
 * {@link RenderFragment#empty()}; {@code notice.style} picks the CSS class; {@code notice.max-chars} truncates (read
 * with the clamped accessor, so a hand-edited out-of-range value can never break rendering). A chrome-scoped
 * instance ({@link WidgetView#isChrome()}) shows the app-level {@code banner.text} instead.
 *
 * <p><b>{@code Messages.site}</b> is how visitor-facing text is localized: it resolves against the site's own bundle
 * first, then every plugin's {@code i18n/} bundle, in the visitor's locale ({@code view.locale()}), and formats the
 * arguments. Pure; never throws; owns its HTML escaping (the host splices the fragment unescaped).
 */
public class DemoNoticeWidget implements ContentWidget {

    private static final Set<String> STYLES = Set.of("info", "warning", "danger");

    private final DemoAppProps props;
    private final PluginSettings settings;
    private final Messages messages;

    public DemoNoticeWidget(DemoAppProps props, PluginSettings settings, Messages messages) {
        this.props = props;
        this.settings = settings;
        this.messages = messages;
    }

    @Override
    public String key() {
        return "demo-notice";
    }

    @Override
    public RenderFragment render(WidgetView view) {
        SettingsView site = settings.site(view.site().id());
        if (!site.bool("notice.enabled", true)) {
            return RenderFragment.empty();
        }
        String style = site.string("notice.style", "info");
        if (!STYLES.contains(style)) {
            style = "info";
        }
        int maxChars = (int) site.integer("notice.max-chars", 280, 1, 2000);

        String body;
        if (view.isChrome() && props.getBanner().isEnabled() && !props.getBanner().getText().isBlank()) {
            body = props.getBanner().getText();
        } else {
            body = messages.site(view.site().id(), view.locale(), "runwaydemo.notice.body",
                    "This is the runway-demo notice widget (instance \"{0}\") on site \"{1}\".",
                    view.instanceId(), view.site().displayName());
        }
        if (body.length() > maxChars) {
            body = body.substring(0, maxChars) + "…";
        }
        return RenderFragment.of("<div class=\"rw-demo-notice rw-demo-notice-" + style + "\" role=\"note\">"
                + Html.escape(body) + "</div>");
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("widget.configurable");
    }
}
