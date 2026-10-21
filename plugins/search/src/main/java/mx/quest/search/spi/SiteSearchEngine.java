package mx.quest.search.spi;

import java.util.List;

/**
 * The search plugin's <b>own</b> extension point (a plugin-to-plugin SPI): a pluggable full-text engine that
 * answers a {@link SearchQuery} for one site with a ranked list of {@link SearchHit}s. The {@code search} plugin
 * ships a built-in substring engine over the site's page metadata; a companion plugin (e.g. {@code search-lucene})
 * declares {@code requires: search} and contributes a richer implementation as an ordinary bean.
 *
 * <p>The search plugin collects every {@code SiteSearchEngine} bean and, per request, uses the <b>available</b>
 * one with the highest {@link #priority()} — so installing a better engine transparently upgrades search with no
 * change to the query surface (the search box widget and the {@code /search} results endpoint).
 *
 * <p>Contract: an implementation is a pure query function over the site's searchable content — the
 * {@link SearchCorpus} the search plugin publishes (since 2.0.0), which already applies the site's exclusions. It
 * never mutates the query, never throws for a normal "no results" (return an empty list), and returns at most
 * {@link SearchQuery#limit()} hits already sorted best-first.
 *
 * <p>This interface, {@link SearchQuery} and {@link SearchHit} are unchanged in search 2.0.0: an engine compiled
 * against search 1.x links and behaves the same.
 */
public interface SiteSearchEngine {

    /** A stable, unique, namespaced key for this engine (e.g. {@code "search/simple"}), for logs and the console. */
    String key();

    /** The ranked hits for {@code query} (best first, at most {@link SearchQuery#limit()}); empty ⇒ no matches. */
    List<SearchHit> search(SearchQuery query);

    /**
     * Selection weight — the search surface picks the highest-priority <em>available</em> engine. The built-in
     * engine is {@code 0}; a contributed engine returns a higher value to take over.
     */
    default int priority() {
        return 0;
    }

    /**
     * Whether this engine can serve {@code siteId} right now (e.g. an index exists). When it can't, the search
     * surface falls back to the next-highest engine — so a not-yet-built index never breaks search.
     */
    default boolean available(String siteId) {
        return true;
    }

    /**
     * Rebuilds this engine's derived state for {@code siteId} from current content <b>now</b> — the operator's
     * manual reindex action — and returns the number of documents the fresh index holds. An engine that caches
     * or persists an index drops it and rebuilds eagerly (not lazily on the next query), so the confirmation the
     * operator sees reflects a completed rebuild; a rebuild failure must not throw (log and return {@code 0},
     * the same never-throw posture as {@link #search}). The default is a no-op returning {@code 0}, for an
     * engine that derives nothing and so has nothing to drop.
     *
     * <p>Added in search 1.2.0 as a {@code default} method — the SPI's binary-compatibility rule: an engine
     * compiled against an earlier SPI keeps loading unchanged and simply inherits the no-op.
     */
    default int reindex(String siteId) {
        return 0;
    }
}
