package mx.quest.demo;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.SettingsChange;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.SiteEventListener;
import mx.quest.runway.spi.service.Sites;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code SiteEventListener} in the default {@link Phase#CONSUMER CONSUMER} phase — what a plugin that reacts to
 * changes it does not own implements (a sitemap, a search index, a change notifier). It replaces listening to the
 * host's internal Spring events, whose order against the kernel's caches was never a contract.
 *
 * <p><b>When each callback is for:</b>
 * <ul>
 *   <li>{@link #onSettingsChanged} — a save of <b>this plugin's own</b> per-site settings (and nothing else);
 *       {@code PluginSettings.site()} already returns the new values. The demo writes an audit note, as 1.x did —
 *       but only when {@link SettingsChange#local()} is true: on a cluster every node receives the change (the
 *       others as a replay, {@code local == false}), and a side effect must happen once, not once per node.</li>
 *   <li>{@link #onCatalogChanged} — anything that may reshape a site's content surface: a page write ({@code PAGE}),
 *       a {@code ContentChanges} call ({@code CONTENT}), a lifecycle event ({@code SITE}) or any plugin's settings
 *       save ({@code SETTINGS}). A {@code PAGE} or {@code CONTENT} change carries the item's URLs <b>before</b>
 *       ({@code previousUrls}, so a deleted or moved item still names its old address) and <b>after</b>
 *       ({@code currentUrls}). The demo keeps the last {@value #KEEP} per site in memory and shows them on its
 *       console screen — an IndexNow-style notifier would submit the union of both lists.</li>
 *   <li>{@link #onSiteLifecycle} — created, updated, suspended, resumed, deleted, restored. On a <b>wholesale</b>
 *       event (deleted, restored) drop everything derived from the site's files; here, the recorded activity.</li>
 * </ul>
 * <b>Guarantees you can rely on:</b> every kernel cache the change affects is already evicted (reads see the new
 * state); no host lock is held (calling any service is safe); one throwing callback is logged and counted, never
 * propagated. <b>Duty:</b> callbacks run on the writer's thread — evict, record or enqueue; anything slow (a network
 * call, an index rebuild) belongs on your own executor. The audit note is one small, bounded file write.
 *
 * <p>It also shows {@link Sites}: the way code that knows a site only by id — a listener, a background task — gets
 * the site's {@link SiteView}, off-request.
 */
public class DemoActivity implements SiteEventListener {

    /** How many recent changes are kept per site. */
    static final int KEEP = 10;

    private static final Logger log = LoggerFactory.getLogger(DemoActivity.class);

    /**
     * One recorded change, as the console screen shows it.
     *
     * @param at           when this node received it
     * @param cause        {@code PAGE}, {@code CONTENT}, {@code SITE}, {@code SETTINGS} (lower case)
     * @param item         {@code <sourceKey>:<id>}, the plugin for a settings change, or {@code ""}
     * @param previousUrls the item's paths before the change
     * @param currentUrls  the item's paths after it
     * @param local        whether the change happened on this node
     */
    public record Entry(Instant at, String cause, String item, List<String> previousUrls, List<String> currentUrls,
                        boolean local) {
    }

    private final DemoNotesStore notes;
    private final Sites sites;
    private final ConcurrentMap<String, Deque<Entry>> recent = new ConcurrentHashMap<>();

    public DemoActivity(DemoNotesStore notes, Sites sites) {
        this.notes = notes;
        this.sites = sites;
    }

    @Override
    public void onSiteLifecycle(SiteLifecycleEvent event) {
        String name = sites.find(event.siteId()).map(SiteView::displayName).orElse("(gone)");
        log.info("runway-demo saw site '{}' ({}) {}{}", event.siteId(), name, event.kind(),
                event.fromBackup() ? " from a backup" : "");
        if (event.wholesale()) {
            recent.remove(event.siteId());
        }
    }

    @Override
    public void onSettingsChanged(SettingsChange change) {
        if (!change.local()) {
            return; // replayed from another node: that node already wrote the note
        }
        try {
            int count = notes.append(change.siteId(), "Per-site settings saved at " + Instant.now(),
                    Note.ORIGIN_SYSTEM);
            log.info("runway-demo settings changed for site '{}': audit note appended (now {} notes)",
                    change.siteId(), count);
        } catch (RuntimeException e) {
            log.warn("runway-demo could not append the settings audit note for site '{}': {}", change.siteId(),
                    e.toString());
        }
    }

    @Override
    public void onCatalogChanged(CatalogChange change) {
        String item = switch (change.cause()) {
            case PAGE, CONTENT -> (change.sourceKey() == null ? "?" : change.sourceKey()) + ":"
                    + (change.id() == null ? "*" : change.id());
            case SETTINGS -> change.pluginId() == null ? "" : change.pluginId();
            default -> "";   // SITE, and any cause a later SPI adds
        };
        Entry entry = new Entry(Instant.now(), change.cause().name().toLowerCase(Locale.ROOT), item,
                paths(change.previousUrls()), paths(change.currentUrls()), change.local());
        Deque<Entry> entries = recent.computeIfAbsent(change.siteId(), id -> new ArrayDeque<>());
        synchronized (entries) {
            entries.addFirst(entry);
            while (entries.size() > KEEP) {
                entries.removeLast();
            }
        }
    }

    /** The site's recent changes, newest first. */
    public List<Entry> recent(String siteId) {
        Deque<Entry> entries = recent.get(siteId);
        if (entries == null) {
            return List.of();
        }
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    private static List<String> paths(List<LocalizedUrl> urls) {
        return urls.stream().map(LocalizedUrl::path).toList();
    }
}
