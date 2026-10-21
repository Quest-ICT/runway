package mx.quest.searchlucene;

import java.util.Objects;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.service.SiteEventListener;

/**
 * The plugin's one {@link SiteEventListener}, a {@link Phase#CONSUMER CONSUMER}: it reads the content listing (through
 * the search plugin's {@code SearchCorpus}) and contributes none. The kernel delivers it after every kernel cache the
 * change affects has been evicted and every content source has dropped its own state, so the rebuild it triggers reads
 * the new state.
 *
 * <p>{@link #onCatalogChanged} drops the site's index for <b>every</b> cause — a page write, a content source's change,
 * a site lifecycle event, and <b>any</b> plugin's settings save: a source reshapes its items from its settings (its
 * URL prefix, say), and the search plugin's {@code exclude} list reshapes the corpus. No {@code pluginId} scoping is
 * needed. This plugin's own settings (the ranking knobs) are read per query, so they need nothing here. The callback
 * only evicts; the rebuild happens lazily on the next query. It runs on every node, for local and replayed changes
 * alike: it only drops local state.
 */
final class LuceneSiteEvents implements SiteEventListener {

    private final LuceneSearchEngine engine;

    LuceneSiteEvents(LuceneSearchEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public Phase phase() {
        return Phase.CONSUMER;
    }

    @Override
    public void onCatalogChanged(CatalogChange change) {
        engine.invalidate(change.siteId());
    }
}
