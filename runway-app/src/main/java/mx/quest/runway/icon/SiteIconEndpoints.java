package mx.quest.runway.icon;

import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SurfacePosture;
import org.springframework.stereotype.Component;

/**
 * Serves each site its own icons at the conventional root URLs ({@code /favicon.svg}, {@code /favicon.ico},
 * {@code /apple-touch-icon.png}, {@code /icon-192.png}, {@code /icon-512.png}) — an ordinary
 * {@link EndpointContributor}, collected and dispatched exactly as a plugin's would be (the no-two-class rule).
 * A site that has not provided an icon simply {@code 404}s that slot; the content type comes from the
 * {@link SiteIcon} slot, never sniffed from the bytes.
 */
@Component
public class SiteIconEndpoints implements EndpointContributor {

    /** Icons are not indexed and are not sitemap entries, but they are safely cacheable. */
    private static final SurfacePosture POSTURE = new SurfacePosture(false, false, true);

    private final SiteIconService icons;

    public SiteIconEndpoints(SiteIconService icons) {
        this.icons = icons;
    }

    @Override
    public String key() {
        return "core.site-icons";
    }

    @Override
    public List<Route> routes() {
        return java.util.Arrays.stream(SiteIcon.values())
                .map(icon -> new Route("GET", icon.url(), Realm.TENANT, POSTURE))
                .toList();
    }

    @Override
    public RouteResponse handle(RouteRequest request) {
        return SiteIcon.fromUrl(request.path())
                .flatMap(icon -> icons.read(request.site().id(), icon)
                        .map(bytes -> new RouteResponse(200, headers(icon), bytes)))
                .orElseGet(RouteResponse::notFound);
    }

    /**
     * The icon's headers. The SVG icon is stored as uploaded, and an SVG opened directly is a document that can run
     * script on the site's origin, so it carries {@code Content-Security-Policy: sandbox}: no script, no same-origin
     * access. A browser loading it as an icon or an image applies no policy to it, so it still shows.
     */
    static Map<String, String> headers(SiteIcon icon) {
        if (SVG.equals(icon.contentType())) {
            return Map.of("Content-Type", icon.contentType(), "Cache-Control", CACHE, "Content-Security-Policy",
                    "sandbox");
        }
        return Map.of("Content-Type", icon.contentType(), "Cache-Control", CACHE);
    }

    private static final String SVG = "image/svg+xml";
    private static final String CACHE = "public, max-age=3600";
}
