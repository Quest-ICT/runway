package mx.quest.search;

import java.util.Objects;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.service.SiteEventListener;

/**
 * The plugin's one {@link SiteEventListener} (a {@link Phase#CONSUMER CONSUMER}: it reads the content listing, it
 * contributes none). The kernel delivers it after every kernel cache the change affects has been evicted, and after
 * every content source has dropped its own state, so what it triggers rebuilds from the new state.
 *
 * <ul>
 *   <li>{@link #onCatalogChanged} — <b>every</b> cause drops the built-in engine's index for the site: a page
 *       write, a content source's change, a site lifecycle event, and any plugin's settings save (a source reshapes
 *       its items from its settings, and this plugin's own {@code exclude} list reshapes the corpus). The callback
 *       only evicts; the rebuild happens lazily on the next query.</li>
 *   <li>{@link #onSiteLifecycle} — a <b>wholesale</b> event (the site was restored from a backup, or deleted) makes
 *       {@link NoResultsLog} forget its in-memory list unwritten, so the restored file is what is read next and a
 *       deleted site's list is never written back.</li>
 * </ul>
 * Both run on every node, for local and replayed changes alike: they only drop local state.
 */
public class SearchSiteEvents implements SiteEventListener {

    private final SimpleSearchEngine engine;
    private final NoResultsLog noResults;

    public SearchSiteEvents(SimpleSearchEngine engine, NoResultsLog noResults) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.noResults = Objects.requireNonNull(noResults, "noResults");
    }

    @Override
    public Phase phase() {
        return Phase.CONSUMER;
    }

    @Override
    public void onSiteLifecycle(SiteLifecycleEvent event) {
        if (event.wholesale()) {
            noResults.forget(event.siteId());
        }
    }

    @Override
    public void onCatalogChanged(CatalogChange change) {
        engine.invalidate(change.siteId());
    }
}
