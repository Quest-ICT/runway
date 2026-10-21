package mx.quest.runway.icon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SurfacePosture;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Serves each site its Web App Manifest at {@code /manifest.webmanifest} — an ordinary
 * {@link EndpointContributor}. The manifest's declared fields come from {@link SiteManifestService}; its
 * {@code icons} array is <b>derived at serve time</b> from the icon slots the site actually provides, so it
 * never advertises a missing asset. A site with neither manifest fields nor any manifest-eligible icon gets a
 * {@code 404} (there is nothing to manifest).
 */
@Component
public class WebManifestEndpoint implements EndpointContributor {

    private static final SurfacePosture POSTURE = new SurfacePosture(false, false, true);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SiteManifestService manifests;
    private final SiteIconService icons;

    public WebManifestEndpoint(SiteManifestService manifests, SiteIconService icons) {
        this.manifests = manifests;
        this.icons = icons;
    }

    @Override
    public String key() {
        return "core.web-manifest";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route("GET", "/manifest.webmanifest", Realm.TENANT, POSTURE));
    }

    @Override
    public RouteResponse handle(RouteRequest request) {
        String siteId = request.site().id();
        SiteManifest manifest = manifests.get(siteId);
        List<Map<String, String>> iconEntries = iconEntries(siteId);
        if (!manifest.hasAny() && iconEntries.isEmpty()) {
            return RouteResponse.notFound();
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        put(doc, "name", manifest.name());
        put(doc, "short_name", manifest.shortName());
        put(doc, "description", manifest.description());
        put(doc, "lang", manifest.lang());
        put(doc, "display", manifest.display());
        put(doc, "theme_color", manifest.themeColor());
        put(doc, "background_color", manifest.backgroundColor());
        if (!iconEntries.isEmpty()) {
            doc.put("icons", iconEntries);
        }
        return new RouteResponse(200,
                Map.of("Content-Type", "application/manifest+json; charset=utf-8", "Cache-Control", "public, max-age=3600"),
                JSON.writeValueAsBytes(doc));
    }

    /** The manifest-eligible icons the site actually has (the PNG sizes and the SVG), in a stable order. */
    private List<Map<String, String>> iconEntries(String siteId) {
        List<Map<String, String>> entries = new ArrayList<>();
        for (SiteIcon icon : icons.presentSlots(siteId)) {
            String sizes = manifestSizes(icon);
            if (sizes != null) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("src", icon.url());
                entry.put("sizes", sizes);
                entry.put("type", icon.contentType());
                entries.add(entry);
            }
        }
        return entries;
    }

    private static String manifestSizes(SiteIcon icon) {
        return switch (icon) {
            case ICON_192 -> "192x192";
            case ICON_512 -> "512x512";
            case SVG_FAVICON -> "any";
            default -> null; // favicon.ico and apple-touch are head links, not manifest icons
        };
    }

    private static void put(Map<String, Object> doc, String key, String value) {
        if (value != null && !value.isBlank()) {
            doc.put(key, value.strip());
        }
    }
}
