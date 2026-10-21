package mx.quest.runway.storage;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.function.Function;

/**
 * A bounded, in-memory cache over hot store reads. Invalidation is <b>event-driven</b> — the
 * kernel evicts affected entries on a file write/reload, a {@code SiteRestoredEvent}, or a plugin
 * config change — not primarily TTL-based; an optional coarse TTL may sit on top as a backstop.
 *
 * <p>Thin wrapper over Caffeine so the rest of the kernel depends on a small, intention-revealing
 * surface rather than the cache library directly.
 *
 * @param <K> cache key (use {@link PathSafety#compoundKey} for multi-part keys)
 * @param <V> cached value
 */
public final class StoreCache<K, V> {

    private final Cache<K, V> cache;

    /** A cache bounded to {@code maxEntries} (least-recently-used eviction beyond the bound). */
    public StoreCache(long maxEntries) {
        this.cache = Caffeine.newBuilder().maximumSize(maxEntries).build();
    }

    /**
     * Returns the cached value for {@code key}, loading and caching it via {@code loader} on a
     * miss. If {@code loader} returns {@code null}, nothing is cached and {@code null} is returned.
     */
    public V get(K key, Function<K, V> loader) {
        return cache.get(key, loader);
    }

    /** Returns the cached value for {@code key} without loading, or {@code null} if absent. */
    public V peek(K key) {
        return cache.getIfPresent(key);
    }

    /** Evicts {@code key} (call when the underlying store entry changes). */
    public void invalidate(K key) {
        cache.invalidate(key);
    }

    /** Evicts everything (call on a broad change such as a site restore). */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    /**
     * Evicts every entry whose key matches {@code predicate} — the <b>scoped</b> middle ground between
     * {@link #invalidate(Object)} (one known key) and {@link #invalidateAll()} (every tenant).
     *
     * <p>The motivating case is a cache keyed by {@link PathSafety#compoundKey} whose first part is a site
     * id: "drop everything belonging to this one site" cannot be expressed as a single key, because the
     * remaining parts (plugin ids, locales, slugs) are not enumerable at eviction time. Reaching for
     * {@code invalidateAll()} there is correct but wasteful — on a per-site event it discards every
     * <i>other</i> tenant's warm entries too, which on a many-tenant node turns one site's save into a
     * node-wide refill.
     *
     * <p>Prefix matching over a {@code compoundKey} is safe precisely because {@code compoundKey} rejects a
     * part containing the separator, so {@code "acme" + SEPARATOR} can only ever match keys whose first part
     * is exactly {@code acme} — never a site called {@code acme-staging}.
     *
     * <p>Iteration is over a weakly-consistent view: an entry written concurrently may or may not be seen.
     * That is acceptable for event-driven invalidation, where the write that prompted the event has already
     * happened before the event is published.
     */
    public void invalidateIf(java.util.function.Predicate<K> predicate) {
        cache.asMap().keySet().removeIf(predicate);
    }
}
