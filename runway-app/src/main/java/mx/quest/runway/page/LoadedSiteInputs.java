package mx.quest.runway.page;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import mx.quest.runway.content.ContentCatalog;
import mx.quest.runway.i18n.SiteAwareMessageSource;
import mx.quest.runway.icon.SiteIconService;
import mx.quest.runway.icon.SiteManifestService;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.RunningPlugins;
import mx.quest.runway.plugin.SitePluginConfigService;
import mx.quest.runway.seo.SeoConfigService;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteContactService;
import mx.quest.runway.site.SiteParamsService;
import mx.quest.runway.site.SiteViews;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.widget.WidgetInstanceConfigService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The page-validator inputs that a render reads from <b>memory</b> — loaded configuration — rather than from a
 * template file, each folded <b>as loaded</b>, so a page's ETag describes the very values its body is rendered
 * from.
 *
 * <h2>Why "as loaded" and not the file</h2>
 * A strong ETag promises that the body served under it is the body a fresh render would produce. Folding a
 * config <em>file's</em> bytes breaks that promise whenever the file and the in-memory copy disagree: a
 * {@code site.yml} or {@code seo.yml} edited on disk moved the ETag at once while every render kept using the
 * cached record until a site reload — the old body went out under the new ETag, and a client or CDN that stored
 * it revalidated it to {@code 304} for good. Folding what is loaded makes the ETag move exactly when the change
 * takes effect (a console save, a site reload, a restart), together with the body. Each value is read from the
 * same cache the render reads, and caches only move forward, so a render can be newer than the fold that preceded
 * it (harmless: the next request's ETag differs from the client's) but never older.
 *
 * <h2>What is folded</h2>
 * <ul>
 *   <li>{@link #siteRecord} — the {@link Site} record the request resolved (from {@code site.yml}; applied by a
 *       save or a site reload).</li>
 *   <li>{@link #bundles} — the per-site message bundles exactly as the site's message source has them
 *       ({@link SiteAwareMessageSource#bundleState}, which re-checks the files first: bundle edits are live).</li>
 *   <li>{@link #headState} — the per-site SEO config, icon presence and web manifest the kernel materializes into
 *       every page's {@code HeadContext}.</li>
 *   <li>{@link #pluginSiteSettings} — the per-site settings, for <b>this</b> site, of every running plugin that
 *       declares {@code siteSettings:} ({@code sites/<id>/plugins/<plugin>.yml}, through the cache
 *       {@code PluginSettings} reads), so a plugin needs to declare nothing for a per-site setting to move the
 *       ETag. Another site's settings never fold.</li>
 *   <li>{@link #pluginAppSettings} — every running plugin's <b>app-level</b> settings: whatever the
 *       {@code Environment} resolves under {@code runway.plugin.<id>.*} (the UI-saved
 *       {@code external/plugin-settings/<id>.yml}, {@code application.yml}, environment variables, the command
 *       line). These bind once, through the plugin's {@code @ConfigurationProperties}, and change only with a
 *       restart, so they are fingerprinted once — the values the running plugins actually have.</li>
 *   <li>{@link #siteParams} — the site's template parameters ({@code params.yml}, the {@code ${params}} every render
 *       model carries) as {@link SiteParamsService} has them (applied by a console save or a site reload).</li>
 *   <li>{@link #widgetInstances} — the site's per-placement widget config ({@code widget-instances.yml}) as
 *       {@link WidgetInstanceConfigService} hands it to the widgets (applied by a console save or a site reload).</li>
 *   <li>{@link #urlMapInputs} — what the cross-page URL map ({@code ${urls}}) is built from: every page's slug,
 *       publication, paths and locales (the page store's records, each re-checked against its {@code .meta.yml}),
 *       and every content source's enumeration. A page linking to another moves its ETag when the target's URL
 *       changes.</li>
 * </ul>
 * {@link #replacedDeclaredInputs} tells the validator which declared head inputs ({@code HeadContributor#headInputs})
 * this class already folds as loaded, so their file bytes are <b>not</b> folded as well: the file would move the
 * ETag before the change applies, which is the very defect this class exists to remove.
 *
 * <p>Cost per page request: cache reads plus one memoized digest per value — a digest is computed once per loaded
 * instance (a weak, identity-keyed memo), relying on these caches handing out the same unmodified instance until
 * they evict it (the contract {@code PluginSettingsImpl} relies on too). Thread-safe.
 */
@Component
public class LoadedSiteInputs {

    private static final String APP_SETTINGS_PREFIX = "runway.plugin.";

    private final SiteAwareMessageSource messages;
    private final SeoConfigService seoConfig;
    private final SiteIconService iconService;
    private final SiteManifestService manifestService;
    private final SitePluginConfigService pluginConfig;
    private final RunningPlugins runningPlugins;
    private final Environment environment;
    private final SiteParamsService siteParams;
    private final WidgetInstanceConfigService widgetConfig;
    private final PageStore pageStore;
    private final ContentCatalog contentCatalog;
    private final SiteContactService contact;
    /** Identity-keyed (weak keys compare by {@code ==}): one digest per loaded instance. */
    private final Cache<Object, String> digests = Caffeine.newBuilder().weakKeys().maximumSize(16_384).build();
    /** Identity-keyed like {@link #digests}: one URL-facet digest per cached {@link Page} record. */
    private final Cache<Page, String> urlFacets = Caffeine.newBuilder().weakKeys().maximumSize(16_384).build();
    /** Computed on first use: the Environment and the plugin set are fixed once the application is running. */
    private volatile String appSettings;

    /**
     * The short form (no params, widget config or URL-map inputs — those fold nothing). Kept for tests and callers that
     * predate 1.0.0-rc1.
     */
    public LoadedSiteInputs(SiteAwareMessageSource messages, SeoConfigService seoConfig, SiteIconService iconService,
                            SiteManifestService manifestService, SitePluginConfigService pluginConfig,
                            RunningPlugins runningPlugins, Environment environment) {
        this(messages, seoConfig, iconService, manifestService, pluginConfig, runningPlugins, environment,
                null, null, null, null);
    }

    /**
     * Full form. Every collaborator may be {@code null} (a bare test, a context without that feature); the matching
     * input then folds nothing and replaces no declared file.
     */
    public LoadedSiteInputs(SiteAwareMessageSource messages, SeoConfigService seoConfig, SiteIconService iconService,
                            SiteManifestService manifestService, SitePluginConfigService pluginConfig,
                            RunningPlugins runningPlugins, Environment environment, SiteParamsService siteParams,
                            WidgetInstanceConfigService widgetConfig, PageStore pageStore,
                            ContentCatalog contentCatalog) {
        this(messages, seoConfig, iconService, manifestService, pluginConfig, runningPlugins, environment, siteParams,
                widgetConfig, pageStore, contentCatalog, null);
    }

    /** Full form with the site's contact information ({@code ${contact}}), which the render model carries. */
    public LoadedSiteInputs(SiteAwareMessageSource messages, SeoConfigService seoConfig, SiteIconService iconService,
                            SiteManifestService manifestService, SitePluginConfigService pluginConfig,
                            RunningPlugins runningPlugins, Environment environment, SiteParamsService siteParams,
                            WidgetInstanceConfigService widgetConfig, PageStore pageStore,
                            ContentCatalog contentCatalog, SiteContactService contact) {
        this.contact = contact;
        this.messages = messages;
        this.seoConfig = seoConfig;
        this.iconService = iconService;
        this.manifestService = manifestService;
        this.pluginConfig = pluginConfig;
        this.runningPlugins = runningPlugins;
        this.environment = environment;
        this.siteParams = siteParams;
        this.widgetConfig = widgetConfig;
        this.pageStore = pageStore;
        this.contentCatalog = contentCatalog;
    }

    @Autowired
    public LoadedSiteInputs(ObjectProvider<SiteAwareMessageSource> messages, ObjectProvider<SeoConfigService> seoConfig,
                            ObjectProvider<SiteIconService> iconService,
                            ObjectProvider<SiteManifestService> manifestService,
                            ObjectProvider<SitePluginConfigService> pluginConfig,
                            ObjectProvider<RunningPlugins> runningPlugins, Environment environment,
                            ObjectProvider<SiteParamsService> siteParams,
                            ObjectProvider<WidgetInstanceConfigService> widgetConfig,
                            ObjectProvider<PageStore> pageStore, ObjectProvider<ContentCatalog> contentCatalog,
                            ObjectProvider<SiteContactService> contact) {
        this(messages.getIfAvailable(), seoConfig.getIfAvailable(), iconService.getIfAvailable(),
                manifestService.getIfAvailable(), pluginConfig.getIfAvailable(), runningPlugins.getIfAvailable(),
                environment, siteParams.getIfAvailable(), widgetConfig.getIfAvailable(), pageStore.getIfAvailable(),
                contentCatalog.getIfAvailable(), contact.getIfAvailable());
    }

    /** The site's template parameters as loaded ({@code "-"} when no params service is wired). */
    String siteParams(String siteId) {
        return siteParams == null ? "-" : memoDigest(siteParams.get(siteId));
    }

    /** The site's contact information as loaded ({@code "-"} when no contact service is wired). */
    String siteContact(String siteId) {
        return contact == null ? "-" : memoDigest(contact.get(siteId));
    }

    /** The site's widget-instance config as loaded ({@code "-"} when no widget-config store is wired). */
    String widgetInstances(String siteId) {
        return widgetConfig == null ? "-" : memoDigest(widgetConfig.siteConfigs(siteId));
    }

    /**
     * What the cross-page URL map of {@code site} is built from, as loaded: per page (in slug order) its slug,
     * publication, default and localized paths and locales; per content source (in registration order) its
     * enumeration. Locale-independent — the union of what every locale's map reads — so one value serves every locale.
     * The page records come from the page store, which re-checks each {@code .meta.yml} with one {@code stat}; their
     * facet digests and the sources' lists are memoized by identity, so an unchanged site costs one short digest.
     *
     * @return a hex digest, or {@code ""} when neither a page store nor a content catalog is wired
     */
    String urlMapInputs(Site site) {
        if (pageStore == null && contentCatalog == null) {
            return "";
        }
        StringBuilder state = new StringBuilder();
        if (pageStore != null) {
            for (Page page : pageStore.findAll(site.id())) {
                state.append(urlFacets.get(page, LoadedSiteInputs::urlFacet)).append(';');
            }
        }
        state.append('|');
        if (contentCatalog != null) {
            for (Map.Entry<String, List<ContentRef>> source
                    : contentCatalog.urlMapInputs(SiteViews.toView(site)).entrySet()) {
                state.append(source.getKey().length()).append(':').append(source.getKey()).append('=')
                        .append(memoDigest(source.getValue())).append(';');
            }
        }
        return CanonicalFingerprint.sha256Hex(state.toString());
    }

    /** The fields of a page the URL map reads ({@code PageRouting.urlMap}, {@code LocaleUrls.urlFor}). */
    private static String urlFacet(Page page) {
        return CanonicalFingerprint.digest(List.of(page.slug(), page.published(),
                page.path() == null ? "" : page.path(), page.localizedPaths(), page.locales()));
    }

    /** The site record's digest — every field, so a field added later folds without anyone remembering to. */
    String siteRecord(Site site) {
        return memoDigest(site);
    }

    /**
     * The site's message bundles as its message source has them (re-checked against the disk first), or
     * {@code null} when no message source is wired — the validator then folds the bundle files directly.
     */
    SiteAwareMessageSource.BundleState bundles(String siteId) {
        return messages == null ? null : messages.bundleState(siteId);
    }

    /** The loaded SEO config + icon presence + manifest the head materializes; {@code null} when none is wired. */
    String headState(String siteId) {
        if (seoConfig == null && iconService == null) {
            return null;
        }
        StringBuilder state = new StringBuilder();
        state.append("seo=").append(seoConfig == null ? "-" : memoDigest(seoConfig.get(siteId)));
        if (iconService != null) {
            state.append(";icons=").append(memoDigest(iconService.presentSlots(siteId)));
            state.append(";manifest=").append(manifestService == null ? "-" : memoDigest(manifestService.get(siteId)));
        }
        return state.toString();
    }

    /**
     * The per-site settings of every running plugin that declares {@code siteSettings:}, for {@code siteId} only,
     * sorted by plugin id; {@code ""} when there is none or no settings store is wired.
     */
    String pluginSiteSettings(String siteId) {
        if (pluginConfig == null) {
            return "";
        }
        StringBuilder state = new StringBuilder();
        for (String pluginId : siteSettingsPluginIds()) {
            Map<String, Object> loaded = pluginConfig.config(siteId, pluginId);
            state.append(pluginId.length()).append(':').append(pluginId).append('=').append(memoDigest(loaded))
                    .append(';');
        }
        return state.toString();
    }

    /**
     * A digest of every running plugin's app-level settings as the {@code Environment} resolves them, computed once.
     * Secrets fold in their stored form (an {@code ENC(...)} value as ciphertext) and only ever as part of a digest.
     */
    String pluginAppSettings() {
        String token = appSettings;
        if (token == null) {
            token = environment == null ? "" : appSettingsDigest(environment, runningPluginIds());
            appSettings = token;
        }
        return token;
    }

    /**
     * The site-relative paths a head contributor may declare that this class already folds as loaded —
     * {@code seo.yml}, {@code manifest.yml}, {@code icons}, {@code plugins/<id>.yml} of a running plugin with
     * {@code siteSettings:} — so the validator
     * must not fold their bytes as well. {@code site.yml} is always in the set (the site record is always folded).
     *
     * @return normalized, {@code /}-separated paths relative to the site folder
     */
    Set<String> replacedDeclaredInputs() {
        Set<String> replaced = new HashSet<>();
        replaced.add("site.yml");
        if (seoConfig != null) {
            replaced.add("seo.yml");
        }
        if (contact != null) {
            replaced.add("contact.yml");
        }
        if (iconService != null) {
            replaced.add("icons");
            if (manifestService != null) {
                replaced.add("manifest.yml");
            }
        }
        if (pluginConfig != null) {
            for (String pluginId : siteSettingsPluginIds()) {
                replaced.add("plugins/" + pluginId + ".yml");
            }
        }
        return replaced;
    }

    private List<String> runningPluginIds() {
        return pluginIds(false);
    }

    /**
     * The running plugins that declare per-site settings ({@code siteSettings:}) — the only ones a site manager can
     * set, and the only ones folded per site. Folding every plugin would put a settings-cache entry per site per
     * plugin on every request; on a many-tenant node that churns the bounded cache for plugins with nothing to read.
     */
    private List<String> siteSettingsPluginIds() {
        return pluginIds(true);
    }

    private List<String> pluginIds(boolean withSiteSettings) {
        if (runningPlugins == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (LoadedPlugin plugin : runningPlugins.installed()) {
            if (!withSiteSettings || !plugin.descriptor().siteSettings().isEmpty()) {
                ids.add(plugin.id());
            }
        }
        ids.sort(null);
        return ids;
    }

    private String memoDigest(Object value) {
        if (value == null) {
            return "-";
        }
        return digests.get(value, CanonicalFingerprint::digest);
    }

    /**
     * Fingerprints, per plugin id (sorted), every property name under {@code runway.plugin.<id>} that any property
     * source offers, with the value of the highest-precedence source — the value Spring binds. A property name in a
     * relaxed form (an environment variable) folds under its own name; a superset of what binds is harmless.
     */
    static String appSettingsDigest(Environment environment, List<String> pluginIds) {
        StringBuilder all = new StringBuilder();
        Iterable<ConfigurationPropertySource> sources = ConfigurationPropertySources.get(environment);
        for (String pluginId : pluginIds) {
            all.append(pluginId.length()).append(':').append(pluginId);
            String prefix = APP_SETTINGS_PREFIX + pluginId;
            if (!ConfigurationPropertyName.isValid(prefix)) {
                all.append("!;"); // no @ConfigurationProperties can bind such a prefix: nothing to fold
                continue;
            }
            ConfigurationPropertyName root = ConfigurationPropertyName.of(prefix);
            TreeMap<String, String> values = new TreeMap<>();
            for (ConfigurationPropertySource source : sources) {
                if (!(source instanceof IterableConfigurationPropertySource iterable)) {
                    continue;
                }
                iterable.filter(root::isAncestorOf).forEach(name -> {
                    if (!values.containsKey(name.toString())) { // sources iterate in precedence order: first wins
                        ConfigurationProperty property = source.getConfigurationProperty(name);
                        values.put(name.toString(), property == null ? null : String.valueOf(property.getValue()));
                    }
                });
            }
            all.append(CanonicalFingerprint.of(values)).append(';');
        }
        return CanonicalFingerprint.sha256Hex(all.toString());
    }
}
