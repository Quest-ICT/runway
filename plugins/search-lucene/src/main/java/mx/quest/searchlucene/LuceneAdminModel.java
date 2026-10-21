package mx.quest.searchlucene;

/**
 * Read-only status model for the plugin's console surfaces (dashboard card + admin fragment), read by template
 * bean name, e.g. {@code ${@searchLuceneAdminModel.indexedPages(tenantSiteId)}}. It delegates to the live
 * {@link LuceneSearchEngine} so the numbers reflect the actual per-site index and effective ranking config.
 */
public class LuceneAdminModel {

    private final LuceneSearchEngine engine;

    public LuceneAdminModel(LuceneSearchEngine engine) {
        this.engine = engine;
    }

    /** How many pages are in this site's Lucene index right now. */
    public int indexedPages(String siteId) {
        return engine.indexedDocuments(siteId);
    }

    /** The engine key (constant) — shown so an operator can confirm which engine is active. */
    public String engineKey() {
        return LuceneSearchEngine.KEY;
    }

    /** The effective title boost applied to this site's queries. */
    public String titleBoost(String siteId) {
        return String.valueOf(engine.effectiveTitleBoost(siteId));
    }

    /** The active index storage mode ({@code heap} or {@code disk}) — an app-level (platform) setting. */
    public String storageMode() {
        return engine.storageMode();
    }
}
