package mx.quest.runway.spi.model;

import java.util.Objects;

/**
 * A site's lifecycle changed — what {@link mx.quest.runway.spi.service.SiteEventListener#onSiteLifecycle} receives.
 * Immutable.
 *
 * @param siteId     the site (never {@code null})
 * @param kind       what happened (never {@code null}). May gain constants in a MINOR release: a {@code switch} over
 *                   it needs a {@code default} branch
 * @param fromBackup on {@link Kind#CREATED}: the site was imported wholesale from a backup (it is a faithful copy;
 *                   a plugin should not seed defaults into it). {@code false} otherwise
 * @param local      {@code true} on the node where the change happened; {@code false} when this node replays a
 *                   peer's change in a cluster. A listener with an external side effect acts only on local events
 * @since SPI 0.6.0
 */
public record SiteLifecycleEvent(String siteId, Kind kind, boolean fromBackup, boolean local) {

    /** The kind of lifecycle change. */
    public enum Kind {
        /** A new site (fresh, or imported from a backup — see {@code fromBackup}). */
        CREATED,
        /** The site's record changed (name, locales, hosts, editorial level, maintenance), or it was reloaded. */
        UPDATED,
        /** An operator suspended the site. */
        SUSPENDED,
        /** An operator resumed the site. */
        RESUMED,
        /** The site and its whole folder were deleted. */
        DELETED,
        /** The site's whole folder was replaced from a backup. */
        RESTORED
    }

    public SiteLifecycleEvent {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * Whether the site's files were replaced or removed as a whole ({@link Kind#DELETED} or {@link Kind#RESTORED}):
     * every per-site state a plugin derived from its files is now wrong and must be dropped.
     */
    public boolean wholesale() {
        return kind == Kind.DELETED || kind == Kind.RESTORED;
    }
}
