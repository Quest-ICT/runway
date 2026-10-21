package mx.quest.runway.spi.service;

import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.SettingsChange;
import mx.quest.runway.spi.model.SiteLifecycleEvent;

/**
 * Reacts to site, settings and content changes. Implement it on any bean from the plugin's own jar and override the
 * callbacks you need; the host collects every {@code SiteEventListener} bean by type. It replaces listening to the
 * host's internal Spring events (which plugins could not rely on: their order against the kernel's caches was not a
 * contract).
 *
 * <h2>What is delivered</h2>
 * <ul>
 *   <li>{@link #onSiteLifecycle} — every site lifecycle event (created, updated, suspended, resumed, deleted,
 *       restored).</li>
 *   <li>{@link #onSettingsChanged} — a save of <b>this listener's own plugin's</b> per-site settings, and nothing
 *       else.</li>
 *   <li>{@link #onCatalogChanged} — anything that may reshape the content surface: a page write, a
 *       {@link ContentChanges#changed} call, any lifecycle event, and any plugin's per-site settings save.</li>
 * </ul>
 * For one event and one listener the callbacks run in that order: lifecycle, then settings, then catalog.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li><b>After the kernel.</b> When a callback runs, every kernel cache the change affects has been evicted:
 *       reading {@link ContentCatalogView}, {@link PluginSettings} or {@link Sites} from a callback sees the new
 *       state.</li>
 *   <li><b>Phases.</b> All {@link Phase#SOURCE} listeners run first (a content source dropping its own index), then
 *       the host evicts the content listing once more, then all {@link Phase#CONSUMER} listeners (a sitemap, a search
 *       index) — so a consumer that re-reads the listing never sees a source's stale state. Within a phase the order
 *       is plugin load order (a plugin after the plugins it {@code requires:}), then bean registration order:
 *       deterministic, but not part of the contract.</li>
 *   <li><b>No host lock is held.</b> A callback never runs while the host holds a write lock (a page's, a backup's).
 *       A change made under a lock is delivered after the lock is released, still before the write call returns —
 *       so "when the save returns, every listener has run" holds. Calling any service from a callback is therefore
 *       safe, including one that writes; the events of such a write are delivered after the current callback
 *       returns.</li>
 *   <li><b>Thread.</b> The thread that made the change: the console request that saved, or the cluster's topic
 *       thread for a change replayed from a peer ({@code local == false}).</li>
 *   <li><b>Isolation.</b> A callback that throws a {@link RuntimeException} or {@link LinkageError} is logged (the
 *       first time with the stack, then at most once a minute) and counted on the plugins screen; the change itself
 *       and every other listener are unaffected.</li>
 * </ul>
 *
 * <h2>Duty</h2>
 * Callbacks run on the writer's thread: <b>evict or enqueue, never do heavy work</b> — hand an index rebuild or a
 * network call to the plugin's own executor. {@link ContentChanges#changed} is refused from a callback (it would
 * start a re-entrant change storm).
 *
 * <h2>Not delivered</h2>
 * Changes nobody announces (a file edited by hand with no reload) and changes made before the platform finished
 * starting. The listing's time-to-live and the plugin's own time-to-live are the backstop.
 *
 * @since SPI 0.6.0
 */
public interface SiteEventListener {

    /** When in a dispatch a listener runs. May gain constants in a MINOR release. */
    enum Phase {
        /** Content sources dropping their own state, before anything re-reads the listing. */
        SOURCE,
        /** Everything else: consumers of the listing (sitemaps, search indexes, change notifiers). */
        CONSUMER
    }

    /**
     * This listener's phase; read once, when the platform starts. Default {@link Phase#CONSUMER}.
     *
     * @return the phase (a {@code null} answer counts as {@code CONSUMER})
     */
    default Phase phase() {
        return Phase.CONSUMER;
    }

    /**
     * A site's lifecycle changed.
     *
     * @param event what happened
     */
    default void onSiteLifecycle(SiteLifecycleEvent event) {
    }

    /**
     * This listener's own plugin's per-site settings were saved; {@link PluginSettings#site} already returns the new
     * values.
     *
     * @param change which site
     */
    default void onSettingsChanged(SettingsChange change) {
    }

    /**
     * Something that may reshape the site's content surface changed. See {@link CatalogChange} for what each cause
     * carries, including the item's URLs before and after the change.
     *
     * @param change what changed
     */
    default void onCatalogChanged(CatalogChange change) {
    }
}
