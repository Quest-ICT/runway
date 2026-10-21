package mx.quest.discover;

import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SurfacePosture;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * Serves the site's IndexNow key file — the proof of ownership the protocol requires.
 *
 * <h2>Why a fixed path rather than {@code /<key>.txt}</h2>
 * IndexNow's usual pattern is a file named after the key itself. That cannot work here: the host matches
 * routes by exact path and builds the table at startup, while the key is per-site configuration that changes
 * at runtime. The spec's own {@code keyLocation} field exists for exactly this — a submission may name any URL
 * on the host as the key's location — so the file lives at a stable path and every submission points there.
 *
 * <h2>Not indexable, not in the sitemap, not cached</h2>
 * It is a verification artifact, not content: it should never appear in search results, and a cached copy
 * would keep answering with an old key after a tenant rotates it — which fails verification for as long as the
 * cache lives, in a way that looks like the engine's fault.
 */
public class IndexNowKeyEndpoint implements EndpointContributor {

    private final PluginSettings settings;
    private final DiscoverabilityProperties properties;

    public IndexNowKeyEndpoint(PluginSettings settings, DiscoverabilityProperties properties) {
        this.settings = settings;
        this.properties = properties;
    }

    @Override
    public String key() {
        return "discoverability/indexnow-key";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route("GET", IndexNowSubmitter.KEY_PATH, Realm.TENANT,
                new SurfacePosture(false, false, false)));
    }

    @Override
    public RouteResponse handle(RouteRequest req) {
        Map<String, Object> cfg = settings.site(req.site().id()).values();
        String key = SiteConfig.str(cfg, "indexnow.key", "").trim();

        // 404 unless the platform permits IndexNow AND this site turned it on AND a key is set. Serving a key
        // for a site that is not using the feature would publish a value that only exists to be verified.
        if (!properties.getIndexnow().isEnabled()
                || !SiteConfig.bool(cfg, "indexnow.enabled", false)
                || key.isEmpty()) {
            return new RouteResponse(404, Map.of("Cache-Control", "no-store"), new byte[0]);
        }
        return new RouteResponse(200,
                Map.of("Content-Type", "text/plain; charset=utf-8", "Cache-Control", "no-store"),
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("endpoint.tenant", "endpoint.indexnow-key");
    }
}
