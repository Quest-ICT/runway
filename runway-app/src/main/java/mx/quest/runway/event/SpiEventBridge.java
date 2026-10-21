package mx.quest.runway.event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import mx.quest.runway.content.ContentCatalog;
import mx.quest.runway.content.ContentChangedEvent;
import mx.quest.runway.content.ContentSourceRegistry;
import mx.quest.runway.page.PageChangedEvent;
import mx.quest.runway.plugin.ContributionGuard;
import mx.quest.runway.plugin.PluginOwnership;
import mx.quest.runway.plugin.SitePluginConfigChangedEvent;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.SettingsChange;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.SiteEventListener;
import mx.quest.runway.spi.service.Sites;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Turns the kernel's four content-shaping host events into the SPI's {@link SiteEventListener} callbacks. The host
 * events stay internal (they are cluster-relayed and carry host types); plugins see only the
 * SPI's immutable records.
 *
 * <h2>Two listeners per event</h2>
 * <ul>
 *   <li>At {@link KernelEventOrder#CAPTURE} — before any kernel cache is evicted — the bridge snapshots the changed
 *       item's <b>previous URLs</b> from the catalog's last-seen index, so a deleted or moved item still
 *       reports where it was.</li>
 *   <li>At {@link KernelEventOrder#PLUGIN_DISPATCH} ({@code LOWEST_PRECEDENCE}) — after every kernel listener, so
 *       every kernel cache is already evicted — the bridge hands the dispatch to {@link HostWriteScope}: queued until
 *       the publishing thread leaves its outermost write scope (after the host released its lock, still before the
 *       write call returns), run at once outside any scope (a {@code ContentChanges} call, a cluster replay).</li>
 * </ul>
 *
 * <h2>One dispatch</h2>
 * <ol>
 *   <li>{@link SiteEventListener.Phase#SOURCE} listeners;</li>
 *   <li>{@code catalog.evict(site)} again — a request may have rebuilt the listing from a source's stale index between
 *       the kernel's eviction and step 1; the eviction epochs make this second eviction remove it;</li>
 *   <li>the item's <b>current URLs</b> from a fresh listing (events naming an item only);</li>
 *   <li>{@link SiteEventListener.Phase#CONSUMER} listeners.</li>
 * </ol>
 * For one listener and one event: {@code onSiteLifecycle}, then {@code onSettingsChanged} (only for the listener's
 * own plugin), then {@code onCatalogChanged}. Within a phase: plugin load order, then bean registration order; a
 * listener that belongs to no plugin (kernel or test code) runs after the plugins' and never receives settings
 * changes. Each callback is isolated under the containment policy ({@link ContributionGuard}, axis {@code listener}).
 *
 * <h2>Cost when unused</h2>
 * Listeners are collected once, after every singleton exists. With none registered (no plugin that listens is
 * installed) the bridge does nothing per event but one check. Events published before the platform finished starting
 * are not delivered.
 */
@Component
public class SpiEventBridge implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(SpiEventBridge.class);

    /** Bound on the previous-URL snapshots a thread may hold (an event whose dispatch never ran leaves one behind). */
    private static final int MAX_PENDING_SNAPSHOTS = 64;

    private static final ThreadLocal<int[]> CALLBACK_DEPTH = new ThreadLocal<>();
    private static final ThreadLocal<Map<Object, List<LocalizedUrl>>> SNAPSHOTS = new ThreadLocal<>();

    /** One collected listener with what the bridge needs to order and attribute it. */
    record Registered(SiteEventListener listener, String pluginId, SiteEventListener.Phase phase, String key) {
    }

    private final Supplier<List<SiteEventListener>> listenerSource;
    private final Supplier<ContentCatalog> catalog;
    private final Sites sites;
    private final ContributionGuard guard;
    private final Function<Object, Optional<String>> pluginOf;
    private final ToIntFunction<String> loadIndex;
    private final Supplier<ContentSourceRegistry> registry;

    private volatile List<Registered> sourcePhase;
    private volatile List<Registered> consumerPhase;

    @Autowired
    public SpiEventBridge(ObjectProvider<SiteEventListener> listeners, ObjectProvider<ContentCatalog> catalog,
                         Sites sites, ContributionGuard guard, PluginOwnership ownership,
                         ObjectProvider<ContentSourceRegistry> registry) {
        this(() -> listeners.stream().toList(), catalog::getObject, sites, guard,
                contribution -> ownership.pluginIdOf(contribution.getClass()), ownership::loadIndex,
                registry::getIfAvailable);
    }

    /**
     * The seams a unit test drives directly.
     *
     * @param listenerSource every listener, in bean registration order (read once, by {@link #collect})
     * @param pluginOf       which plugin's own jar a contribution's class came from (empty for kernel code)
     * @param loadIndex      a plugin's load position ({@link Integer#MAX_VALUE} when unknown)
     * @param registry       the content sources ({@code null} answer allowed: no source attribution)
     */
    public SpiEventBridge(Supplier<List<SiteEventListener>> listenerSource, Supplier<ContentCatalog> catalog,
                          Sites sites, ContributionGuard guard, Function<Object, Optional<String>> pluginOf,
                          ToIntFunction<String> loadIndex, Supplier<ContentSourceRegistry> registry) {
        this.listenerSource = Objects.requireNonNull(listenerSource, "listenerSource");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.sites = Objects.requireNonNull(sites, "sites");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.pluginOf = Objects.requireNonNull(pluginOf, "pluginOf");
        this.loadIndex = Objects.requireNonNull(loadIndex, "loadIndex");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Whether the current thread is inside a {@link SiteEventListener} callback — {@code ContentChanges} refuses to
     * announce a change from one (a re-entrant change storm).
     */
    public static boolean inCallback() {
        int[] depth = CALLBACK_DEPTH.get();
        return depth != null && depth[0] > 0;
    }

    @Override
    public void afterSingletonsInstantiated() {
        collect();
    }

    /** Collects and orders the listeners (once; called after every singleton exists). */
    public void collect() {
        List<Registered> all = new ArrayList<>();
        for (SiteEventListener listener : listenerSource.get()) {
            String plugin = pluginOf.apply(listener).orElse(null);
            String key = listener.getClass().getName();
            SiteEventListener.Phase phase = guard.call(listener, ContributionGuard.Axis.LISTENER, key + "#phase",
                    listener::phase, () -> SiteEventListener.Phase.CONSUMER);
            all.add(new Registered(listener, plugin, phase == null ? SiteEventListener.Phase.CONSUMER : phase, key));
        }
        // A stable sort: plugin load order, then (within a plugin, and for the kernel group last) registration order.
        all.sort(Comparator.comparingInt(r -> r.pluginId() == null ? Integer.MAX_VALUE : loadIndex.applyAsInt(
                r.pluginId())));
        sourcePhase = all.stream().filter(r -> r.phase() == SiteEventListener.Phase.SOURCE).toList();
        consumerPhase = all.stream().filter(r -> r.phase() != SiteEventListener.Phase.SOURCE).toList();
        if (!all.isEmpty()) {
            log.info("SPI event listeners: {} source-phase, {} consumer-phase", sourcePhase.size(),
                    consumerPhase.size());
        }
    }

    /** The collected listeners, SOURCE phase first (for the plugins screen and tests). */
    public List<Registered> listeners() {
        List<Registered> out = new ArrayList<>();
        if (sourcePhase != null) {
            out.addAll(sourcePhase);
            out.addAll(consumerPhase);
        }
        return List.copyOf(out);
    }

    private boolean active() {
        List<Registered> sources = sourcePhase;
        return sources != null && (!sources.isEmpty() || !consumerPhase.isEmpty());
    }

    // --- CAPTURE: the previous URLs, before any eviction ------------------------------------------------------

    @EventListener
    @Order(KernelEventOrder.CAPTURE)
    public void capturePage(PageChangedEvent event) {
        capture(event, event.siteId(), ContentCatalogView.PAGES_SOURCE, event.slug());
    }

    @EventListener
    @Order(KernelEventOrder.CAPTURE)
    public void captureContent(ContentChangedEvent event) {
        capture(event, event.siteId(), event.sourceKey(), event.id());
    }

    private void capture(Object event, String siteId, String sourceKey, String id) {
        if (id == null || !active()) {
            return;
        }
        Map<Object, List<LocalizedUrl>> snapshots = SNAPSHOTS.get();
        if (snapshots == null) {
            snapshots = new IdentityHashMap<>();
            SNAPSHOTS.set(snapshots);
        } else if (snapshots.size() >= MAX_PENDING_SNAPSHOTS) {
            snapshots.clear(); // dispatches that never ran (a kernel listener threw); never let them accumulate
        }
        List<LocalizedUrl> previous = List.of();
        try {
            previous = catalog.get().lastSeenUrls(siteId, sourceKey + ":" + id);
        } catch (RuntimeException e) {
            log.warn("could not snapshot the previous URLs of {}:{} on site '{}'", sourceKey, id, siteId, e);
        }
        snapshots.put(event, previous);
    }

    private static List<LocalizedUrl> takeSnapshot(Object event) {
        Map<Object, List<LocalizedUrl>> snapshots = SNAPSHOTS.get();
        if (snapshots == null) {
            return List.of();
        }
        List<LocalizedUrl> previous = snapshots.remove(event);
        if (snapshots.isEmpty()) {
            SNAPSHOTS.remove();
        }
        return previous == null ? List.of() : previous;
    }

    // --- PLUGIN_DISPATCH: after every kernel listener -----------------------------------------------------------

    @EventListener
    @Order(KernelEventOrder.PLUGIN_DISPATCH)
    public void onSiteChanged(SiteChangedEvent event) {
        if (!active()) {
            return;
        }
        boolean local = !ClusterReplay.active();
        SiteLifecycleEvent lifecycle = new SiteLifecycleEvent(event.siteId(),
                SiteLifecycleEvent.Kind.valueOf(event.kind().name()), event.fromBackup(), local);
        CatalogChange change = new CatalogChange(event.siteId(), CatalogChange.Cause.SITE, null, null, null, null,
                null, local);
        HostWriteScope.dispatch(() -> deliver(new Delivery(lifecycle, null, change)));
    }

    @EventListener
    @Order(KernelEventOrder.PLUGIN_DISPATCH)
    public void onSitePluginConfigChanged(SitePluginConfigChangedEvent event) {
        if (!active() || event.siteId() == null || event.pluginId() == null) {
            return;
        }
        boolean local = !ClusterReplay.active();
        SettingsChange settings = new SettingsChange(event.siteId(), event.pluginId(), local);
        CatalogChange change = new CatalogChange(event.siteId(), CatalogChange.Cause.SETTINGS, null, null,
                event.pluginId(), null, null, local);
        HostWriteScope.dispatch(() -> deliver(new Delivery(null, settings, change)));
    }

    @EventListener
    @Order(KernelEventOrder.PLUGIN_DISPATCH)
    public void onPageChanged(PageChangedEvent event) {
        List<LocalizedUrl> previous = takeSnapshot(event);
        if (!active()) {
            return;
        }
        CatalogChange change = new CatalogChange(event.siteId(), CatalogChange.Cause.PAGE,
                ContentCatalogView.PAGES_SOURCE, event.slug(), null, previous, null, !ClusterReplay.active());
        HostWriteScope.dispatch(() -> deliver(new Delivery(null, null, change)));
    }

    @EventListener
    @Order(KernelEventOrder.PLUGIN_DISPATCH)
    public void onContentChanged(ContentChangedEvent event) {
        List<LocalizedUrl> previous = takeSnapshot(event);
        if (!active()) {
            return;
        }
        CatalogChange change = new CatalogChange(event.siteId(), CatalogChange.Cause.CONTENT, event.sourceKey(),
                event.id(), pluginOfSource(event.sourceKey()), previous, null, !ClusterReplay.active());
        HostWriteScope.dispatch(() -> deliver(new Delivery(null, null, change)));
    }

    private String pluginOfSource(String sourceKey) {
        try {
            ContentSourceRegistry sources = registry.get();
            return sources == null ? null
                    : sources.byKey(sourceKey).flatMap(source -> pluginOf.apply(source)).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // --- delivery ---------------------------------------------------------------------------------------------

    /** What one dispatch hands each listener: the lifecycle and settings parts are present only for their causes. */
    private record Delivery(SiteLifecycleEvent lifecycle, SettingsChange settings, CatalogChange change) {
    }

    private void deliver(Delivery delivery) {
        String siteId = delivery.change().siteId();
        for (Registered registered : sourcePhase) {
            invoke(registered, delivery, delivery.change());
        }
        try {
            catalog.get().evict(siteId);
        } catch (RuntimeException e) {
            log.warn("could not evict the content listing of site '{}' between listener phases", siteId, e);
        }
        if (consumerPhase.isEmpty()) {
            return;
        }
        CatalogChange change = delivery.change();
        if (change.id() != null
                && (change.cause() == CatalogChange.Cause.PAGE || change.cause() == CatalogChange.Cause.CONTENT)) {
            change = new CatalogChange(change.siteId(), change.cause(), change.sourceKey(), change.id(),
                    change.pluginId(), change.previousUrls(), currentUrls(siteId, change.sourceKey(), change.id()),
                    change.local());
        }
        for (Registered registered : consumerPhase) {
            invoke(registered, delivery, change);
        }
    }

    /** The item's URLs in a fresh listing; empty when it is gone, unpublished, or the site is unknown. */
    private List<LocalizedUrl> currentUrls(String siteId, String sourceKey, String id) {
        try {
            Optional<SiteView> site = sites.find(siteId);
            if (site.isEmpty()) {
                return List.of();
            }
            String composite = sourceKey + ":" + id;
            for (CatalogEntry entry : catalog.get().entries(site.get())) {
                if (entry.compositeId().equals(composite)) {
                    return entry.urls();
                }
            }
        } catch (RuntimeException e) {
            log.warn("could not resolve the current URLs of {}:{} on site '{}'", sourceKey, id, siteId, e);
        }
        return List.of();
    }

    private void invoke(Registered registered, Delivery delivery, CatalogChange change) {
        SiteEventListener listener = registered.listener();
        if (delivery.lifecycle() != null) {
            callback(registered, "onSiteLifecycle", () -> listener.onSiteLifecycle(delivery.lifecycle()));
        }
        if (delivery.settings() != null && delivery.settings().pluginId().equals(registered.pluginId())) {
            callback(registered, "onSettingsChanged", () -> listener.onSettingsChanged(delivery.settings()));
        }
        callback(registered, "onCatalogChanged", () -> listener.onCatalogChanged(change));
    }

    private void callback(Registered registered, String method, Runnable call) {
        int[] depth = CALLBACK_DEPTH.get();
        if (depth == null) {
            depth = new int[1];
            CALLBACK_DEPTH.set(depth);
        }
        depth[0]++;
        try {
            guard.run(registered.listener(), ContributionGuard.Axis.LISTENER, registered.key() + "#" + method, call);
        } finally {
            if (--depth[0] == 0) {
                CALLBACK_DEPTH.remove();
            }
        }
    }
}
