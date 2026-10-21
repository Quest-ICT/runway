package mx.quest.demo;

import java.util.List;
import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.RunwaySpi;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.model.SettingsView;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * {@code HeadContributor} — splices markup into every page's {@code <head>} at the response edge, so it lands on every
 * page without any edit to a tenant's templates. Ordered {@code @Order(200)} in {@link DemoPluginConfig}, after the
 * kernel's own SEO ({@code @Order(100)}) and icon ({@code @Order(90)}) contributors.
 *
 * <p>Emits an escaped {@code <meta name="generator">} from the app-level {@code generator.label} setting, the plugin's
 * stylesheet while the per-site notice is enabled, and — only when the per-site {@code analytics.enabled} flag is on
 * and the app-level {@code analytics.write-key} secret is set — an inert analytics {@code <script>} (plain
 * {@code <script src>}: the kernel stamps the CSP nonce on it). Returns {@code ""}, never {@code null}, when it has
 * nothing to add.
 *
 * <p><b>{@code PluginSettings}</b> is how a plugin reads its own per-site settings: {@code site(siteId)} returns a
 * {@link SettingsView} with the {@code siteSettings:} defaults already applied and the saved values over them, so the
 * code fallback only matters for undeclared keys. It is bound to this plugin by the jar it was injected into.
 */
public class DemoAnalyticsHead implements HeadContributor {

    private final DemoAppProps props;
    private final PluginSettings settings;

    public DemoAnalyticsHead(DemoAppProps props, PluginSettings settings) {
        this.props = props;
        this.settings = settings;
    }

    @Override
    public String key() {
        return "runway-demo/analytics";
    }

    @Override
    public String render(HeadContext ctx) {
        SettingsView site = settings.site(ctx.site().id());
        StringBuilder out = new StringBuilder();
        // A provenance comment: the SPI this plugin was built against (a constant), and an input the host hands in.
        out.append("\n<!-- runway-demo ").append(Html.escape(RunwaySpi.VERSION))
                .append(ctx.noindex() ? " (noindex)" : "").append(" -->");
        out.append("\n<meta name=\"generator\" content=\"").append(Html.escape(props.getGenerator().getLabel()))
                .append("\">");
        if (site.bool("notice.enabled", true)) {
            // The plugin's own stylesheet, from its static-asset namespace, so the widgets are styled.
            out.append("\n<link rel=\"stylesheet\" href=\"/__plugin/runway-demo/static/css/demo.css\">");
        }
        if (site.bool("analytics.enabled", false) && props.getAnalytics().hasKey()) {
            // Inert placeholder — a real plugin would emit its vendor's snippet here. No key goes into the page.
            out.append("\n<script data-demo-analytics=\"1\" src=\"/__plugin/runway-demo/static/js/demo-analytics.js\"")
                    .append(" data-site=\"").append(Html.escape(ctx.site().id())).append("\" defer></script>");
        }
        return out.toString();
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("head.per-locale");
    }

    /**
     * This contributor's whole per-site input: its own settings file. Head markup is spliced in after a page's
     * {@code ETag} has been computed, so the host folds the files listed here into the validator — without it, a
     * settings change would alter the delivered page but not its {@code ETag}, and clients would keep the old head.
     */
    @Override
    public List<String> headInputs(String siteId) {
        return List.of("plugins/runway-demo.yml");
    }
}
