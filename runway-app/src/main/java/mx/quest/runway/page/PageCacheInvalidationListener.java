package mx.quest.runway.page;

import java.util.Objects;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.web.TemplateCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The single place a {@link PageChangedEvent} turns into cache eviction. It performs exactly the two evictions a
 * page edit has always needed — the {@link PageStore} record cache and the parsed-template cache
 * — so that every page mutation ({@link PageWriteService} save/create/delete/rename/draft/re-seed) flows through
 * ONE eviction path: the write publishes the event, this listener evicts. The eviction <b>semantics</b> are
 * unchanged from when {@code PageWriteService} poked the caches inline; only the trigger moved to an event.
 *
 * <p>Consolidating on an event (rather than a direct call) buys two things: it lets the cluster relay observe
 * the same signal and fan a page edit out to peer nodes (see {@code cluster.ClusterEventRelay}), and it gives a
 * clean seam for any future non-write origin of a page change (an admin "rescan disk" action, a restore) to
 * drop the same caches. Because Spring events are synchronous, the eviction still completes before the write
 * call that published the event returns — no page is ever briefly served stale by the mutating node.
 *
 * @see PageChangedEvent
 */
@Component
public class PageCacheInvalidationListener {

    private static final Logger log = LoggerFactory.getLogger(PageCacheInvalidationListener.class);

    private final PageStore pages;
    private final TemplateCacheService templateCache;

    public PageCacheInvalidationListener(PageStore pages, TemplateCacheService templateCache) {
        this.pages = Objects.requireNonNull(pages, "pages");
        this.templateCache = Objects.requireNonNull(templateCache, "templateCache");
    }

    /**
     * Evicts the changed page's two cache entries. A {@code null} slug means "the whole site's pages" — a
     * wholesale drop of the record cache — reserved for a future site-wide page invalidation; the single-page
     * path (the only caller today) evicts just that page in both caches, exactly as the old inline evict did.
     */
    @EventListener
    @Order(KernelEventOrder.STORES)
    public void onPageChanged(PageChangedEvent event) {
        if (event.slug() == null) {
            log.debug("Invalidating ALL page caches for site {}", event.siteId());
            pages.evictSite(event.siteId());
            templateCache.evictAll();
            return;
        }
        log.debug("Invalidating page cache for {}/{}", event.siteId(), event.slug());
        pages.evict(event.siteId(), event.slug());
        templateCache.evictPage(event.siteId(), event.slug());
    }
}
