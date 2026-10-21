package mx.quest.runway.spi.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import mx.quest.runway.spi.model.CatalogEntry;

/**
 * The read side of a site's <b>published content surface</b>: every real page and every content source's item, with
 * the URLs the host computed for each. What a sitemap, an llms.txt, a search index or an "is this path taken?" check
 * reads. A kernel singleton, injected by type.
 *
 * <h2>What the listing guarantees</h2>
 * The host re-filters every source's output <b>fail-closed</b> before it is listed: unpublished items, items with an
 * unsafe path, and items whose URL (in any locale) collides with the reserved kernel surface or an exact endpoint
 * route are dropped; a source that throws contributes nothing and never breaks the listing of the others. The URLs
 * come from the same computation as the page {@code <head>} alternates, so a listing cannot disagree with the
 * pages it lists.
 *
 * <h2>Freshness</h2>
 * {@link #entries} is cached per site and evicted by every change that can reshape the surface (a page write, a
 * {@link ContentChanges#changed} call, a site lifecycle event, any plugin's per-site settings save), with a 30-second
 * time-to-live as the backstop for changes nothing announces (a file edited by hand). {@link #refresh} drops the
 * cached listing on demand — a manual "reindex" button.
 *
 * <h2>Rules</h2>
 * Every method takes a <b>site id</b> and resolves the site on the host side (a caller can never fill the shared
 * cache from a stale or hand-built view). An unsafe id throws {@link IllegalArgumentException}; a well-formed id of
 * an unknown site answers empty, {@code ""} or {@code false}. Thread-safe; no method returns {@code null}.
 *
 * @since SPI 0.6.0
 */
public interface ContentCatalogView {

    /** The {@link CatalogEntry#sourceKey()} of real pages. */
    String PAGES_SOURCE = "pages";

    /**
     * Every published item of the site — real pages and every content source's items, grouped by source in a
     * stable order (do not rely on which source comes first) — re-filtered as described above. Metadata only: no body
     * is read, so this is safe on public, anonymous request paths.
     *
     * @param siteId the site
     * @return the entries (never {@code null}; unmodifiable; empty for an unknown site)
     */
    List<CatalogEntry> entries(String siteId);

    /**
     * The item's indexable material in {@code locale} — the text a search engine folds in beyond the title and
     * description, as HTML or plain text (strip markup before indexing). Only sources that declare the
     * {@code content.indexable-text} capability are asked; for the others, for an unknown site, and when the source
     * fails, the answer is {@code ""}.
     *
     * @param siteId the site
     * @param entry  an entry from {@link #entries} of the same site
     * @param locale the locale to index
     * @return the material, or {@code ""} (never {@code null})
     */
    String indexableText(String siteId, CatalogEntry entry, Locale locale);

    /**
     * The slug of the <b>published real page</b> that serves {@code path} in {@code locale} — the question a content
     * source asks before it publishes an item at a path ("a page owns this URL; the page wins, so the entry would be
     * unreachable"). A draft page serves nothing and owns no path.
     *
     * @param siteId the site
     * @param path   a root-relative path without a locale prefix ({@code /blog})
     * @param locale the locale whose URL space to check
     * @return the owning page's slug (when two pages wrongly claim the path, the first by slug), or empty when no
     *         published page claims it
     */
    Optional<String> pageAt(String siteId, String path, Locale locale);

    /**
     * Whether the host would route content at {@code path} at all: a safe page path that is not part of the
     * reserved kernel surface ({@code /__…}, {@code /login}, {@code /error}, …) and not an exact endpoint route
     * ({@code /sitemap.xml}, …). No content source is consulted, so a path another item already uses still answers
     * {@code true} — this is the "is this prefix even allowed?" check, not an availability check.
     *
     * @param siteId the site ({@code false} for an unknown site)
     * @param path   the root-relative path to check
     * @return whether content may be served at the path
     */
    boolean servablePath(String siteId, String path);

    /**
     * Drops the site's cached listing, so the next {@link #entries} re-reads every source. For a manual reindex of
     * changes no event announced; ordinary writes do not need it.
     *
     * @param siteId the site
     */
    void refresh(String siteId);
}
