package mx.quest.runway.web;

import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.site.SiteChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.cache.ICache;
import org.thymeleaf.cache.ICacheManager;
import org.thymeleaf.cache.TemplateCacheKey;
import org.thymeleaf.engine.TemplateModel;

/**
 * Targeted, event-driven eviction of the Thymeleaf parsed-template cache — the mechanism that lets
 * the cache run ON (in development and production alike) while edits still take effect: instead of disabling the
 * cache, the kernel evicts exactly the entries whose source changed.
 *
 * <p>Entries are keyed by the site-scoped template name ({@code __site__/<siteId>/<name>}). A
 * <b>page</b> edit evicts that one name; a <b>chrome/fragment</b> edit must clear more broadly,
 * because a fragment pulled into a master is held under the master's cache entry, so evicting the
 * fragment's own name would not refresh its consumers (hence {@link #evictSite(String)} on a site-level
 * change). The cache is per-JVM; when clustering is on, {@code cluster.ClusterEventRelay} bridges the
 * eviction-driving events across nodes over a Hazelcast topic so a reload fans out to every node.
 *
 * <p>Edits made <b>on disk</b> fire no event; they are caught by the entries themselves: each cached parse of a site
 * or plugin template is valid only while its {@code .html}/{@code .th.xml} are unchanged ({@link FileStampValidity},
 * checked on every hit). That is what keeps a page's body in step with its ETag, which folds those files' bytes. The
 * eviction here stays for what a file stamp cannot see or should not wait for — a console save frees the entry at
 * once, and a site delete/restore replaces files wholesale.
 */
@Service
public class TemplateCacheService {

    private final TemplateEngine engine;

    public TemplateCacheService(TemplateEngine engine) {
        this.engine = engine;
    }

    /** Evicts one template by its full site-scoped name. */
    public void evictTemplate(String siteScopedName) {
        engine.clearTemplateCacheFor(siteScopedName);
    }

    /** Evicts a single page (and is where the draft sibling will also be evicted once drafts exist). */
    public void evictPage(String siteId, String name) {
        engine.clearTemplateCacheFor(SiteTemplateResolver.PREFIX + siteId + "/" + name);
    }

    /**
     * Clears the whole parsed-template cache — for a site delete or restore (files replaced wholesale) and the
     * operator's <i>Clear template cache</i>. A change confined to one site uses {@link #evictSite(String)}.
     */
    public void evictAll() {
        engine.clearTemplateCache();
    }

    /**
     * Evicts every parsed template of one site — its pages, its {@code _master}, its fragments — and nothing of
     * any other site. An entry belongs to the site when its template name, or the name of the template that
     * owns it (a fragment parsed while rendering a page is cached under its owner), starts with
     * {@code __site__/<siteId>/}. The trailing slash matters: evicting {@code acme} must not touch
     * {@code acme2}. A no-op when the engine has no template cache (cache off).
     *
     * @return how many entries were evicted
     */
    public int evictSite(String siteId) {
        if (siteId == null || siteId.isBlank()) {
            return 0;
        }
        ICacheManager manager = engine.getCacheManager();
        ICache<TemplateCacheKey, TemplateModel> cache = manager == null ? null : manager.getTemplateCache();
        if (cache == null) {
            return 0;
        }
        String prefix = SiteTemplateResolver.PREFIX + siteId + "/";
        int evicted = 0;
        for (TemplateCacheKey key : java.util.List.copyOf(cache.keySet())) {
            if (startsWith(key.getTemplate(), prefix) || startsWith(key.getOwnerTemplate(), prefix)) {
                cache.clearKey(key);
                evicted++;
            }
        }
        return evicted;
    }

    private static boolean startsWith(String name, String prefix) {
        return name != null && name.startsWith(prefix);
    }

    /**
     * Keeps the cache honest about a site's change:
     * <ul>
     *   <li>a <b>delete or restore</b> replaces the site's files wholesale — clear the whole cache so no stale
     *       chrome is served anywhere;</li>
     *   <li>an <b>update</b> — above all the operator's <i>reload</i> ({@code SiteReloadService}), which exists
     *       for files edited outside the console — evicts <b>that site's</b> parsed templates, so the next render
     *       re-reads them from disk. Other sites keep their warm cache. Console saves of site settings publish
     *       {@code UPDATED} too; the cost there is one re-parse per template of that site, which is cheap;</li>
     *   <li>create / suspend / resume do not touch templates and evict nothing.</li>
     * </ul>
     * When clustering is on, the relayed event repeats this on every node.
     */
    @EventListener
    @Order(KernelEventOrder.RESOURCES)
    public void onSiteChanged(SiteChangedEvent event) {
        if (event.isWholesaleReplacement()) {
            evictAll();
        } else if (event.kind() == SiteChangedEvent.Kind.UPDATED) {
            evictSite(event.siteId());
        }
    }
}
