package mx.quest.search;

import java.util.List;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.service.AccessCheck;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.PluginSiteFiles;
import mx.quest.runway.spi.service.SiteEventListener;
import mx.quest.runway.spi.service.SitePages;
import mx.quest.runway.spi.service.Sites;
import mx.quest.search.spi.SearchCorpus;
import mx.quest.search.spi.SiteSearchEngine;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The plugin's {@code mainClass} — the single {@code @Configuration} the host registers into its context before
 * refresh. Every bean is wired EXPLICITLY (no {@code @ComponentScan}). The package {@code mx.quest.search} lives
 * OUTSIDE {@code mx.quest.runway} so the host never double-scans it.
 *
 * <p><b>SPI only.</b> Everything this plugin needs from the kernel arrives as an SPI service (SPI 0.6.0):
 * {@link PluginSettings} (this plugin's per-site settings) and {@link PluginSiteFiles} (this plugin's per-site
 * folder, where the no-result terms are kept) are bound to this plugin by the jar this class was loaded from;
 * {@link ContentCatalogView}, {@link Sites}, {@link Messages}, {@link AccessCheck} and {@link SitePages} are kernel
 * singletons. Nothing outside {@code mx.quest.runway.spi} is linked. Every bean name starts with the plugin id
 * ({@code search…}), the kernel's rule for a bean namespace plugins share.
 *
 * <p>The plugin publishes its own SPI ({@code mx.quest.search.spi}): the {@link SearchCorpus} every engine builds
 * from (the content listing minus the site's exclusions) and the {@link SiteSearchEngine} extension point, with the
 * built-in {@link SimpleSearchEngine}. The {@link SearchEndpoint} and {@link SearchBoxWidget} are list-collected
 * kernel SPIs — plain {@code @Bean}s, NEVER {@code @Primary}; both inject {@code List<SiteSearchEngine>}, so a
 * companion plugin (e.g. {@code search-lucene}) that contributes a higher-priority engine is picked up transparently.
 * One {@link SiteEventListener} ({@link SearchSiteEvents}) keeps the built-in index and the no-result list fresh, and
 * one {@link HeadContributor} ({@link SearchHighlightHead}) lets a page opened from a result highlight the matches.
 *
 * <p>App-level knobs bind from {@code external/plugin-settings/search.yml} into {@link SearchProperties} through
 * the {@code @ConfigurationProperties} on {@link #searchProperties()}.
 */
@Configuration(proxyBeanMethods = false)
public class SearchConfig {

    /**
     * This release's version, appended as {@code ?v=} to the plugin's own file URLs (search.css, the highlighter) so
     * a browser fetches the new files right after an upgrade instead of keeping the previous copy for the kernel's
     * plugin-file cache lifetime. {@code SearchConfigTest} holds it equal to {@code plugin.yml}'s version and to the
     * URLs inside the static scripts.
     */
    public static final String VERSION = "2.3.1";

    /** The query a plugin file URL ends with. */
    static final String ASSET_QUERY = "?v=" + VERSION;

    /** The app-level settings ({@code runway.plugin.search.*}), bound by the host's properties binder. */
    @Bean
    @ConfigurationProperties(prefix = "runway.plugin.search")
    public SearchProperties searchProperties() {
        return new SearchProperties();
    }

    /** What every engine indexes: the kernel's content listing minus the site's {@code exclude} list. */
    @Bean
    public SearchCorpus searchCorpus(ContentCatalogView catalog, PluginSettings settings) {
        return new SiteSearchCorpus(catalog, settings);
    }

    /** The built-in engine — a full-text matcher over title/description/body; always available, priority 0. */
    @Bean
    public SimpleSearchEngine searchSimpleEngine(SearchCorpus corpus, Sites sites, PluginSettings settings,
                                                 SearchProperties properties) {
        return new SimpleSearchEngine(corpus, sites, settings, properties);
    }

    /** The per-client query limiter for the JSON API, built from the app-level rate-limit settings. */
    @Bean
    public SearchRateLimiter searchRateLimiter(SearchProperties properties) {
        return new SearchRateLimiter(properties.getRateLimit());
    }

    /**
     * The per-site record of searches that found nothing (opt-in per site; see {@link NoResultsLog}). Its
     * {@code close()} is the bean's inferred destroy method, so pending terms are written on shutdown.
     */
    @Bean
    public NoResultsLog searchNoResultsLog(PluginSiteFiles files, PluginSettings settings) {
        return new NoResultsLog(files, settings);
    }

    /** Drops the built-in index on every content-surface change, and the no-result list on a restore/delete. */
    @Bean
    public SiteEventListener searchSiteEvents(SimpleSearchEngine engine, NoResultsLog noResults) {
        return new SearchSiteEvents(engine, noResults);
    }

    /** The JSON API and the gated maintenance actions (reindex, page install, clear no-result terms). */
    @Bean
    public EndpointContributor searchResultsEndpoint(List<SiteSearchEngine> engines, PluginSettings settings,
                                                     Messages messages, SearchRateLimiter rateLimiter,
                                                     AccessCheck access, SitePages pages, NoResultsLog noResults) {
        return new SearchEndpoint(engines, settings, messages, rateLimiter, access, pages, noResults);
    }

    /**
     * The embeddable, query-aware search box ({@code data-widget="search/box"}): renders the box, and when its
     * page is requested with {@code ?q=…} renders the results inline — so the results page is a normal editable
     * tenant page, not a plugin-owned surface.
     */
    @Bean
    public ContentWidget searchBoxWidget(List<SiteSearchEngine> engines, PluginSettings settings, Messages messages,
                                         NoResultsLog noResults, ContentCatalogView catalog) {
        return new SearchBoxWidget(engines, settings, messages, noResults, new ResultsPageLinks(catalog));
    }

    /**
     * The result highlighter's loader on every page of a site with search and {@code highlight} on: a page opened
     * from a result highlights the words the engine matched. A list-collected kernel SPI — a plain {@code @Bean}.
     */
    @Bean
    public HeadContributor searchHighlightHead(PluginSettings settings) {
        return new SearchHighlightHead(settings);
    }

    /** Status model for the dashboard card + admin fragment (including the no-result terms list). */
    @Bean
    public SearchAdminModel searchAdminModel(List<SiteSearchEngine> engines, SearchCorpus corpus, SitePages pages,
                                             PluginSettings settings, NoResultsLog noResults) {
        return new SearchAdminModel(engines, corpus, pages, settings, noResults);
    }
}
