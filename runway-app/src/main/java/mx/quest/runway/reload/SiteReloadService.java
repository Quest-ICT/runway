package mx.quest.runway.reload;

import java.util.List;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.site.SiteStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Re-reads <b>everything</b> the platform holds for a site, without a restart.
 *
 * <h2>Why this exists</h2>
 * Per-site state is read through snapshot caches that are invalidated when the platform itself writes a site.
 * An operator editing {@code sites/<id>/site.yml} (or a per-site plugin config, or dropping in icon files) <b>out
 * of band</b> therefore changes nothing until a restart — silently: no error, no warning, and the health probe
 * keeps reporting the stale answer. That is tolerable when the console is available; it is the <em>only</em> path
 * for an operator whose toolchain is scoped file access with no console credentials, and for them the restart is
 * not a fallback but a tax on every edit.
 *
 * <h2>How it re-reads everything rather than one store</h2>
 * It evicts {@link SiteStore} and then publishes {@link SiteChangedEvent.Kind#UPDATED}. That event already has a
 * platform-wide fan-out — the resolver's host index, the site's parsed Thymeleaf templates (pages, {@code _master}
 * and fragments: {@code TemplateCacheService.evictSite}, that site only — other sites stay warm), per-site i18n,
 * per-site plugin settings, SEO config, icons and the web manifest, the user/role/grant stores, documents, the
 * content catalog, and the cluster relay that repeats it to peer nodes. (Until the follow-up that added
 * {@code evictSite}, this sentence claimed template caches were cleared while {@code TemplateCacheService} acted
 * only on delete/restore — a template file edited on disk kept rendering its old parse after a "successful"
 * reload. Same lesson as below. Since 1.0.0-rc1 a template edited on disk also shows without any reload: a cached parse is
 * valid only while its files are unchanged, {@code FileStampValidity}.) So "reload this site" is not a synonym for "evict
 * SiteStore": it genuinely means re-read, and it covers the cases that actually bit — plugin config and icons —
 * without this class knowing either of them exists.
 *
 * <p><b>That fan-out is a claim about ~25 other classes, not a guarantee this one can enforce — and it was
 * already wrong once.</b> Each listener decides for itself which {@code Kind}s it acts on, so a listener gating
 * on a narrower condition silently drops out of the reload while this class still reports success.
 * {@code SitePluginConfigService} did exactly that: it invalidated only on {@code isWholesaleReplacement()}
 * (delete/restore), so per-site <i>plugin</i> config — named above as one of the two cases that motivated this
 * feature — kept serving pre-edit YAML after a reload that returned {@code {"reloaded":"<id>"}}. Found in
 * production on {@code 0.5.0-beta6}; fixed by scoping that listener's eviction to the changed site.
 *
 * <p>The lesson for anyone adding a listener here or auditing this fan-out: <b>assert that the generated
 * artefact changed</b> (fetch {@code /robots.txt}, the web manifest, the rendered page) — never that some
 * in-memory config object changed. A test written against the object would have passed against that bug,
 * because the object was never what was stale: the cache in front of it was.
 *
 * <p>Order matters: the store is evicted <b>before</b> the event, so a listener that re-reads the site during
 * handling sees the new record rather than the one being replaced.
 *
 * <p><b>{@code UPDATED} is deliberate and is not merely "some kind".</b> The seeders gate strictly on
 * {@code CREATED} ({@code TenantSeeder}, {@code TenantIconSeeder}), and the default-site janitor acts only on
 * {@code DELETED}, so {@code UPDATED} triggers invalidation and nothing constructive or destructive. Publishing a
 * different kind here would re-seed templates or clear the platform default-site pointer.
 */
public class SiteReloadService {

    private static final Logger log = LoggerFactory.getLogger(SiteReloadService.class);

    private final SiteStore sites;
    private final ApplicationEventPublisher events;

    public SiteReloadService(SiteStore sites, ApplicationEventPublisher events) {
        this.sites = sites;
        this.events = events;
    }

    /**
     * Re-reads everything for one site.
     *
     * @return {@code true} if the site exists and was reloaded; {@code false} if no such site (so the caller can
     *         answer {@code 404} rather than silently pretending to have done something)
     */
    public boolean reload(String siteId) {
        sites.evict(siteId);
        if (sites.find(siteId).isEmpty()) {
            return false;
        }
        events.publishEvent(new SiteChangedEvent(siteId, SiteChangedEvent.Kind.UPDATED));
        log.info("reloaded site '{}' on request — config re-read without a restart", siteId);
        return true;
    }

    /** Re-reads everything for every configured site; returns the ids reloaded, in order. */
    public List<String> reloadAll() {
        List<String> ids = sites.findAll().stream().map(Site::id).toList();
        ids.forEach(id -> {
            sites.evict(id);
            events.publishEvent(new SiteChangedEvent(id, SiteChangedEvent.Kind.UPDATED));
        });
        log.info("reloaded {} site(s) on request — config re-read without a restart", ids.size());
        return ids;
    }
}
