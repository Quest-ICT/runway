package mx.quest.runway.page;

import java.util.Objects;

/**
 * Published whenever a tenant page's on-disk files change through {@link PageWriteService} — a save, create,
 * delete, rename, draft/publish flip, or a chrome re-seed. It is the <b>single trigger</b> for dropping that
 * page's stale cache entries: a local {@link PageCacheInvalidationListener} turns it into the exact eviction a
 * page edit needs — the {@link PageStore} record cache and the parsed-template cache — so there
 * is one eviction path (publish → listener evicts) rather than the write path poking the caches directly.
 *
 * <p>The event is published regardless of clustering; with clustering OFF it drives purely local eviction (the
 * correct single-node behaviour) and nothing is broadcast. When clustering is ON, the (separately gated)
 * {@code cluster.ClusterEventRelay} also listens for it and fans it out to peer nodes over the Hazelcast topic
 * so every node evicts — the same cross-node invalidation the kernel already gives {@code SiteChangedEvent} and
 * {@code SitePluginConfigChangedEvent}, extended to fine-grained page edits (which were previously local-only).
 *
 * <p><b>Synchronous.</b> Spring publishes events synchronously by default, so the listener's eviction has run
 * by the time {@code publishEvent} returns — the write path keeps its "evict before I return" guarantee exactly
 * as when it called the caches inline.
 *
 * @param siteId the affected tenancy
 * @param slug   the page whose files changed; a {@code null} slug means <b>the whole site's pages</b> (a
 *               wholesale drop), for a future site-wide page invalidation — single-page callers always pass a
 *               concrete slug
 */
public record PageChangedEvent(String siteId, String slug) {

    public PageChangedEvent {
        Objects.requireNonNull(siteId, "siteId");
    }
}
