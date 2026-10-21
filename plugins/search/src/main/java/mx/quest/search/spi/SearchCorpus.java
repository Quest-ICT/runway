package mx.quest.search.spi;

import java.util.List;
import java.util.Locale;
import mx.quest.runway.spi.model.CatalogEntry;

/**
 * What a site's search index is built from — the search plugin's <b>own</b> published service (plugin-to-plugin,
 * since search 2.0.0). It is the kernel's content surface ({@code ContentCatalogView}: every published page and
 * content-source item, with host-computed URLs) <b>minus the site's search exclusions</b> (the per-site
 * {@code exclude} setting of the {@code search} plugin). Every {@link SiteSearchEngine} builds from it — the
 * built-in one and a contributed one such as {@code search-lucene} — so the exclusion rule is applied in exactly one
 * place and no engine reads the search plugin's settings itself.
 *
 * <h2>How to get it</h2>
 * The search plugin registers one bean of this type ({@code searchCorpus}). A companion plugin that declares
 * {@code requires: "search >= 2.0.0"} receives it by ordinary Spring injection, by type.
 *
 * <h2>The exclusion rule</h2>
 * An entry is left out when its own id ({@code entry.ref().id()}: a page's slug, a content-source item's id — never
 * the composite {@code source:id} form) equals, ignoring case, one of the site's {@code exclude} values; a leading
 * {@code /} and surrounding blanks on a value are ignored, so an operator may type {@code legal/privacy} or
 * {@code /legal/privacy}.
 *
 * <h2>Freshness</h2>
 * Nothing here is cached by the search plugin: every call reads the kernel's listing (cached per site by the kernel,
 * evicted by every change that can reshape it) and the search plugin's current settings. A consumer that keeps an
 * index of its own drops it from a {@code SiteEventListener.onCatalogChanged} callback: every cause is relevant,
 * because a page write, a content change, a site lifecycle event and <b>any</b> plugin's settings save — the search
 * plugin's {@code exclude} list included — can reshape this corpus.
 *
 * <h2>Rules</h2>
 * Every method takes a site id; an unsafe id throws {@link IllegalArgumentException}, a well-formed id of an unknown
 * site answers empty or {@code ""}. Thread-safe; no method returns {@code null}.
 *
 * <h2>Evolution</h2>
 * Like {@link SiteSearchEngine}, this interface is binary API: it grows by {@code default} methods only.
 *
 * @since search 2.0.0
 */
public interface SearchCorpus {

    /**
     * The site's searchable entries: every published page and content-source item of the kernel's listing, in the
     * listing's order, except the excluded ones.
     *
     * @param siteId the site
     * @return the entries (never {@code null}; unmodifiable; empty for an unknown site)
     */
    List<CatalogEntry> corpus(String siteId);

    /**
     * The entry's indexable material in {@code locale} — the text an engine folds in beyond the title and
     * description, as HTML or plain text (strip markup before indexing). {@code ""} for an excluded entry, for a
     * source that does not offer indexable text, for an unknown site, and when the source fails.
     *
     * @param siteId the site
     * @param entry  an entry from {@link #corpus} of the same site
     * @param locale the locale to index
     * @return the material, or {@code ""} (never {@code null})
     */
    String indexableText(String siteId, CatalogEntry entry, Locale locale);

    /**
     * Drops the kernel's cached listing of the site, so the next {@link #corpus} re-reads every content source (the
     * exclusion list is never cached, so it is always current). For the operator's manual reindex of changes no
     * event announced (a file edited by hand); ordinary writes do not need it.
     *
     * @param siteId the site
     */
    void refresh(String siteId);
}
