package mx.quest.runway.plugin;

import java.lang.reflect.Member;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mx.quest.runway.content.ContentChangesImpl;
import mx.quest.runway.content.ContentSourceRegistry;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.plugin.spi.PluginSettingsImpl;
import mx.quest.runway.plugin.spi.PluginSiteFilesImpl;
import mx.quest.runway.spi.model.PluginInfo;
import mx.quest.runway.spi.service.ContentChanges;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.PluginSiteFiles;
import mx.quest.runway.storage.StorageLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.core.env.Environment;

/**
 * The factories behind the <b>plugin-scoped</b> SPI services ({@link PluginSettings}, {@link PluginSiteFiles},
 * {@link ContentChanges}, {@link PluginInfo}): each is
 * a prototype bean resolved from the {@link InjectionPoint}, bound to the plugin <b>whose own jar the injecting
 * class was loaded from</b> ({@link PluginOwnership}) — never to an id the plugin passes in, so no plugin can name
 * another. The facade is built once per plugin and cached, so every injection point of one plugin shares it.
 *
 * <h2>Fail-fast cases</h2>
 * <ul>
 *   <li>Injection into a class from a plugin's {@code lib/} jar: library code is shared by every plugin bundling
 *       it, so binding it to one would be a guess.</li>
 *   <li>Injection into kernel code (or a test class with no plugin jar): the message names the kernel
 *       alternative.</li>
 *   <li>A bare {@code getBean(PluginSettings.class)}: there is no injection point to bind by, and Spring refuses
 *       before this factory runs. An {@code ObjectProvider<PluginSettings>} <em>injected</em> into plugin code
 *       works — Spring keeps the provider's own injection point.</li>
 * </ul>
 * Each failure is an {@link IllegalStateException} at wiring time, so the plugin never starts half-bound.
 *
 * <p>Binding happens at injection, so a callback that runs later on a host thread (a console request, the
 * cluster topic thread) still answers for the right plugin.
 */
@Configuration(proxyBeanMethods = false)
public class PluginScopedServices {

    private static final Logger log = LoggerFactory.getLogger(PluginScopedServices.class);

    private final PluginOwnership ownership;
    private final SitePluginConfigService siteConfig;
    private final SecretCipher cipher;
    private final StorageLayout layout;
    private final long siteFilesMaxBytes;
    private final Environment environment;
    private final Map<String, PluginSettings> settings = new ConcurrentHashMap<>();
    private final Map<String, PluginSiteFiles> siteFiles = new ConcurrentHashMap<>();
    private final Map<String, PluginInfo> infos = new ConcurrentHashMap<>();
    private final Map<String, ContentChanges> contentChanges = new ConcurrentHashMap<>();

    /**
     * @param layout            where the per-site plugin subtrees live ({@code null} only in tests that never ask
     *                          for {@link PluginSiteFiles})
     * @param siteFilesMaxBytes {@code runway.plugins.site-files.max-write-bytes} (non-positive = the default)
     * @param environment       the running environment, which holds each plugin's app-level settings as
     *                          {@code runway.plugin.<id>.<key>} ({@code null} only in tests: no app-level secrets)
     */
    @Autowired
    public PluginScopedServices(PluginOwnership ownership, SitePluginConfigService siteConfig, SecretCipher cipher,
                                StorageLayout layout,
                                @Value("${runway.plugins.site-files.max-write-bytes:16777216}") long siteFilesMaxBytes,
                                Environment environment) {
        this.ownership = ownership;
        this.siteConfig = siteConfig;
        this.cipher = cipher;
        this.layout = layout;
        this.siteFilesMaxBytes = siteFilesMaxBytes;
        this.environment = environment;
    }

    /** Test form without the running environment: app-level secrets answer empty. */
    public PluginScopedServices(PluginOwnership ownership, SitePluginConfigService siteConfig, SecretCipher cipher,
                                StorageLayout layout, long siteFilesMaxBytes) {
        this(ownership, siteConfig, cipher, layout, siteFilesMaxBytes, null);
    }

    /** Test form without the per-site files (the SPI 0.4.0 shape). */
    public PluginScopedServices(PluginOwnership ownership, SitePluginConfigService siteConfig, SecretCipher cipher) {
        this(ownership, siteConfig, cipher, null, PluginSiteFilesImpl.DEFAULT_MAX_WRITE_BYTES);
    }

