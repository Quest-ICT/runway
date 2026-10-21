package mx.quest.discover;

import java.util.LinkedHashMap;
import java.util.Map;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.Sites;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * The plugin's {@code mainClass} — the single {@code @Configuration} the host registers into its context before
 * refresh. It wires every bean EXPLICITLY (no {@code @ComponentScan}: scanning a plugin's own package across the
 * shared child classloader is fragile). The plugin package ({@code mx.quest.discover}) is deliberately OUTSIDE
 * {@code mx.quest.runway} so the host never double-scans it.
 *
 * <p>The {@link HeadContributor} and {@link EndpointContributor}s are list-collected SPIs — plain
 * {@code @Bean}s, NEVER {@code @Primary}. The head contributor is ordered AFTER the kernel's own SEO
 * ({@code @Order 100}) and icon ({@code @Order 90}) head contributors, so the plugin's structured data is
 * spliced last.
 *
 * <p><b>Links only the Runway SPI</b> (since 2.0.0). Every host service arrives by ordinary injection of an SPI
 * type: {@link PluginSettings} (this plugin's per-site settings, bound to this plugin by the jar its code was
 * loaded from), {@link ContentCatalogView} (the site's published content surface with host-computed URLs) and
 * {@link Sites}. Every bean name starts with the plugin id ({@code discoverability…}): plugins share the host's
 * bean namespace.
 */
@Configuration(proxyBeanMethods = false)
public class DiscoverabilityConfig {

    // --- app-level (platform) settings ----------------------------------------------------------------

    /**
     * The app-level settings, bound from {@code runway.plugin.discoverability.*}. Declared as a named {@code @Bean}
     * rather than through {@code @EnableConfigurationProperties}, which would register it under the generated name
     * {@code runway.plugin.discoverability-mx.quest.discover.DiscoverabilityProperties} — outside the plugin's bean
     * prefix.
     */
    @Bean
    @ConfigurationProperties("runway.plugin.discoverability")
    public DiscoverabilityProperties discoverabilityProperties() {
        return new DiscoverabilityProperties();
    }

    // --- head contributor (SEO+ and GEO in one @graph) ------------------------------------------------

    @Bean
    @Order(150)
    public HeadContributor discoverabilityStructuredData(PluginSettings settings) {
        return new StructuredDataHead(settings, schemaTypes());
    }

    /**
     * The content-type registry (item 5), indexed by the type token a tenant declares in {@code content.schema}.
     * Subtypes are registered under aliases that share one implementation (e.g. {@code BlogPosting}→Article).
     * Fails fast on a duplicate key so a wiring mistake never silently shadows a type.
     */
    static Map<String, SchemaType> schemaTypes() {
        Map<String, SchemaType> types = new LinkedHashMap<>();
        register(types, new FaqPageSchema());
        register(types, new ProductSchema());
        register(types, new ArticleSchema(), "BlogPosting", "NewsArticle");
        register(types, new LocalBusinessSchema(), "CafeOrCoffeeShop");
        register(types, new ServiceSchema(), "ProfessionalService");
        register(types, new PersonSchema());
        register(types, new CollectionSchema(), "CollectionPage");
        for (String key : SimplePageType.KEYS) {
            register(types, new SimplePageType(key));
        }
        return types;
    }

    private static void register(Map<String, SchemaType> types, SchemaType type, String... aliases) {
        put(types, type.key(), type);
        for (String alias : aliases) {
            put(types, alias, type);
        }
    }

    private static void put(Map<String, SchemaType> types, String key, SchemaType type) {
        if (types.putIfAbsent(key, type) != null) {
            throw new IllegalStateException("duplicate schema type key: " + key);
        }
    }

    // --- crawl-surface endpoints ----------------------------------------------------------------------

    @Bean
    public EndpointContributor discoverabilitySitemap(ContentCatalogView catalog, PluginSettings settings) {
        return new SitemapEndpoint(catalog, settings);
    }

    @Bean
    public EndpointContributor discoverabilityLlms(ContentCatalogView catalog, PluginSettings settings) {
        return new LlmsTxtEndpoint(catalog, settings);
    }

    @Bean
    public EndpointContributor discoverabilityRobots(PluginSettings settings, DiscoverabilityProperties properties) {
        return new RobotsTxtEndpoint(settings, properties);
    }

    @Bean
    public EndpointContributor discoverabilityIndexNowKey(PluginSettings settings,
                                                          DiscoverabilityProperties properties) {
        return new IndexNowKeyEndpoint(settings, properties);
    }

    // --- IndexNow (outbound: OFF unless the platform operator AND the tenant both enable it) -----------

    /** A {@code SiteEventListener} (collected by type by the host) that queues changed URLs, plus the flush. */
    @Bean
    public IndexNowSubmitter discoverabilityIndexNow(Sites sites, PluginSettings settings,
                                                     DiscoverabilityProperties properties) {
        return new IndexNowSubmitter(sites, settings, properties);
    }

    // --- advisory quality check (edit-time detect-and-lift for on-page FAQs) --------------------------

    @Bean
    public PageQualityCheck discoverabilityFaqAdvisory(PluginSettings settings) {
        return new FaqAdvisoryCheck(settings, schemaTypes());
    }

    /** The page editor's line saying what each page is described as, and where that comes from. */
    @Bean
    public PageQualityCheck discoverabilityPageType(PluginSettings settings, Messages messages) {
        return new PageTypeCheck(settings, schemaTypes(), messages);
    }

    // --- console surface (dashboard card + admin fragment) --------------------------------------------

    /** Read by the templates as {@code ${@discoverabilityAdminModel…}} (renamed from {@code discoverAdminModel}). */
    @Bean
    public DiscoverAdminModel discoverabilityAdminModel(PluginSettings settings, ContentCatalogView catalog,
                                                        Messages messages) {
        return new DiscoverAdminModel(settings, catalog, schemaTypes(), messages);
    }
}
