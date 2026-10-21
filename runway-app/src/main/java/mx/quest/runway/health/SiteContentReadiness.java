package mx.quest.runway.health;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.page.PageRouting;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.storage.StorageLayout;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

/**
 * The cheapest truthful answer to "could this site actually serve its home page?" — the check that stops
 * {@code /__health} reporting green for a site whose every request 500s.
 *
 * <p>Two signals, deliberately in this order:
 * <ol>
 *   <li><b>Home page routable</b> — asked through the REAL router ({@link PageRouting#find(Site, String)}), not by
 *       guessing a filename, so it honors localized paths and published/draft state exactly as a visitor's request
 *       would. Absent ⇒ {@link SiteHealth.ContentCheck#MISSING_HOME}, a definitive fault: the site's root does not
 *       serve.</li>
 *   <li><b>{@code _master.html} present</b> — one {@code Files.exists}. Absent ⇒
 *       {@link SiteHealth.ContentCheck#MISSING_MASTER}. <b>Advisory, and it can be a false alarm:</b> a page may be
 *       standalone (self-contained, pulling no master), so an all-standalone site is legitimately master-less. That
 *       is precisely why a failed content check yields {@code DEGRADED} and never {@code DOWN} — it warns a human,
 *       it never pages one, and it never claims the site is off.</li>
 * </ol>
 *
 * <p>Results are memoized per site for {@code runway.health.content-check-ttl-seconds} so a monitor polling every
 * few seconds does not stat the filesystem every time, and the memo is evicted on {@link SiteChangedEvent} so an
 * edit is reflected immediately rather than after the TTL. Thread-safe.
 */
public class SiteContentReadiness {

    private static final String MASTER = "_master.html";

    private final StorageLayout layout;
    private final PageRouting routing;
    private final HealthProperties properties;
    private final Map<String, Memo> memos = new ConcurrentHashMap<>();

    public SiteContentReadiness(StorageLayout layout, PageRouting routing, HealthProperties properties) {
        this.layout = layout;
        this.routing = routing;
        this.properties = properties;
    }

    /** The content verdict for {@code site}, memoized; {@code SKIPPED} when the check is switched off. */
    public SiteHealth.ContentCheck check(Site site) {
        if (!properties.isContentCheck()) {
            return SiteHealth.ContentCheck.SKIPPED;
        }
        long now = System.nanoTime();
        long ttlNanos = properties.getContentCheckTtlSeconds() * 1_000_000_000L;
        Memo memo = memos.get(site.id());
        if (memo != null && now - memo.takenAtNanos() < ttlNanos) {
            return memo.result();
        }
        SiteHealth.ContentCheck result = evaluate(site);
        memos.put(site.id(), new Memo(result, now));
        return result;
    }

    private SiteHealth.ContentCheck evaluate(Site site) {
        if (routing.find(site, "/").isEmpty()) {
            return SiteHealth.ContentCheck.MISSING_HOME;
        }
        Path master = layout.siteDir(site.id()).resolve("templates").resolve(MASTER);
        return Files.exists(master) ? SiteHealth.ContentCheck.OK : SiteHealth.ContentCheck.MISSING_MASTER;
    }

    /** Drops a site's memo when its content/config changes, so an edit shows up without waiting out the TTL. */
    @EventListener
    @Order(KernelEventOrder.DERIVED)
    public void onSiteChanged(SiteChangedEvent event) {
        if (event.siteId() == null) {
            memos.clear();
        } else {
            memos.remove(event.siteId());
        }
    }

    private record Memo(SiteHealth.ContentCheck result, long takenAtNanos) {
    }
}
