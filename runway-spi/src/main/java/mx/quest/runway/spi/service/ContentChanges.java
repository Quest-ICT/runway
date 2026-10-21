package mx.quest.runway.spi.service;

/**
 * Announces that the calling plugin's content changed, so every cache derived from it — the host's routing and
 * URL maps, the {@link ContentCatalogView} listing, a search index, a sitemap — is dropped, on every node of a
 * cluster. Every {@link mx.quest.runway.spi.ContentSource} calls it after each mutation of its content: the host
 * cannot see a plugin's files change.
 *
 * <h2>How a plugin gets one</h2>
 * Plugin-scoped, like {@link PluginSettings}: inject it into a class from the plugin's own jar and it is bound to
 * that plugin. The binding is what lets the host refuse an announcement for a source the plugin does not own.
 *
 * <h2>What a call does, in order</h2>
 * Synchronously, on the calling thread: the host's caches for the site are evicted; listeners that still use the
 * host's own change event run; the change is relayed to the other nodes of a cluster; then every
 * {@link SiteEventListener} receives {@link SiteEventListener#onCatalogChanged} (cause {@code CONTENT}). When
 * {@code changed} returns, every listener on this node has run.
 *
 * <p><b>It does not refresh HTTP validators.</b> A rendered item's {@code ETag} folds its {@code ContentRef}
 * metadata; a change to what {@code body()} returns must also change the ref (bump {@code updatedAt}).
 *
 * @since SPI 0.6.0
 */
public interface ContentChanges {

    /**
     * Call after mutating the calling plugin's content.
     *
     * @param siteId    the site whose content changed
     * @param sourceKey the {@link mx.quest.runway.spi.ContentSource#key()} whose content changed — one the calling
     *                  plugin contributes
     * @param id        the changed item's {@code ContentRef.id()}; nullable: {@code null} means the source's whole
     *                  corpus for the site (a re-import, a prefix change)
     * @throws IllegalArgumentException if {@code siteId} is unsafe, or {@code sourceKey} is not a content source
     *                                  contributed by the calling plugin
     * @throws IllegalStateException    if called from inside a {@link SiteEventListener} callback (a change announced
     *                                  while a change is being delivered would start a re-entrant storm)
     */
    void changed(String siteId, String sourceKey, String id);
}
