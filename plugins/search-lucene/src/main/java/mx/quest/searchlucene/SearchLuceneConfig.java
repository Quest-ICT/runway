package mx.quest.searchlucene;

import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.SiteEventListener;
import mx.quest.runway.spi.service.Sites;
import mx.quest.search.spi.SearchCorpus;
import mx.quest.search.spi.SiteSearchEngine;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The plugin's {@code mainClass} — the single {@code @Configuration} the host registers into its context before
 * refresh. Every bean is wired EXPLICITLY (no {@code @ComponentScan}); the package {@code mx.quest.searchlucene} lives
 * OUTSIDE {@code mx.quest.runway} so the host never double-scans it.
 *
 * <p><b>SPI only.</b> Everything this plugin needs arrives as a published service: {@link PluginSettings} (this
 * plugin's per-site ranking knobs, bound to this plugin by the jar this class was loaded from) and {@link Sites} (a
 * site's default locale) from the kernel's SPI 0.6.0, and {@link SearchCorpus} (what to index — the content listing
 * minus the site's search exclusions) from the {@code search} plugin's own SPI (search 2.0.0). Nothing outside
 * {@code mx.quest.runway.spi} and {@code mx.quest.search.spi} is linked. Every bean name starts with the plugin id in
 * lowerCamelCase ({@code searchLucene…}), the kernel's rule for the bean namespace plugins share.
 *
 * <p>{@code requires: search >= 2.0.0} and {@code requires: lucene-provider == 10.4.0} in {@code plugin.yml} are HARD
 * gates: without the search plugin (which publishes {@link SiteSearchEngine} and {@link SearchCorpus}) or the
 * provider (which supplies the exact Lucene binaries) this plugin is not loaded — so both are present at wire time.
 *
 * <p>App-level storage settings bind from {@code external/plugin-settings/search-lucene.yml} into
 * {@link LuceneStorageProps} through the {@code @ConfigurationProperties} on {@link #searchLuceneStorageProps()} —
 * not {@code @EnableConfigurationProperties}, which would name the bean {@code runway.plugin.search-lucene-…} and break
 * the bean-name rule.
 */
@Configuration(proxyBeanMethods = false)
public class SearchLuceneConfig {

    /** The app-level settings ({@code runway.plugin.search-lucene.*}: storage, indexRoot), applied on restart. */
    @Bean
    @ConfigurationProperties(prefix = "runway.plugin.search-lucene")
    public LuceneStorageProps searchLuceneStorageProps() {
        return new LuceneStorageProps();
    }

    /**
     * The engine, registered as its concrete type — which IS-A {@link SiteSearchEngine}, so the search plugin's
     * {@code List<SiteSearchEngine>} collects it (one definition, no duplicate; NEVER {@code @Primary}), while the
     * admin model and the listener inject the concrete type. Spring closes the engine's cached Lucene directories on
     * shutdown through {@code destroyMethod}.
     */
    @Bean(destroyMethod = "close")
    public LuceneSearchEngine searchLuceneEngine(SearchCorpus corpus, Sites sites, PluginSettings settings,
                                                 LuceneStorageProps storage) {
        return new LuceneSearchEngine(corpus, sites, settings, storage);
    }

    /** Drops a site's index on every change that may reshape what it holds (a CONSUMER listener). */
    @Bean
    public SiteEventListener searchLuceneSiteEvents(LuceneSearchEngine engine) {
        return new LuceneSiteEvents(engine);
    }

    /** Read-only status for the dashboard card and the admin fragment ({@code @searchLuceneAdminModel}). */
    @Bean
    public LuceneAdminModel searchLuceneAdminModel(LuceneSearchEngine engine) {
        return new LuceneAdminModel(engine);
    }
}
