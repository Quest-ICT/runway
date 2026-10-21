package mx.quest.runway.site;

import java.util.Objects;

/**
 * Published whenever a site's lifecycle state changes, so the kernel's event-driven caches and the
 * {@link SiteResolver} host index can evict/refresh the affected entries. (The design's
 * "SiteRestoredEvent" is realized here as {@link Kind#RESTORED}.)
 *
 * <p>{@code templateId} is a <b>birth-only</b> field: on {@link Kind#CREATED} it names the seed template the
 * site is being born from (so {@link TenantSeeder} seeds from the operator's chosen template), and is
 * {@code null} for every other kind and for a create that takes the default template. It is carried on the
 * event rather than persisted on the {@link Site} record because it matters only at creation — a site owns its
 * own copy of the templates thereafter (self-containment), so there is nothing to remember afterward. The
 * {@linkplain #SiteChangedEvent(String, Kind) two-arg constructor} keeps every non-creation publisher unchanged.
 *
 * <p>{@code fromBackup} marks a {@link Kind#CREATED} that <b>imports a whole site from a backup</b> (as opposed
 * to a fresh create). It lets birth-time seeders that would otherwise inject platform defaults (e.g. the default
 * favicon) stand down, so an imported site is a <b>faithful copy</b> of its backup and renders identically —
 * never gaining a default the source did not have. {@code false} for every ordinary create.
 *
 * @param siteId     the affected site
 * @param kind       what happened
 * @param templateId on {@code CREATED}, the seed-template id to born the site from (nullable ⇒ default)
 * @param fromBackup on {@code CREATED}, whether the site is being imported wholesale from a backup
 */
public record SiteChangedEvent(String siteId, Kind kind, String templateId, boolean fromBackup) {

    /** The kind of lifecycle change. */
    public enum Kind {
        CREATED, UPDATED, SUSPENDED, RESUMED, DELETED, RESTORED
    }

    public SiteChangedEvent {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(kind, "kind");
    }

    /** A create/change with a seed template (or {@code null}); not a backup import. */
    public SiteChangedEvent(String siteId, Kind kind, String templateId) {
        this(siteId, kind, templateId, false);
    }

    /** A change with no associated seed template (every kind except a create with a chosen template). */
    public SiteChangedEvent(String siteId, Kind kind) {
        this(siteId, kind, null, false);
    }

    /** A {@code CREATED} for a site imported wholesale from a backup — birth-time default seeding stands down. */
    public static SiteChangedEvent createdFromBackup(String siteId) {
        return new SiteChangedEvent(siteId, Kind.CREATED, null, true);
    }

    /**
     * Whether this event replaces the site's files <b>wholesale</b> — a {@link Kind#DELETED} or
     * {@link Kind#RESTORED}. The single source of truth for the many event-driven caches that must drop a site's
     * entries only on a whole-folder rewrite (and NOT on a metadata-only suspend/resume, which they'd needlessly
     * re-read). Kept here so listeners don't each hand-copy the {@code kind == DELETED || kind == RESTORED} check.
     */
    public boolean isWholesaleReplacement() {
        return kind == Kind.DELETED || kind == Kind.RESTORED;
    }
}
