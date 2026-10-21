package mx.quest.discover;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SurfacePosture;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * GEO — serves a per-site <b>{@code /llms.txt}</b>, the emerging convention for exposing a site's content to
 * large-language-model / answer engines in a compact, machine-friendly form: an {@code H1} of the site name, a
 * one-line summary (the tenant's {@code geo.summary}), then the site's whole published content surface —
 * kernel pages AND every content source's items (blog entries, any plugin content) — as Markdown links with
 * their descriptions, in the site's default locale. Built from the host's {@link ContentCatalogView}
 * (kernel-re-filtered, metadata-only: no item's body is ever read serving this route).
 *
 * <p>Each item is linked at {@link CatalogEntry#pathFor(Locale) its address in the default locale}, or — for an
 * item that does not exist in the default locale — at its primary locale's real address. (Until 2.0.0 such an item
 * was linked at its unprefixed default path, which the site answers with a 404.)
 *
 * <p>Part of the opt-in GEO area: gated by the per-tenant {@code geo.enabled} flag — disabled ⇒ {@code 404}.
 */
public class LlmsTxtEndpoint implements EndpointContributor {

    private final ContentCatalogView catalog;
    private final PluginSettings settings;

    public LlmsTxtEndpoint(ContentCatalogView catalog, PluginSettings settings) {
        this.catalog = catalog;
        this.settings = settings;
    }

    @Override
    public String key() {
        return "discoverability/llms";
    }

    @Override
    public List<Route> routes() {
        // Cacheable: this endpoint manages its own caching (Last-Modified + no-cache revalidation).
        return List.of(new Route("GET", "/llms.txt", Realm.TENANT, new SurfacePosture(true, true, true)));
    }

    @Override
    public RouteResponse handle(RouteRequest req) {
        Map<String, Object> cfg = settings.site(req.site().id()).values();
        if (!SiteConfig.bool(cfg, "geo.enabled", false)) {
            // The route posture is cacheable (so the 200/304 carry their own validators), which means the
            // dispatcher no longer stamps a fail-safe no-store — so this disabled-path 404 must forbid caching
            // itself, or a heuristically-cacheable 404 could outlive the tenant re-enabling the surface.
            return new RouteResponse(404, java.util.Map.of("Cache-Control", "no-store"), new byte[0]);
        }
        String base = PageUrls.base(req.site(), req);
        Locale def = req.site().defaultLocale();

        List<CatalogEntry> items = catalog.entries(req.site().id());
        String summary = SiteConfig.str(cfg, "geo.summary", "");
        // The validator folds what this document prints besides the items: the H1 (the site name), the summary,
        // the locale every title and link is chosen in, and the site's base URL every link starts with — so editing
        // any of them advances Last-Modified and a revalidating crawler refetches (until 2.1.0 it kept the old copy).
        // The base URL, not the request origin: a host-less site reached under two names serves each its own links,
        // and each crawler's copy stays right for the address it fetched — folding the origin would only make the
        // validator advance on every alternate request.
        java.time.Instant newest = CrawlConditional.lastModified(req.site().id(), CrawlConditional.LLMS, items,
                java.util.Arrays.asList(req.site().displayName(), summary, def.toLanguageTag(),
                        req.site().baseUrl()));
        if (CrawlConditional.notModified(req, newest)) {
            return CrawlConditional.notModifiedResponse(newest);
        }

        StringBuilder out = new StringBuilder(512);
        out.append("# ").append(req.site().displayName()).append("\n\n");
        if (!summary.isBlank()) {
            out.append("> ").append(oneLine(summary)).append("\n\n");
        }
        out.append("## Pages\n\n");
        for (CatalogEntry item : items) {
            // Same rule as the sitemap: content the site tells robots not to index has no business being
            // advertised to answer engines either. llms.txt is a recommendation list, not a site dump.
            if (item.ref().noindex()) {
                continue;
            }
            String title = item.ref().titleFor(def);
            String label = mdLabel((title == null || title.isBlank()) ? item.ref().id() : title);
            String url = base + item.pathFor(def);
            out.append("- [").append(label).append("](").append(url).append(")");
            String desc = item.ref().descriptionFor(def);
            if (desc != null && !desc.isBlank()) {
                out.append(": ").append(oneLine(desc));
            }
            out.append('\n');
        }
        return new RouteResponse(200,
                CrawlConditional.cacheHeaders(newest, "text/plain; charset=utf-8"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String oneLine(String text) {
        return text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    /**
     * A markdown-safe link label: one line, with the link metacharacters mapped to parentheses-free forms so a
     * legitimate title like {@code FAQ [updated]} cannot terminate the {@code [label](url)} early and corrupt
     * the machine-parsed index.
     */
    private static String mdLabel(String text) {
        return oneLine(text).replace('[', '(').replace(']', ')');
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("endpoint.tenant", "endpoint.geo");
    }
}
