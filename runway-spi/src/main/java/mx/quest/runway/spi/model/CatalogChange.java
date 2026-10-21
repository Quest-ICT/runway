package mx.quest.runway.spi.model;

import java.util.List;
import java.util.Objects;

/**
 * Something that may reshape a site's content surface changed — what
 * {@link mx.quest.runway.spi.service.SiteEventListener#onCatalogChanged} receives. Immutable.
 *
 * <p>What each {@link Cause} carries:
 * <table>
 *   <caption>Components by cause</caption>
 *   <tr><th>cause</th><th>{@code sourceKey}</th><th>{@code id}</th><th>{@code pluginId}</th><th>URLs</th></tr>
 *   <tr><td>{@code PAGE}</td><td>{@code "pages"}</td><td>the page slug, or {@code null} for the whole site</td>
 *       <td>{@code null}</td><td>previous and current</td></tr>
 *   <tr><td>{@code CONTENT}</td><td>the publishing source's key</td><td>the item id, or {@code null} for the
 *       source's whole corpus</td><td>the plugin that owns the source ({@code null} for a kernel source)</td>
 *       <td>previous and current</td></tr>
 *   <tr><td>{@code SITE}</td><td>{@code null}</td><td>{@code null}</td><td>{@code null}</td><td>both empty</td></tr>
 *   <tr><td>{@code SETTINGS}</td><td>{@code null}</td><td>{@code null}</td><td>the plugin whose per-site settings
 *       were saved</td><td>both empty</td></tr>
 * </table>
 *
 * <p><b>The URLs.</b> {@code previousUrls} are the item's addresses as the host last listed them, captured before
 * any cache was evicted — so a <b>deleted</b> or <b>moved</b> item still reports where it used to be. They are empty
 * when the item is new, when the event names no item, or when the host has not listed the site since it started.
 * {@code currentUrls} are the item's addresses after the change, from a fresh listing; empty when the item is gone,
 * unpublished or names no item. A consumer that tells the outside world about changed URLs (an IndexNow submitter)
 * uses the union of both. <b>A {@code SOURCE}-phase listener always receives {@code currentUrls} empty</b>: the
 * current listing cannot be resolved before the sources themselves have dropped their state.
 *
 * @param siteId       the site (never {@code null})
 * @param cause        what happened (never {@code null}); may gain constants in a MINOR release
 * @param sourceKey    see the table (nullable)
 * @param id           see the table (nullable)
 * @param pluginId     see the table (nullable)
 * @param previousUrls the item's addresses before the change (never {@code null}; copied, unmodifiable)
 * @param currentUrls  the item's addresses after the change (never {@code null}; copied, unmodifiable)
 * @param local        {@code true} on the node where the change happened; {@code false} for a peer's replayed
 *                     change. A listener with an external side effect acts only on local changes, so a cluster of N
 *                     nodes does not repeat it N times
 * @since SPI 0.6.0
 */
public record CatalogChange(String siteId, Cause cause, String sourceKey, String id, String pluginId,
                            List<LocalizedUrl> previousUrls, List<LocalizedUrl> currentUrls, boolean local) {

    /** Why the content surface may have changed. */
    public enum Cause {
        /** A real page was created, saved, renamed, deleted, published or unpublished. */
        PAGE,
        /** A content source announced a change to its content. */
        CONTENT,
        /** A site lifecycle event (see {@link SiteLifecycleEvent}). */
        SITE,
        /** A plugin's per-site settings were saved (any plugin: settings can reshape a source's output). */
        SETTINGS
    }

    public CatalogChange {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(cause, "cause");
        previousUrls = (previousUrls == null) ? List.of() : List.copyOf(previousUrls);
        currentUrls = (currentUrls == null) ? List.of() : List.copyOf(currentUrls);
    }
}
