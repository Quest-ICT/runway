package mx.quest.discover;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.Alternate;
import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;
import mx.quest.runway.spi.model.SurfacePosture;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * SEO+ — serves a per-site <b>{@code /sitemap.xml}</b> enumerating the site's whole published content surface —
 * kernel pages AND every content source's items (blog entries, any plugin content) through the host's
 * {@link ContentCatalogView} — each {@code <url>} carrying its {@code hreflang} alternates, {@code x-default}, and
 * (when the item knows it) a {@code <lastmod>}. Every URL is one the host computed ({@link CatalogEntry#urls()},
 * {@link CatalogEntry#xDefaultPath()}) with the SAME code the head-injection alternates use, so the
 * {@code <head>} and the sitemap provably agree about every item's URLs; this class only prefixes the origin.
 *
 * <p>The catalog is already kernel-re-filtered (published-only, path-safe, reserved paths dropped) and
 * metadata-only (no item's body is ever read serving this route — a sitemap request stays cheap no matter how
 * many sources are installed).
 *
 * <p>The route is tenant-scoped and {@link SurfacePosture#discoverable()}. It honors the per-tenant
 * {@code sitemap.enabled} flag — disabled ⇒ {@code 404}, so a tenant can opt out without uninstalling the
 * plugin.
 */
public class SitemapEndpoint implements EndpointContributor {

    private final ContentCatalogView catalog;
    private final PluginSettings settings;

    public SitemapEndpoint(ContentCatalogView catalog, PluginSettings settings) {
        this.catalog = catalog;
        this.settings = settings;
    }

    @Override
    public String key() {
        return "discoverability/sitemap";
    }

    @Override
    public List<Route> routes() {
        // Indexable + in-sitemap + CACHEABLE: this endpoint manages its own caching (Last-Modified +
        // no-cache revalidation below) — the dispatcher must not stamp no-store over it.
        SurfacePosture posture = new SurfacePosture(true, true, true);
        List<Route> routes = new java.util.ArrayList<>(MAX_SHARDS + 1);
        routes.add(new Route("GET", SITEMAP_PATH, Realm.TENANT, posture));
        // Shard routes are declared STATICALLY, one per possible shard, because the host matches routes by
        // exact path — there is no pattern form, deliberately (a pattern on a public tenant route is a
        // path-safety surface nobody needs). A shard beyond what a site actually has answers 404; nothing
        // links to it, so the only way to reach one is to type it.
        for (int shard = 1; shard <= MAX_SHARDS; shard++) {
            routes.add(new Route("GET", shardPath(shard), Realm.TENANT, posture));
        }
        return List.copyOf(routes);
    }

    /** The path of shard {@code n} (1-based), e.g. {@code /sitemap-3.xml}. */
    private static String shardPath(int n) {
        return "/sitemap-" + n + ".xml";
    }

    /** The shard number a path names, or {@code 0} when it is the index itself / not a shard path. */
    private static int shardNumber(String path) {
        if (path == null || !path.startsWith("/sitemap-") || !path.endsWith(".xml")) {
            return 0;
        }
        try {
            return Integer.parseInt(path.substring("/sitemap-".length(), path.length() - ".xml".length()));
        } catch (NumberFormatException notAShard) {
            return 0;
        }
    }

    @Override
    public RouteResponse handle(RouteRequest req) {
        Map<String, Object> cfg = settings.site(req.site().id()).values();
        if (!SiteConfig.bool(cfg, "sitemap.enabled", true)) {
            // The route posture is cacheable (so the 200/304 carry their own validators), which means the
            // dispatcher no longer stamps a fail-safe no-store — so this disabled-path 404 must forbid caching
            // itself, or a heuristically-cacheable 404 could outlive the tenant re-enabling the surface.
            return new RouteResponse(404, java.util.Map.of("Cache-Control", "no-store"), new byte[0]);
        }
        String base = PageUrls.base(req.site(), req);
        List<CatalogEntry> items = catalog.entries(req.site().id());

        // Conditional GET: crawlers revalidate constantly; when nothing changed since their copy, answer 304
        // without re-serializing the document. The index and every shard share the site's newest instant —
        // coarse (any edit revalidates all of them) but honest: a per-shard instant would claim a shard is
        // unchanged while an item moved into or out of it.
        java.time.Instant newest = CrawlConditional.lastModified(req.site().id(), items);
        if (CrawlConditional.notModified(req, newest)) {
            return CrawlConditional.notModifiedResponse(newest);
        }

        Listing listing = listing(items, base);
        int shard = shardNumber(req.path());
        if (shard > 0) {
            return shardResponse(listing, shard, newest, req.site().id());
        }
        if (listing.urls().size() <= SHARD_URLS) {
            // The ordinary case, and the shape every small site keeps: one plain <urlset>, no index, no extra
            // round trip. Splitting a 40-URL site across an index would cost a crawler two fetches to learn
            // nothing.
            return ok(urlsetDocument(listing.urls(), 0, listing.urls().size()), newest);
        }
        return ok(indexDocument(listing, base, newest), newest);
    }

    /** One sitemap entry: the URL to list, its alternates block (pre-rendered), and its {@code <lastmod>}. */
    private record Entry(String url, String alternates, String lastmod) {
    }

    /** The site's whole sitemap surface, and what was left out of it. */
    private record Listing(List<Entry> urls, int skippedNoindex, int skippedCanonical) {
    }

    /**
     * Every URL this site advertises, in a <b>stable, content-derived order</b>.
     *
     * <h2>Why it is sorted</h2>
     * {@link ContentCatalogView#entries} returns items grouped by source in an order it does not promise, which is stable
     * within a boot and says nothing across boots or across the nodes of a cluster. Shard membership is
     * derived from position in this list, so an unsorted list would let two nodes behind the same load
     * balancer serve <b>different content for the same shard number</b> — a crawler alternating between them
     * would see URLs appear and vanish with nothing in the response to explain it. Sorting by URL makes the
     * boundaries a function of the content alone.
     *
     * <p>Metadata only: no item's body is read here, so this stays cheap however many sources are installed.
     */
    private static Listing listing(List<CatalogEntry> items, String base) {
        List<Entry> entries = new java.util.ArrayList<>();
        int skippedNoindex = 0;
        int skippedCanonical = 0;

        for (CatalogEntry item : items) {
            // A sitemap is a set of RECOMMENDATIONS to index. Listing a URL the same site tells robots not to
            // index contradicts itself, so drop those here rather than in the host listing — the router, the search
            // indexer and llms.txt all legitimately need the full published set.
            if (item.ref().noindex()) {
                skippedNoindex++;
                continue;
            }
            // Likewise a page whose canonical points somewhere ELSE is, by its own declaration, not the
            // authoritative URL. Emitting it would advertise a self-declared non-canonical address. (A canonical
            // equal to the item's own URL is the normal self-referential case and stays.)
            if (pointsElsewhere(item, base)) {
                skippedCanonical++;
                continue;
            }
            // The host's URLs — the same computation the head-injection alternates use (provable agreement): one
            // per available locale, each a REAL routing address (never the canonical override).
            List<Alternate> alternates = item.urls().stream().map(url -> url.alternate(base)).toList();
            String xDefault = base + item.xDefaultPath();
            String lastmod = item.ref().updatedAt() == null ? null : item.ref().updatedAt().toString();

            StringBuilder links = new StringBuilder(128);
            for (Alternate alt : alternates) {
                links.append("    <xhtml:link rel=\"alternate\" hreflang=\"").append(alt.hreflang())
                        .append("\" href=\"").append(xml(alt.url())).append("\"/>\n");
            }
            links.append("    <xhtml:link rel=\"alternate\" hreflang=\"x-default\" href=\"")
                    .append(xml(xDefault)).append("\"/>\n");
            String block = links.toString();

            for (Alternate self : alternates) {
                entries.add(new Entry(self.url(), block, lastmod));
            }
        }
        entries.sort(java.util.Comparator.comparing(Entry::url));
        return new Listing(List.copyOf(entries), skippedNoindex, skippedCanonical);
    }

    /** One shard's {@code <urlset>}, or {@code 404} for a shard this site does not have. */
    private RouteResponse shardResponse(Listing listing, int shard, java.time.Instant newest, String siteId) {
        int total = listing.urls().size();
        if (total <= SHARD_URLS) {
            // Not sharded at all: the shard paths exist as routes on every site, so they must say "no" rather
            // than duplicate /sitemap.xml at a second address — two URLs serving one document is exactly the
            // duplicate-content shape the rest of this plugin works to avoid.
            return new RouteResponse(404, java.util.Map.of("Cache-Control", "no-store"), new byte[0]);
        }
        int from = (shard - 1) * SHARD_URLS;
        if (from >= total) {
            return new RouteResponse(404, java.util.Map.of("Cache-Control", "no-store"), new byte[0]);
        }
        int to = Math.min(from + SHARD_URLS, total);
        warnIfTruncated(listing, siteId);
        return ok(urlsetDocument(listing.urls(), from, to), newest);
    }

    /** The {@code <sitemapindex>} naming each shard this site actually has. */
    private byte[] indexDocument(Listing listing, String base, java.time.Instant newest) {
        int shards = Math.min(shardCount(listing.urls().size()), MAX_SHARDS);
        StringBuilder xml = new StringBuilder(256 + shards * 128);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (int shard = 1; shard <= shards; shard++) {
            xml.append("  <sitemap>\n")
                    .append("    <loc>").append(xml(base + shardPath(shard))).append("</loc>\n");
            if (newest != null) {
                xml.append("    <lastmod>").append(newest).append("</lastmod>\n");
            }
            xml.append("  </sitemap>\n");
        }
        xml.append("</sitemapindex>\n");
        return xml.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A {@code <urlset>} over {@code urls[from, to)}. */
    private static byte[] urlsetDocument(List<Entry> urls, int from, int to) {
        StringBuilder xml = new StringBuilder(512 + (to - from) * 256);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"")
                .append(" xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">\n");
        for (int i = from; i < to; i++) {
            Entry entry = urls.get(i);
            xml.append("  <url>\n")
                    .append("    <loc>").append(xml(entry.url())).append("</loc>\n");
            if (entry.lastmod() != null) {
                xml.append("    <lastmod>").append(entry.lastmod()).append("</lastmod>\n");
            }
            xml.append(entry.alternates()).append("  </url>\n");
        }
        xml.append("</urlset>\n");
        return xml.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** How many shards {@code total} URLs need. */
    private static int shardCount(int total) {
        return (total + SHARD_URLS - 1) / SHARD_URLS;
    }

    private static void warnIfTruncated(Listing listing, String siteId) {
        int needed = shardCount(listing.urls().size());
        if (needed > MAX_SHARDS) {
            // LOUD, and naming the tenant: a silently truncated sitemap is indistinguishable from a small site,
            // so an operator would have no way to learn that content stopped being advertised.
            log.warn("sitemap for site '{}' needs {} shards but only {} are served ({} URLs per shard) — "
                    + "{} URL(s) beyond {} are NOT advertised.", siteId, needed, MAX_SHARDS, SHARD_URLS,
                    listing.urls().size() - (long) MAX_SHARDS * SHARD_URLS, (long) MAX_SHARDS * SHARD_URLS);
        }
    }

    private static RouteResponse ok(byte[] body, java.time.Instant newest) {
        return new RouteResponse(200,
                CrawlConditional.cacheHeaders(newest, "application/xml; charset=utf-8"), body);
    }

    /** The route the index (or, for a small site, the single {@code <urlset>}) is served at. */
    private static final String SITEMAP_PATH = "/sitemap.xml";

    /**
     * URLs per shard, and how many shards are served — together the ceiling on what a site can advertise
     * ({@value #SHARD_URLS} &times; {@value #MAX_SHARDS}).
     *
     * <h2>Why 10,000 and not the spec's 50,000</h2>
     * sitemaps.org allows <b>50,000 URLs and 50 MB</b> per file, and a conformant consumer may reject a
     * document that exceeds either — so an unbounded generator does not degrade gracefully on a large tenant,
     * it loses the whole sitemap at once. But the spec cap is not the binding constraint here: this is a
     * <b>public, anonymous</b> route, and a 50,000-URL document with {@code hreflang} alternates is tens of
     * megabytes assembled in the heap, per concurrent request. A smaller shard keeps each response comfortably
     * inside both the spec and a sane memory envelope, and costs a crawler only extra round trips it makes
     * anyway.
     *
     * <p>{@value #MAX_SHARDS} shards is a real ceiling — {@value #SHARD_URLS} &times; {@value #MAX_SHARDS}
     * URLs — because the host matches routes by exact path, so each shard is a declared route rather than a
     * pattern. Well past any filesystem-backed tenant, four times the single-file limit it replaces, and it
     * still WARNs loudly rather than truncating in silence.
     */
    private static final int SHARD_URLS = 10_000;
    private static final int MAX_SHARDS = 20;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SitemapEndpoint.class);

    /**
     * Whether {@code item} declares a canonical URL pointing somewhere other than its own address — in which case
     * the item asks not to be treated as the authoritative URL and has no business in the sitemap.
     *
     * <p>The override is read from {@link LocalizedUrl#canonical()} (the host hands it over as given; equal to the
     * URL's own path when there is none). A root-relative override ({@code /about}) is resolved against
     * {@code base} before comparing, as the kernel does for the {@code <head>} canonical: it names an address on
     * this site, so {@code /about} on the {@code /about} page is the self-referential case. (Until 2.0.0 the raw
     * value was compared with the absolute URLs, so such a page was dropped from the sitemap.)
     *
     * <p>Compared against every locale's address and the {@code x-default}, because a localized variant is its own
     * address: a canonical matching any of the item's own URLs is the ordinary self-referential case.
     */
    private static boolean pointsElsewhere(CatalogEntry item, String base) {
        String override = null;
        for (LocalizedUrl url : item.urls()) {
            if (!url.canonical().equals(url.path())) {
                override = url.canonical();
                break;
            }
        }
        if (override == null || override.isBlank()) {
            return false;
        }
        String want = override.trim();
        if (want.startsWith("/")) {
            want = base + want;
        }
        for (LocalizedUrl url : item.urls()) {
            if (url.absolute(base).equals(want)) {
                return false;
            }
        }
        return !want.equals(base + item.xDefaultPath());
    }

    /** XML text/attribute escaping for URLs (chiefly {@code &} in query strings). */
    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of("endpoint.tenant", "endpoint.sitemap");
    }
}
