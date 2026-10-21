package mx.quest.runway.content;

import java.util.Objects;

/**
 * Published whenever a {@link mx.quest.runway.spi.ContentSource}'s content changes for a site — an entry
 * created, edited, deleted, published or unpublished, or the source's whole per-site corpus replaced. It is
 * the single freshness trigger for <b>server-side derived state</b>: routing/urlMap caches, the search index,
 * and any source-private lookup index all evict on it (publish → listeners evict), mirroring
 * {@link mx.quest.runway.page.PageChangedEvent}'s discipline for kernel pages — the write path never pokes
 * caches directly.
 *
 * <p><b>It does NOT affect HTTP caching.</b> A render's ETag/{@code Last-Modified} are computed from the
 * ref's metadata (notably {@link mx.quest.runway.spi.model.ContentRef#updatedAt()}) — a source whose body
 * output changed must bump the ref, or revalidating clients keep getting {@code 304}s; publishing this event
 * alone does not refresh them.
 *
 * <p><b>Every source change must reach it.</b> The kernel cannot see a plugin's files change; a plugin source calls
 * the SPI's {@link mx.quest.runway.spi.service.ContentChanges#changed}, which publishes this event (a plugin may not
 * link this host type — from SPI 1.0.0 one that does is refused). A source that forgets leaves stale
 * routing/search/sitemap output until an unrelated eviction happens to run. Kernel-internal: plugins observe changes
 * through {@link mx.quest.runway.spi.service.SiteEventListener}, never by listening to this type.
 *
 * <p>The event is published regardless of clustering; with clustering OFF it drives purely local eviction.
 * When clustering is ON, {@code cluster.ClusterEventRelay} fans it out to peer nodes over the Hazelcast topic
 * (envelope kind {@code CONTENT_CHANGED}) so every node's caches evict — the same cross-node invalidation the
 * kernel gives site, plugin-config and page events.
 *
 * <p><b>Synchronous.</b> Spring publishes events synchronously by default, so local listeners' evictions have
 * run by the time {@code publishEvent} returns.
 *
 * @param siteId    the affected tenancy (never {@code null})
 * @param sourceKey the {@link mx.quest.runway.spi.ContentSource#key()} whose content changed (never
 *                  {@code null})
 * @param id        the changed content's {@link mx.quest.runway.spi.model.ContentRef#id()}; {@code null}
 *                  means <b>the source's whole corpus for this site</b> (a wholesale drop — e.g. a prefix
 *                  reconfiguration or a restore)
 */
public record ContentChangedEvent(String siteId, String sourceKey, String id) {

    public ContentChangedEvent {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(sourceKey, "sourceKey");
    }
}
