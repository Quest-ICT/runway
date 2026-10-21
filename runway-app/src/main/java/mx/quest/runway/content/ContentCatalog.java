package mx.quest.runway.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.endpoint.EndpointContributorRegistry;
import mx.quest.runway.page.ContentUrls;
import mx.quest.runway.page.LocaleUrls;
import mx.quest.runway.plugin.ContributionGuard;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.storage.PathSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The kernel's <b>enumeration authority</b> over every content source (pages included): the one call the
 * listing consumers — the sitemap, llms.txt, URL maps, search indexing — make to see a site's whole published
 * content surface, already <b>kernel-re-filtered fail-closed</b>. Consumers never iterate the registry raw;
 * this class is where the source-discipline backstops live:
 *
 * <ul>
 *   <li><b>published-only</b> — an unpublished ref a source leaks is dropped;</li>
 *   <li><b>path-safe</b> — a ref whose default or localized path fails {@link PathSafety} is dropped;</li>
 *   <li><b>reserved-path drop</b> — a ref whose URL (in any of its locales) would collide with the reserved
 *       kernel surface ({@code /__…}, {@code /actuator}, {@code /error}, {@code /login}, {@code /logout},
 *       {@code /app/…}) or with an exact {@link mx.quest.runway.spi.EndpointContributor} route
 *       ({@code /sitemap.xml}, {@code /robots.txt}, …) is dropped — a listing must never advertise a URL the
 *       router will not serve as content;</li>
 *   <li><b>failure containment</b> — a throwing source contributes nothing (WARN) and never breaks the
 *       listing of the others.</li>
 * </ul>
 *
 * <p>The contributor registry arrives lazily ({@link ObjectProvider}) because endpoint contributors (the
 * sitemap itself) may depend on this catalog — resolving at call time breaks the construction cycle.
 *
 * <h2>Caching</h2>
 * {@link #enumerate} is cached per site (evicted by the page/content/site/plugin-config change events — the
 * kernel's first {@code ContentChangedEvent} listeners — with a 30s TTL backstop for changes no event
 * announces). {@link #enumerateContributed} and {@link #urlMap} stay uncached: sources self-cache their own
 * enumeration (the authoring-guide discipline), and the pages projection is skipped there entirely.
 */
@Component
public class ContentCatalog {

    private static final Logger log = LoggerFactory.getLogger(ContentCatalog.class);

    /** How long a cached enumeration may serve without an eviction event (the no-event safety net). */
    private static final long CACHE_TTL_MILLIS = 30_000L;

    /** One site's listing: the routable items and their SPI entries (built together, so they always agree). */
    private record CachedList(List<RoutableContent> items, List<CatalogEntry> entries, long at) {
    }

    /**
     * The <b>last-seen URL index</b>: per site, composite id → the URLs the item had in the
     * most recent uncached enumeration. Replaced whole on every enumeration and <b>never cleared by eviction</b>, so it
     * outlives the 30-second cache: when a page or an entry is deleted or moved, the SPI bridge snapshots its old
     * URLs from here before any listener evicts anything (that is how a deletion reaches IndexNow). Dropped only when
     * the site itself is deleted. Empty for a site not enumerated since boot (documented on {@code CatalogChange}).
     */
    private final java.util.concurrent.ConcurrentMap<String, Map<String, List<LocalizedUrl>>> lastSeen =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final ContentSourceRegistry registry;
    private final ObjectProvider<EndpointContributorRegistry> endpoints;
    /** Per-site cached enumeration — evicted by the four content-shaping events below, TTL as backstop. */
    private final java.util.concurrent.ConcurrentMap<String, CachedList> cache =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Per-site eviction epoch, bumped BEFORE each cache removal. An in-flight enumeration records the epoch
     * before loading and declines to cache its result if an eviction landed meanwhile — otherwise the
     * get→load→put sequence would re-install a pre-change list over the eviction with a fresh timestamp,
     * nullifying the event and (worse) letting a search engine that rebuilds on that very event cache an index
     * over the stale list for its full freshness window.
     */
    private final java.util.concurrent.ConcurrentMap<String, Long> evictionEpoch =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public ContentCatalog(ContentSourceRegistry registry, ObjectProvider<EndpointContributorRegistry> endpoints) {
        this.registry = registry;
        this.endpoints = endpoints;
    }

    /** Bare form for unit tests / fixtures — no contributor registry (its reserved drops don't apply). */
    public ContentCatalog(ContentSourceRegistry registry) {
        this.registry = registry;
        this.endpoints = new ObjectProvider<EndpointContributorRegistry>() {
            @Override public EndpointContributorRegistry getObject() { throw new IllegalStateException("none"); }
            @Override public EndpointContributorRegistry getIfAvailable() { return null; }
        };
    }

    /**
     * Every source's published content for {@code site} — pages and plugin content alike, in registration
     * order, re-filtered per the class contract. Metadata only ({@code enumerate} never reads bodies), so this
     * is safe on public, anonymous request paths.
     *
     * @param site the site being listed (never {@code null})
     * @return the sanitized refs, each with its owning source key (never {@code null}; may be empty)
     */
    public List<RoutableContent> enumerate(SiteView site) {
        return listing(site).items();
    }

    /**
     * The same listing as {@link #enumerate}, as the SPI's {@link CatalogEntry} values (what
     * {@code ContentCatalogView.entries} hands plugins) — built in the same pass and cached with it.
     *
     * @param site the site being listed (never {@code null})
     * @return the entries (never {@code null}; unmodifiable)
     */
    public List<CatalogEntry> entries(SiteView site) {
        return listing(site).entries();
    }

    /**
     * The URLs {@code compositeId} had when {@code siteId} was last enumerated (see {@link #lastSeen}): what a change
     * event reports as the item's previous URLs.
     *
     * @return the URLs, or an empty list when the item was not in the last listing (or the site was never listed)
     */
    public List<LocalizedUrl> lastSeenUrls(String siteId, String compositeId) {
        Map<String, List<LocalizedUrl>> index = lastSeen.get(siteId);
        if (index == null) {
            return List.of();
        }
        List<LocalizedUrl> urls = index.get(compositeId);
        return urls == null ? List.of() : urls;
    }

    private CachedList listing(SiteView site) {
        // Cached per site: a sitemap/llms.txt hit or a conflicts screen must not re-stat every page file and
        // re-read every source on each request. Evicted by the content-shaping events; TTL is the backstop
        // for changes no event announces (a hand-edited file). System time is fine here (a freshness window).
        CachedList cached = cache.get(site.id());
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_TTL_MILLIS) {
            return cached;
        }
        long epoch = evictionEpoch.getOrDefault(site.id(), 0L);
        List<RoutableContent> items = enumerateUncached(site);
        List<CatalogEntry> entries = new ArrayList<>(items.size());
        Map<String, List<LocalizedUrl>> index = new HashMap<>(items.size() * 2);
        for (RoutableContent item : items) {
            CatalogEntry entry = item.toCatalogEntry(site);
            entries.add(entry);
            index.put(entry.compositeId(), entry.urls());
        }
        CachedList fresh = new CachedList(items, List.copyOf(entries), System.currentTimeMillis());
        lastSeen.put(site.id(), Map.copyOf(index)); // replaced whole; outlives eviction on purpose
        // Cache only if no eviction landed while we loaded — see evictionEpoch. A skipped put is cheap
        // (the next caller reloads); a stale put would outlive the event that tried to kill it.
        if (evictionEpoch.getOrDefault(site.id(), 0L) == epoch) {
            cache.put(site.id(), fresh);
        }
        return fresh;
    }

    private List<RoutableContent> enumerateUncached(SiteView site) {
        EndpointContributorRegistry routes = endpoints.getIfAvailable();
        List<RoutableContent> out = new ArrayList<>();
        for (ContentSource source : registry.sources()) {
            List<ContentRef> refs = enumerate(source, site);
            if (refs == null) {
                continue;
            }
            for (ContentRef ref : refs) {
                if (ref != null && admissible(source, ref, site, routes)) {
                    out.add(new RoutableContent(source.key(), ref));
                }
            }
        }
        return List.copyOf(out);
    }

    // --- cache eviction: the four events that can change a site's content surface --------------------------
    //
    // Ordering across the stacked cache layers matters. A source self-caches its own enumeration (e.g. a
    // plugin's ref index), THIS catalog caches the union, and each search engine caches an index built FROM the
    // union. All three evict on the same events, dispatched synchronously on the publishing thread. If a
    // concurrent request rebuilt an engine's index mid-dispatch while an inner layer were still stale, it would
    // cache stale content. The kernel's convention: evict OUTERMOST-LAST — sources at HIGHEST_PRECEDENCE, the
    // page record caches at KernelEventOrder.STORES, this catalog at KernelEventOrder.CATALOG, engines at
    // LOWEST_PRECEDENCE (see the search plugins). The page caches MUST precede this catalog: the pages source
    // enumerates through them, so a catalog rebuilt between the two evictions would re-cache stale Page records
    // for the whole TTL (the defect KernelEventOrder fixed). Because the engine's own
    // invalidation is the last act of the dispatch, any index a concurrent query built during the dispatch is
    // discarded, so the next query rebuilds through fully-evicted inner layers.

    @EventListener
    @org.springframework.core.annotation.Order(mx.quest.runway.event.KernelEventOrder.CATALOG)
    public void onPageChanged(mx.quest.runway.page.PageChangedEvent event) {
        evict(event.siteId());
    }

    @EventListener
    @org.springframework.core.annotation.Order(mx.quest.runway.event.KernelEventOrder.CATALOG)
    public void onContentChanged(ContentChangedEvent event) {
        evict(event.siteId());
    }

    @EventListener
    @org.springframework.core.annotation.Order(mx.quest.runway.event.KernelEventOrder.CATALOG)
    public void onSiteChanged(mx.quest.runway.site.SiteChangedEvent event) {
        evict(event.siteId());
        if (event.kind() == mx.quest.runway.site.SiteChangedEvent.Kind.DELETED) {
            lastSeen.remove(event.siteId()); // the site is gone; a later site with this id starts unlisted
        }
    }

    @EventListener
    @org.springframework.core.annotation.Order(mx.quest.runway.event.KernelEventOrder.CATALOG)
    public void onSitePluginConfigChanged(mx.quest.runway.plugin.SitePluginConfigChangedEvent event) {
        evict(event.siteId()); // any plugin's per-site config may reshape its source's output
    }

    /**
     * The catalog's eviction order: just after a self-caching source (which sits at {@code HIGHEST_PRECEDENCE})
     * and well before the engines (at {@code LOWEST_PRECEDENCE}), so the union is fresh before any consumer
     * rebuilds from it. Sources that self-cache MUST evict at a higher precedence than this — the authoring
     * guide states the convention.
     */
    public static final int CATALOG_EVICT_ORDER = mx.quest.runway.event.KernelEventOrder.CATALOG;

    /**
     * Forces the next {@link #enumerate} of {@code siteId} to reload — drops the cached listing and bumps the
     * eviction epoch (so an in-flight load cannot re-install the stale list). Called by the search engines'
     * manual-reindex hook: an operator presses "reindex" precisely for out-of-band changes that fire no event
     * (an SFTP-uploaded or hand-edited page file), so the shared catalog cache — which those changes never
     * evict — must be refreshed too, or the rebuild would read a listing up to the TTL out of date.
     *
     * @param siteId the site whose cached enumeration to drop (never {@code null})
     */
    public void evict(String siteId) {
        evictionEpoch.merge(siteId, 1L, Long::sum);
        cache.remove(siteId);
    }

    /**
     * As {@link #enumerate} but over the <b>contributed</b> (non-pages) sources only — the variant for
     * consumers that only care about plugin content (the visibility triple's claims/conflicts): the pages
     * projection is skipped <em>before</em> enumeration, so no per-page file stats are paid for a question
     * pages cannot answer.
     */
    public List<RoutableContent> enumerateContributed(SiteView site) {
        EndpointContributorRegistry routes = endpoints.getIfAvailable();
        List<RoutableContent> out = new ArrayList<>();
        for (ContentSource source : registry.sources()) {
            if (PagesContentSource.KEY.equals(source.key())) {
                continue;
            }
            List<ContentRef> refs = enumerate(source, site);
            if (refs == null) {
                continue;
            }
            for (ContentRef ref : refs) {
                if (ref != null && admissible(source, ref, site, routes)) {
                    out.add(new RoutableContent(source.key(), ref));
                }
            }
        }
        return out;
    }

    /**
     * By-id link targets for source content — the {@code ${urls['<sourceKey>:<id>']}} half of the render
     * model's URL map (pages keep their bare-slug keys via {@code PageRouting.urlMap}): each non-pages item's
     * locale-correct URL for {@code locale} (its URL there when available, else its primary's — the same
     * fallback pages use, so an internal link never 404s). The pages projection is skipped ENTIRELY — not even
     * enumerated — so a site with no content plugins pays nothing here.
     *
     * @param locale the locale the linking page is being rendered in
     * @return composite id → root-relative URL (never {@code null})
     */
    public Map<String, String> urlMap(SiteView site, Locale locale) {
        Locale siteDefault = site.defaultLocale();
        EndpointContributorRegistry routes = endpoints.getIfAvailable();
        Map<String, String> urls = new HashMap<>();
        for (ContentSource source : registry.sources()) {
            if (PagesContentSource.KEY.equals(source.key())) {
                continue; // pages are keyed by bare slug in PageRouting.urlMap — no second enumeration
            }
            List<ContentRef> refs = enumerate(source, site);
            if (refs == null) {
                continue;
            }
            for (ContentRef ref : refs) {
                if (ref == null || !admissible(source, ref, site, routes)) {
                    continue;
                }
                RoutableContent item = new RoutableContent(source.key(), ref);
                Locale target = item.hasLocale(locale, siteDefault) ? locale : item.primaryLocale(siteDefault);
                urls.put(item.compositeId(), LocaleUrls.urlFor(item, target, siteDefault));
            }
        }
        return urls;
    }

    /**
     * The inputs {@link #urlMap} reads for {@code site}: every non-pages source's enumeration, by source key in
     * registration order — exactly the lists {@code urlMap} iterates (a throwing source contributes an empty list
     * there and here alike). The page validator folds them (each list memoized by identity, so a source that hands out
     * its cached list costs nothing per request), so a content item's URL change moves the ETag of every page whose
     * {@code ${urls}} could link to it. Unfiltered on purpose: a superset of what the map keeps is a correct fold, and
     * re-checking admissibility here would log every inadmissible ref twice per request.
     *
     * @return source key → that source's enumeration (never {@code null}; empty when there are no content plugins)
     */
    public Map<String, List<ContentRef>> urlMapInputs(SiteView site) {
        Map<String, List<ContentRef>> inputs = new java.util.LinkedHashMap<>();
        for (ContentSource source : registry.sources()) {
            if (!PagesContentSource.KEY.equals(source.key())) {
                inputs.put(source.key(), enumerate(source, site));
            }
        }
        return inputs;
    }

    /**
     * The item's raw indexable material for {@code locale} — the search integration's one entry point:
     * capability-gated (a source that never opted into {@code content.indexable-text} is never called) and
     * failure-contained (a throwing source indexes as metadata-only, never breaks an index build). Engines
     * strip markup themselves; this returns material "as authored".
     */
    public String indexableText(SiteView site, RoutableContent item, java.util.Locale locale) {
        return registry.byKey(item.sourceKey())
                .filter(source -> source.capabilities().has(ContentSource.CAP_INDEXABLE_TEXT))
                .map(source -> {
                    String text = registry.guard().call(source, ContributionGuard.Axis.CONTENT_SOURCE,
                            source.key(), () -> source.indexableText(site, item.ref().id(), locale), () -> "");
                    return text == null ? "" : text;
                })
                .orElse("");
    }

    /**
     * One source's enumeration under the containment policy: a source that throws contributes nothing (logged with its
     * stack once per minute, counted against its plugin) and never breaks the listing of the others. A
     * {@code null} answer is "nothing" too.
     */
    private List<ContentRef> enumerate(ContentSource source, SiteView site) {
        List<ContentRef> refs = registry.guard().call(source, ContributionGuard.Axis.CONTENT_SOURCE, source.key(),
                () -> source.enumerate(site), List::of);
        return refs == null ? List.of() : refs;
    }

    /** The fail-closed listing re-check; a {@code false} means dropped (already WARNed when noteworthy). */
    private boolean admissible(ContentSource source, ContentRef ref, SiteView site,
                               EndpointContributorRegistry routes) {
        if (!ref.published()) {
            log.warn("content source '{}' enumerated an UNPUBLISHED ref '{}' (site '{}') — dropped",
                    source.key(), ref.id(), site.id());
            return false;
        }
        if (ref.id().indexOf(':') >= 0) {
            log.warn("content source '{}' ref id '{}' contains ':' — dropped (the composite <sourceKey>:<id> "
                    + "identity must stay unambiguous)", source.key(), ref.id());
            return false;
        }
        RoutableContent content = new RoutableContent(source.key(), ref);
        for (Locale locale : ContentUrls.availableLocales(content, site)) {
            String path = ref.pathFor(locale);
            if (!PathSafety.isSafePagePath(path)) {
                log.warn("content source '{}' ref '{}' has an unsafe path '{}' — dropped", source.key(),
                        ref.id(), path);
                return false;
            }
            if (reserved(path, routes)) {
                // Not a source bug per se (an operator CAN name a page /login) — but a listing must never
                // advertise a URL the reserved surface will serve instead. Info-level: expected occasionally.
                log.info("content source '{}' ref '{}' path '{}' collides with the reserved surface — "
                        + "not listed", source.key(), ref.id(), path);
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the host would route content at {@code path} at all — the same path rules a listing applies to every
     * item (a safe page path, not reserved, not an exact endpoint route), with no source consulted. What
     * {@code ContentCatalogView.servablePath} answers.
     */
    public boolean servablePath(String path) {
        return path != null && PathSafety.isSafePagePath(path) && !reserved(path, endpoints.getIfAvailable());
    }

    /**
     * Whether {@code path} belongs to the reserved kernel surface ({@link
     * mx.quest.runway.web.ReservedPaths#atEnumerationTime the single authority}) or an exact contributor
     * route (this catalog owns that registry lookup).
     */
    private static boolean reserved(String path, EndpointContributorRegistry routes) {
        if (mx.quest.runway.web.ReservedPaths.atEnumerationTime(path)) {
            return true;
        }
        return routes != null && routes.match("GET", path).isPresent();
    }
}
