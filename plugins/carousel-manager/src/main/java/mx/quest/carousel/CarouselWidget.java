package mx.quest.carousel;

import java.util.ArrayList;
import java.util.List;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.SettingsView;
import mx.quest.runway.spi.model.WidgetView;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * The {@code carousel} content widget: a site manager drops
 * {@code <div id="hero" data-widget="carousel/hero"></div>} on a page and the host asks this widget to render an
 * image carousel from the site's per-tenant configuration. It emits self-contained markup that references the
 * plugin's own stylesheet and script ({@code /__plugin/carousel-manager/static/carousel.{css,js}}) — the script
 * is idempotent, so multiple carousels on a page are fine.
 *
 * <p><b>One slide set per site:</b> this plugin declares no {@code widgetSettings:} schema, so the host's
 * per-placement config ({@code WidgetView.config()}, edited at {@code /__admin/widgets}) stays empty for it and
 * every {@code carousel/*} anchor on a site renders the SAME configured slide set. Distinct carousels per
 * placement need this plugin to declare such a schema and read {@code config()}; the {@code instanceId} is
 * already carried through for that. With no slides configured the widget renders nothing
 * ({@link RenderFragment#empty()}).
 *
 * <p><b>Settings</b> come from {@link PluginSettings#site(String)}: the descriptor's {@code siteSettings:}
 * defaults overlaid by what the site saved. The code fallbacks below equal the descriptor defaults
 * ({@code intervalSeconds: 5}, {@code showCaptions: true}, {@code showControls: true}), so the widget renders the
 * same whether or not the host applied them; {@code intervalSeconds} is clamped to the declared {@code 0..60}.
 */
public class CarouselWidget implements ContentWidget {

    private static final String STATIC = "/__plugin/carousel-manager/static";

    private final PluginSettings settings;

    public CarouselWidget(PluginSettings settings) {
        this.settings = settings;
    }

    @Override
    public String key() {
        return "carousel";
    }

    @Override
    public RenderFragment render(WidgetView view) {
        SettingsView cfg = settings.site(view.site().id());
        List<Slide> slides = slides(cfg);
        if (slides.isEmpty()) {
            return RenderFragment.empty();
        }
        long interval = cfg.integer("intervalSeconds", 5, 0, 60);
        boolean captions = cfg.bool("showCaptions", true);
        boolean controls = cfg.bool("showControls", true);

        StringBuilder b = new StringBuilder(512);
        b.append("<link rel=\"stylesheet\" href=\"").append(STATIC).append("/carousel.css\">")
                .append("<div class=\"rw-carousel\" data-interval=\"").append(interval)
                .append("\" data-controls=\"").append(controls).append("\"><div class=\"rw-carousel-track\">");
        for (int i = 0; i < slides.size(); i++) {
            Slide s = slides.get(i);
            b.append("<figure class=\"rw-carousel-slide").append(i == 0 ? " is-active" : "").append("\">");
            boolean linked = !s.link().isBlank();
            if (linked) {
                b.append("<a class=\"rw-carousel-link\" href=\"").append(Html.escape(s.link())).append("\">");
            }
            b.append("<img class=\"rw-carousel-img\" src=\"").append(Html.escape(s.imageUrl()))
                    .append("\" alt=\"").append(Html.escape(s.caption())).append("\" loading=\"lazy\">");
            if (linked) {
                b.append("</a>");
            }
            if (captions && !s.caption().isBlank()) {
                b.append("<figcaption class=\"rw-carousel-caption\">").append(Html.escape(s.caption()))
                        .append("</figcaption>");
            }
            b.append("</figure>");
        }
        b.append("</div>");
        if (controls && slides.size() > 1) {
            b.append("<button type=\"button\" class=\"rw-carousel-prev\" aria-label=\"Previous\">‹</button>")
                    .append("<button type=\"button\" class=\"rw-carousel-next\" aria-label=\"Next\">›</button>");
        }
        b.append("</div><script src=\"").append(STATIC).append("/carousel.js\" defer></script>");
        return RenderFragment.of(b.toString());
    }

    /** Parses the per-tenant {@code slides} text (one {@code imageUrl | caption | link} per line). */
    static List<Slide> slides(SettingsView cfg) {
        List<Slide> out = new ArrayList<>();
        for (String line : cfg.string("slides", "").split("\\R")) {
            Slide slide = Slide.parse(line);
            if (slide != null) {
                out.add(slide);
            }
        }
        return out;
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("widget.carousel");
    }
}
