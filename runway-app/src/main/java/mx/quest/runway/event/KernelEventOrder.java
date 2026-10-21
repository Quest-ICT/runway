package mx.quest.runway.event;

import org.springframework.core.Ordered;

/**
 * The explicit dispatch order of every kernel listener on the four content-shaping host events —
 * {@code SiteChangedEvent}, {@code SitePluginConfigChangedEvent}, {@code PageChangedEvent} and
 * {@code ContentChangedEvent}. Every kernel {@code @EventListener} on one of those types carries an
 * {@code @Order} taken from this class; {@code KernelEventOrderTest} fails the build otherwise.
 *
 * <h2>Why the order is explicit</h2>
 * Spring dispatches an event to its listeners synchronously, sorted by {@code @Order}; listeners without one sit at
 * {@link Ordered#LOWEST_PRECEDENCE} and are tie-broken by bean <b>registration</b> order. Plugin beans are
 * registered before refresh, i.e. <i>before</i> every component-scanned kernel bean, so a plugin listener at the
 * default order ran <b>before</b> a kernel listener at the default order. That hid a live defect: the page record
 * caches ({@code PageStore}, {@code PageCacheInvalidationListener}) evicted at the default order, after
 * {@code ContentCatalog} ({@link #CATALOG}). A plugin consumer that re-enumerated the catalog inside the same
 * dispatch (IndexNow resolving URLs, a search engine rebuilding) therefore rebuilt the catalog from stale
 * {@code Page} records — and the catalog's eviction epoch then let that stale list stay cached for its 30-second
 * window. The bands below run the layers <b>innermost first</b>: seed the site, drop the record stores, drop
 * the resources built from them, drop the catalog built from those, then derived caches, then relay to peers.
 * Plugins receive these events only through the SPI event bridge, at {@link #PLUGIN_DISPATCH} — after every kernel
 * listener — so they see every kernel cache already evicted. (Until SPI 1.0.0 a plugin could also listen to the host
 * events directly; a plugin that links a host event type is refused from 1.0.0.)
 *
 * <p>Within one band, ties keep registration order; no kernel listener depends on another in the same band.
 */
public final class KernelEventOrder {

    private KernelEventOrder() {
    }

    /** Seeding on {@code CREATED} — the site's starter files and icons exist before anything reads the site. */
    public static final int SEED = Ordered.HIGHEST_PRECEDENCE + 100;

    /** Reserved for the SPI event bridge's snapshot of an item's previous URLs, before any eviction. */
    public static final int CAPTURE = Ordered.HIGHEST_PRECEDENCE + 200;

    /** Record stores and their caches: pages, users, roles, grants, per-site config, the host index. */
    public static final int STORES = Ordered.HIGHEST_PRECEDENCE + 500;

    /** Resources built from the records: per-site message bundles and parsed templates. */
    public static final int RESOURCES = Ordered.HIGHEST_PRECEDENCE + 700;

    /** The content catalog's per-site enumeration (formerly {@code ContentCatalog.CATALOG_EVICT_ORDER}). */
    public static final int CATALOG = Ordered.HIGHEST_PRECEDENCE + 1000;

    /** Caches derived from the catalog or the stores (readiness memos and similar). */
    public static final int DERIVED = Ordered.HIGHEST_PRECEDENCE + 1500;

    /** The cluster relay — peers are told only once this node's own caches are consistent. */
    public static final int RELAY = Ordered.HIGHEST_PRECEDENCE + 2000;

    /** Any other kernel listener (side effects that read, rather than cache, kernel state). */
    public static final int KERNEL_LATE = Ordered.LOWEST_PRECEDENCE - 1000;

    /** Reserved for the SPI event bridge's single plugin dispatch point: after every kernel listener. */
    public static final int PLUGIN_DISPATCH = Ordered.LOWEST_PRECEDENCE;
}
