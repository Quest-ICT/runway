package mx.quest.search;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.SitePages;
import mx.quest.search.spi.SearchCorpus;
import mx.quest.search.spi.SiteSearchEngine;

/**
 * Status model for the plugin's console surfaces — the tenant dashboard card ({@code dashboard.html})
 * and the admin fragment ({@code admin.html}) rendered below the per-tenant settings form. Templates read it by
 * bean name, e.g. {@code ${@searchAdminModel.activeEngineKey(tenantSiteId)}}. Every method is per-site scoped and
 * answers a neutral value for a {@code null} site (the application console has no bound site).
 */
public class SearchAdminModel {

    private static final DateTimeFormatter LAST_SEEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final List<SiteSearchEngine> engines;
    private final SearchCorpus corpus;
    private final SitePages pages;
    private final PluginSettings settings;
    private final NoResultsLog noResults;

    public SearchAdminModel(List<SiteSearchEngine> engines, SearchCorpus corpus, SitePages pages,
                            PluginSettings settings, NoResultsLog noResults) {
        this.engines = List.copyOf(engines);
        this.corpus = Objects.requireNonNull(corpus, "corpus");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.noResults = Objects.requireNonNull(noResults, "noResults");
    }

    /** Whether this site records searches that found nothing (the opt-in {@code recordNoResults}). */
    public boolean noResultsRecording(String siteId) {
        return siteId != null && noResults.enabled(siteId);
    }

    /** The site's no-result terms within retention, most frequent first (empty when none). */
    public List<NoResultsLog.Entry> noResults(String siteId) {
        return siteId == null ? List.of() : noResults.list(siteId);
    }

    /** The site's no-result retention in days (for the hint under the list). */
    public int noResultsRetentionDays(String siteId) {
        return siteId == null ? NoResultsLog.DEFAULT_RETENTION_DAYS : noResults.retentionDays(siteId);
    }

    /** The most terms a site's list keeps (for the hint under the list). */
    public int noResultsMaxTerms() {
        return NoResultsLog.MAX_TERMS;
    }

    /**
     * A recorded term's last-seen time as {@code yyyy-MM-dd HH:mm} in the server's zone — the same clock the
     * site's logs and the kernel's Site insights screen use — which also sorts correctly as text.
     */
    public String lastSeen(NoResultsLog.Entry entry) {
        return entry == null ? "" : LAST_SEEN.format(entry.lastSeen());
    }

    /** Whether search is enabled for this site (per-tenant flag, default true). */
    public boolean enabled(String siteId) {
        return siteId == null ? SearchSiteConfig.ENABLED_DEFAULT
                : SearchSiteConfig.of(settings.site(siteId)).enabled();
    }

    /** The {@code key()} of the engine that would serve this site now (highest-priority available), or "none". */
    public String activeEngineKey(String siteId) {
        if (siteId == null) {
            return "none";
        }
        return engines.stream()
                .filter(e -> e.available(siteId))
                .max(Comparator.comparingInt(SiteSearchEngine::priority))
                .map(SiteSearchEngine::key)
                .orElse("none");
    }

    /** How many search engines are installed (the built-in one plus any contributed by other plugins). */
    public int engineCount() {
        return engines.size();
    }

    /**
     * How many items the site's search covers: every published page and content-source item of the kernel's
     * listing (a safe path, not part of the reserved surface), minus the site's {@code exclude} list — exactly what
     * every engine indexes ({@link SearchCorpus}).
     */
    public int searchableItems(String siteId) {
        return siteId == null ? 0 : corpus.corpus(siteId).size();
    }

    /**
     * Whether this site already has the ready-made search results page (the one {@code /search/install-page}
     * seeds), in any state. Asked by the installer's stable slug, so it stays true after the operator moves or
     * renames the page.
     */
    public boolean pageInstalled(String siteId) {
        return siteId != null && pages.pathOf(siteId, SearchEndpoint.INSTALLED_PAGE_SLUG).isPresent();
    }

    /**
     * The current URL path of the installed search page (e.g. {@code /search}, or wherever the operator moved it),
     * or an empty string when it is not installed. Read live so the console always shows the real location.
     */
    public String pagePath(String siteId) {
        return siteId == null ? "" : pages.pathOf(siteId, SearchEndpoint.INSTALLED_PAGE_SLUG).orElse("");
    }
}