    /** {@link PluginSettings} bound to the injecting plugin. */
    @Bean
    @Scope(BeanDefinition.SCOPE_PROTOTYPE)
    public PluginSettings pluginSettings(InjectionPoint injectionPoint) {
        LoadedPlugin plugin = owner(injectionPoint, PluginSettings.class,
                "kernel code reads per-site plugin settings through SitePluginConfigService");
        return settings.computeIfAbsent(plugin.id(), id -> new PluginSettingsImpl(id,
                plugin.descriptor().siteSettings(), plugin.descriptor().settings(),
                key -> environment == null ? null : environment.getProperty(key), key -> savedApp(id, key),
                siteConfig, cipher));
    }

    /**
     * One saved app-level value in its stored form; {@code null} when unset, without a layout, or when the file cannot
     * be read or parsed. A hand-edited file with a syntax error must not turn the plugin's check into an error page,
     * and the parser's message is not logged: it can quote the offending line, which may hold a password.
     */
    private String savedApp(String pluginId, String key) {
        if (layout == null) {
            return null;
        }
        try {
            return PluginSettingsService.saved(layout, pluginId).get(key);
        } catch (RuntimeException e) {
            log.warn("plugin '{}': its saved settings file could not be read ({})", pluginId,
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /** {@link PluginSiteFiles} bound to the injecting plugin's subtree {@code sites/<id>/plugins/<pluginId>/}. */
    @Bean
    @Scope(BeanDefinition.SCOPE_PROTOTYPE)
    public PluginSiteFiles pluginSiteFiles(InjectionPoint injectionPoint) {
        LoadedPlugin plugin = owner(injectionPoint, PluginSiteFiles.class,
                "kernel code reaches a site's files through SiteFileStore");
        if (layout == null) {
            throw new IllegalStateException("PluginSiteFiles is not available: no storage layout configured");
        }
        return siteFiles.computeIfAbsent(plugin.id(), id -> new PluginSiteFilesImpl(id, layout, siteFilesMaxBytes));
    }

    /**
     * {@link ContentChanges} bound to the injecting plugin: it may announce changes only for content sources that
     * plugin contributes. The source registry is resolved per call — a plugin's content source may itself depend on
     * this service, and the registry depends on every source.
     */
    @Bean
    @Scope(BeanDefinition.SCOPE_PROTOTYPE)
    public ContentChanges contentChanges(InjectionPoint injectionPoint, ApplicationEventPublisher events,
                                         ObjectProvider<ContentSourceRegistry> registry) {
        LoadedPlugin plugin = owner(injectionPoint, ContentChanges.class,
                "kernel code publishes ContentChangedEvent through its ApplicationEventPublisher");
        return contentChanges.computeIfAbsent(plugin.id(), id -> new ContentChangesImpl(id, registry::getObject,
                ownership::pluginIdOf, events));
    }

    /** {@link PluginInfo} of the injecting plugin. */
    @Bean
    @Scope(BeanDefinition.SCOPE_PROTOTYPE)
    public PluginInfo pluginInfo(InjectionPoint injectionPoint) {
        LoadedPlugin plugin = owner(injectionPoint, PluginInfo.class,
                "kernel code reads loaded plugins through RunningPlugins");
        return infos.computeIfAbsent(plugin.id(), id -> infoOf(plugin));
    }

    /** The {@link PluginInfo} the host describes {@code plugin} with (also handed to {@code PluginLifecycle}). */
    static PluginInfo infoOf(LoadedPlugin plugin) {
        return new PluginInfo(plugin.id(), plugin.descriptor().version(), plugin.installDir());
    }

    private LoadedPlugin owner(InjectionPoint injectionPoint, Class<?> service, String kernelAlternative) {
        Member member = injectionPoint.getMember();
        Class<?> site = member.getDeclaringClass();
        PluginOwnership.Owner owner = ownership.ownerOf(site);
        return switch (owner.kind()) {
            case PLUGIN -> owner.plugin();
            case LIBRARY -> throw new IllegalStateException(service.getSimpleName() + " is plugin-scoped, but "
                    + site.getName() + " is loaded from a lib/ jar (bundled by plugin '" + owner.plugin().id()
                    + "'). Library classes are shared by every plugin that bundles them, so they cannot be bound "
                    + "to one: inject " + service.getSimpleName() + " into a class from the plugin's own jar and "
                    + "pass it on.");
            case NONE -> throw new IllegalStateException(service.getSimpleName() + " is plugin-scoped and can "
                    + "only be injected into a class from a plugin's own jar; " + site.getName() + " is not one ("
                    + kernelAlternative + ").");
        };
    }
}
