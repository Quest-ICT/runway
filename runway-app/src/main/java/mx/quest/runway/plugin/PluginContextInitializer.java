package mx.quest.runway.plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import mx.quest.runway.storage.PathSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * The pivot of the loader (registered via {@code META-INF/spring.factories}): it runs after the {@code Environment}
 * is prepared but <b>before</b> {@code refresh()}, so plugin {@code @Configuration} beans wire in the <b>same</b>
 * refresh pass as the kernel's — controllers, filters, security chains and SPI impls all become first-class
 * peers. There is no second wiring pass; that is why install is restart-only.
 *
 * <p>Order: (1) <b>skip entirely in WIZARD mode</b> — when no application master is pinned there are no tenants
 * or app users to protect, so plugins must not load (a pre-placed essential plugin firing pre-setup would be
 * incoherent); (2) discover; refuse quarantined plugins ({@link PluginQuarantine}) and plugins whose own jars link
 * kernel internals or need SPI types this kernel lacks ({@link HostLinkageScanner}); resolve + topo-order (the
 * resolver also applies the SPI floor rules, including the 1.0 mandatory floor); (3) build the shared classloader
 * and check each {@code mainClass} against the surface rules ({@link PluginSurfaceCheck}) — a violation blocks the
 * plugin, and the resolution runs again so a plugin requiring it is blocked too; install the classloader on the
 * context and bean factory; (4) register each {@code mainClass} as {@code plugin:<id>}, <b>each registration
 * isolated</b> so one bad plugin is logged and skipped while the platform still boots; (5) publish a
 * {@link PluginRuntime} singleton for {@link PluginManager} to pick up after refresh.
 *
 * <p>Every refusal here is a <b>Blocked</b> plugin with a reason (logged, and shown on the plugins screen) — never a
 * boot failure; the platform comes up with the plugins that comply. The essential plugin is exempt from the scanner
 * and the surface check; if it is blocked by any other rule the fail-closed floor engages.
 * What the before-refresh check cannot see (component-scanned beans) is checked after refresh by
 * {@link PluginCompliance}, which quarantines the plugin instead.
 */
public class PluginContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(PluginContextInitializer.class);

    static final String ROOT_PROPERTY = "runway.plugins.root";
    static final String EXTERNAL_PROPERTY = "runway.storage.external";
    static final String APP_MASTER_PROPERTY = "runway.security.app-master-id";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        Environment env = context.getEnvironment();

        String appMaster = env.getProperty(APP_MASTER_PROPERTY);
        if (appMaster == null || appMaster.isBlank()) {
            log.info("WIZARD mode (no {}) — skipping plugin discovery", APP_MASTER_PROPERTY);
            publish(context, PluginRuntime.empty(context.getClassLoader()));
            return;
        }

        Path root = pluginsRoot(env);
        java.util.Set<String> installedIds = PluginLoader.installedPluginIds(root);
        loadPluginSettings(context, Path.of(env.getProperty(EXTERNAL_PROPERTY, "external"), "plugin-settings"),
                installedIds);

        List<LoadedPlugin> discovered = PluginLoader.discover(root);
        if (discovered.isEmpty()) {
            // Nothing loadable — but an installed essential plugin (e.g. present-but-disabled) still fails closed.
            boolean essentialFailed = computeEssentialFailed(installedIds, List.of(), List.of(), Map.of());
            publish(context, new PluginRuntime(List.of(), context.getClassLoader(), java.util.Map.of(), essentialFailed));
            return;
        }
        warnIfDevtoolsClassLoader(context.getClassLoader());

        // Refusals decided before resolution (they cascade through requires: like any other block): a quarantine
        // marker left by a boot this plugin broke, and SPI types the plugin needs that this kernel lacks.
        Path external = Path.of(env.getProperty(EXTERNAL_PROPERTY, "external"));
        Map<String, String> preBlocked = new LinkedHashMap<>(PluginQuarantine.blocked(external, discovered));
        scanLinkage(discovered, context.getClassLoader(), preBlocked, new java.util.HashSet<>(), true);

        PluginResolution.Result resolution = PluginResolution.resolve(discovered, preBlocked);
        List<LoadedPlugin> ordered = PluginLoader.order(resolution.loadable());
        ClassLoader pluginClassLoader = PluginLoader.classLoaderFor(ordered, context.getClassLoader());

        // Surface rules, before refresh: bean names and Spring surface. It needs the plugin classes, hence the loader built for
        // the loadable set; a violation is one more refusal, and resolving again cascades it through requires:. The
        // loadable set only shrinks, so one more resolution is enough (the check of a plugin does not depend on which
        // others load: a plugin whose dependency was refused is blocked by the cascade, whatever its own check said).
        Map<String, String> surface = surfaceRefusals(ordered, pluginClassLoader);
        if (!surface.isEmpty()) {
            surface.forEach(preBlocked::putIfAbsent);
            resolution = PluginResolution.resolve(discovered, preBlocked);
            ordered = PluginLoader.order(resolution.loadable());
            closeQuietly(pluginClassLoader, context.getClassLoader());
            pluginClassLoader = PluginLoader.classLoaderFor(ordered, context.getClassLoader());
        }
        for (Map.Entry<String, String> blocked : resolution.blocked().entrySet()) {
            log.warn("plugin '{}' is blocked: {}", blocked.getKey(), blocked.getValue());
        }
        context.setClassLoader(pluginClassLoader);
        beanFactory.setBeanClassLoader(pluginClassLoader);

        List<LoadedPlugin> registered = new ArrayList<>();
        if (beanFactory instanceof BeanDefinitionRegistry registry) {
            AnnotatedBeanDefinitionReader reader = new AnnotatedBeanDefinitionReader(registry);
            for (LoadedPlugin plugin : ordered) {
                if (plugin.isLibraryOnly()) {
                    registered.add(plugin); // counts as installed; contributes no @Configuration
                    continue;
                }
                try {
                    Class<?> mainClass = Class.forName(plugin.descriptor().mainClass(), false, pluginClassLoader);
                    // Registered under a host-chosen name, so the mainClass can never collide with a kernel bean
                    // that happens to share its simple class name.
                    reader.registerBean(mainClass, PluginSurfaceCheck.mainBeanName(plugin.id()));
                    registered.add(plugin);
                } catch (Throwable t) { // isolate: one bad plugin must not stop the platform booting
                    log.warn("skipping plugin '{}': could not register mainClass {} ({})",
                            plugin.id(), plugin.descriptor().mainClass(), t.toString());
                }
            }
        } else {
            log.warn("bean factory is not a BeanDefinitionRegistry — cannot register plugin configurations");
        }
        log.info("plugins: {} registered, {} blocked", registered.size(), resolution.blocked().size());
        boolean essentialFailed = computeEssentialFailed(installedIds, discovered, registered, resolution.blocked());
        publish(context, new PluginRuntime(registered, pluginClassLoader, resolution.blocked(), essentialFailed));
    }

    /**
     * The surface rules' before-refresh check over every non-essential, code-carrying plugin in {@code ordered}: plugin id →
     * the operator-readable reason it is refused (only plugins with a violation). A {@code mainClass} that cannot be
     * loaded is left to registration, which skips it with its own message. A check that cannot inspect the plugin
     * (an annotation naming a class that is not there, say) refuses it: an unchecked plugin is exactly what the rule
     * exists to keep out.
     */
    static Map<String, String> surfaceRefusals(List<LoadedPlugin> ordered, ClassLoader pluginClassLoader) {
        Map<String, String> refused = new LinkedHashMap<>();
        for (LoadedPlugin plugin : ordered) {
            if (plugin.isLibraryOnly() || EssentialAllowlist.contains(plugin.id())) {
                continue; // nothing to check / the essential plugin is exempt
            }
            Class<?> mainClass;
            try {
                mainClass = Class.forName(plugin.descriptor().mainClass(), false, pluginClassLoader);
            } catch (ClassNotFoundException | LinkageError unloadable) {
                continue;
            }
            List<String> problems;
            try {
                problems = PluginSurfaceCheck.beforeRefresh(plugin.id(), mainClass);
            } catch (RuntimeException | LinkageError failure) {
                problems = List.of("its configuration could not be checked (" + failure + ")");
            }
            if (!problems.isEmpty()) {
                refused.put(plugin.id(), surfaceReason(problems));
            }
        }
        return refused;
    }

    /**
     * {@link #surfaceRefusals} through a class loader built for {@code loadable} alone and closed afterwards — the
     * plugins screen's next-boot preview, which must not keep plugin classes loaded.
     */
    static Map<String, String> surfaceRefusalsInThrowawayLoader(List<LoadedPlugin> loadable, ClassLoader parent) {
        List<LoadedPlugin> ordered = PluginLoader.order(loadable);
        ClassLoader loader = PluginLoader.classLoaderFor(ordered, parent);
        try {
            return surfaceRefusals(ordered, loader);
        } finally {
            closeQuietly(loader, parent);
        }
    }

    /**
     * Path, size and modification time of every jar (own and {@code lib/}) of {@code plugins}, in a stable order — what
     * a surface-check result depends on, so a remembered result is reused only while none of them changed.
     */
    static List<String> jarFingerprint(List<LoadedPlugin> plugins) {
        List<String> out = new ArrayList<>();
        for (LoadedPlugin plugin : plugins) {
            out.add(plugin.id() + "@" + plugin.descriptor().version() + "|" + plugin.descriptor().mainClass());
            for (Path dir : List.of(plugin.installDir(), plugin.installDir().resolve("lib"))) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> jars = Files.list(dir)) {
                    for (Path jar : jars.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                        out.add(jar + "|" + Files.size(jar) + "|" + Files.getLastModifiedTime(jar).toMillis());
                    }
                } catch (IOException unreadable) {
                    out.add(dir + "|unreadable");
                }
            }
        }
        return List.copyOf(out);
    }

    /** The one-line block reason for surface-rule violations (shared with the after-refresh quarantine). */
    static String surfaceReason(List<String> problems) {
        return "breaks the plugin rules for Runway's shared Spring context (SPI 1.0): " + String.join("; ", problems)
                + ". Install a release of this plugin that follows them.";
    }

    private static void closeQuietly(ClassLoader loader, ClassLoader parent) {
        if (loader != parent && loader instanceof java.io.Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // a throwaway inspection loader; nothing to do
            }
        }
    }

    /**
     * Runs the {@link HostLinkageScanner} over every non-essential plugin: SPI types this kernel lacks, host linkage
     * and classes under {@code mx.quest.runway} are each a pre-resolution block ({@code preBlocked}, id → reason).
     *
     * @param linksHost receives the id of every plugin whose own jars link kernel internals (the plugins screen's
     *                  "uses host internals" badge)
     * @param log       whether to log each finding — the boot does; the plugins screen's preview does not, or every
     *                  visit to it would repeat them
     */
    static void scanLinkage(List<LoadedPlugin> discovered, ClassLoader kernelLoader, Map<String, String> preBlocked,
                            Set<String> linksHost, boolean log) {
        java.util.function.Predicate<String> spi = HostLinkageScanner.spiOf(kernelLoader);
        for (LoadedPlugin plugin : discovered) {
            if (EssentialAllowlist.contains(plugin.id())) {
                continue; // the essential plugin ships with the kernel and is exempt
            }
            HostLinkageScanner.Report report = HostLinkageScanner.scan(plugin, spi);
            if (report.linksHost()) {
                linksHost.add(plugin.id());
            }
            String reason = report.blockReason();
            if (reason != null) {
                preBlocked.putIfAbsent(plugin.id(), reason);
                if (log) {
                    PluginContextInitializer.log.warn("plugin '{}' linkage scan: {}", plugin.id(), reason);
                }
            }
        }
    }

    /**
     * Whether an allowlisted-essential plugin that is <b>physically present on disk</b> failed to register — the
     * fail-closed trigger (a broken security floor is worse than an absent one). Crucially it works off the RAW
     * folder set ({@code installedIds}, by directory name), not the filtered {@code discovered} list, so a
     * <b>malformed / disabled / blocked / threw</b> essential plugin is caught — any of which would otherwise
     * silently open the floor. A plugin self-marking {@code essential} without being on the allowlist is ignored
     * with a warning. Each failure is logged with {@link #essentialFailureMessage}, which says what to do.
     *
     * @param blocked the resolution's refusals (id → reason), so a blocked essential plugin's line carries the reason
     */
    private static boolean computeEssentialFailed(java.util.Set<String> installedIds, List<LoadedPlugin> discovered,
                                                  List<LoadedPlugin> registered, Map<String, String> blocked) {
        java.util.Set<String> registeredIds = new java.util.HashSet<>();
        registered.forEach(plugin -> registeredIds.add(plugin.id()));
        for (LoadedPlugin plugin : discovered) {
            if (plugin.descriptor().isEssential() && !EssentialAllowlist.contains(plugin.id())) {
                log.warn("plugin '{}' declares essential: true but is not on the security-floor allowlist — ignored",
                        plugin.id());
            }
        }
        boolean failed = false;
        for (String installedId : installedIds) {
            if (EssentialAllowlist.contains(installedId) && !registeredIds.contains(installedId)) {
                log.error(essentialFailureMessage(installedId, blocked.get(installedId)));
                failed = true;
            }
        }
        return failed;
    }

    /**
     * The fail-closed log line for an essential plugin that is installed but did not load, written for the operator
     * who finds every tenant page answering {@code 503}: what happened, why, and what to do. A <b>blocked</b> plugin's
     * line carries its refusal reason, which already ends with the fix (for a descriptor refusal, the
     * {@link EssentialAllowlist#repairAdvice}; for a quarantine, its own). Any other failure — disabled, malformed, or
     * its configuration could not be registered — gets the repair advice: the copy bundled with the running release
     * fixes each of them (an upgrade that left an older copy behind is the usual cause).
     *
     * @param id            the essential plugin's id
     * @param blockedReason its refusal reason, or {@code null} when it was not refused by resolution
     */
    static String essentialFailureMessage(String id, String blockedReason) {
        String head = "essential plugin '" + id + "' is installed but failed to load — entering fail-closed degraded "
                + "mode (tenant pages answer 503). ";
        if (blockedReason != null) {
            return head + "Blocked: " + blockedReason;
        }
        return head + "It is disabled (enabled: false), its plugin.yml is malformed, or its configuration could not "
                + "be registered — see the lines above. " + EssentialAllowlist.repairAdvice(id);
    }

    /**
     * Each context's runtime, kept beside the singleton: a failed refresh destroys every singleton — the
     * registered {@link PluginRuntime} included — before Spring Boot tells its run listeners, and the wiring
     * quarantine needs the runtime exactly then. Weak keys, so a closed context is not retained.
     */
    private static final java.util.Map<ConfigurableApplicationContext, PluginRuntime> RUNTIMES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static void publish(ConfigurableApplicationContext context, PluginRuntime runtime) {
        context.getBeanFactory().registerSingleton(PluginRuntime.BEAN_NAME, runtime);
        RUNTIMES.put(context, runtime);
    }

    /** The runtime this initializer published for {@code context}, even after a failed refresh destroyed it. */
    static java.util.Optional<PluginRuntime> runtimeOf(ConfigurableApplicationContext context) {
        return java.util.Optional.ofNullable(context == null ? null : RUNTIMES.get(context));
    }

    /**
     * Loads each {@code external/plugin-settings/<id>.yml} (UI-owned, outside the install dir so it survives
     * upgrade/uninstall) as a property source <b>above</b> the application config but <b>below</b>
     * system properties / env / CLI — so a UI-saved value masks a stray same-prefix key yet never overrides an
     * operator's command-line override. Each plugin owns a disjoint key prefix, so their relative order is moot.
     * Only files named for an installed plugin load, and only their own prefix ({@link #confinedTo}).
     */
    private static void loadPluginSettings(ConfigurableApplicationContext context, Path settingsDir,
                                           java.util.Set<String> installedIds) {
        if (!Files.isDirectory(settingsDir)) {
            return;
        }
        if (isWorldWritable(settingsDir)) {
            // These files become Spring property sources ABOVE the config files — a world-writable dir would let
            // any local user inject arbitrary properties (datasource, security, …) into the next restart. Refuse.
            log.error("plugin-settings dir {} is world-writable — refusing to load it as property sources "
                    + "(fix its permissions to owner/group-only)", settingsDir);
            return;
        }
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = new ArrayList<>();
        try (Stream<Path> files = Files.list(settingsDir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                String base = file.getFileName().toString();
                String id = base.substring(0, base.length() - ".yml".length());
                if (!PathSafety.isSafeId(id)) {
                    // The filename is the plugin id; a name that isn't a safe id was not written by the UI.
                    log.warn("ignoring plugin-settings file with an unsafe name: {}", base);
                    continue;
                }
                if (!installedIds.contains(id)) {
                    // Not an installed plugin's settings: a plugin's own data kept in this folder (protection's
                    // protection-app.yml) or a removed plugin's file. Its content is no configuration of anyone's;
                    // until 1.0.0 it was loaded anyway, its keys (an encrypted CAPTCHA secret among them) included.
                    log.debug("not loading plugin-settings/{} as configuration: no installed plugin '{}'", base, id);
                    continue;
                }
                for (PropertySource<?> loaded : loader.load("plugin-settings:" + base, new FileSystemResource(file))) {
                    sources.add(confinedTo(id, loaded));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("reading plugin settings under " + settingsDir, e);
        }
        MutablePropertySources propertySources = context.getEnvironment().getPropertySources();
        String anchor = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        for (PropertySource<?> source : sources) {
            if (propertySources.contains(anchor)) {
                propertySources.addAfter(anchor, source);
            } else {
                propertySources.addLast(source);
            }
        }
        if (!sources.isEmpty()) {
            log.info("loaded {} plugin-settings property source(s)", sources.size());
        }
    }

    /**
     * The part of a plugin's settings file under its own {@code runway.plugin.<id>.} prefix: the only keys the
     * console writes there. Anything else is dropped with a warning naming the keys.
     *
     * <h2>Why</h2>
     * These sources rank above {@code application.yml}. Loaded whole, a hand-edited file could set any property,
     * {@code runway.security.*} or {@code server.*} included, from a file whose name says it holds one plugin's
     * settings. Until 1.0.0 it was loaded whole.
     *
     * <p>Text is kept literal ({@link PlaceholderText}): a <code>${</code> in a value is never resolved, and the plugin
     * reads exactly the text that was saved (so the form shows no false pending restart). A plugin that
     * binds its settings would otherwise expand <code>${runway.security.secret-key}</code> typed into a settings
     * field and could publish it (in robots.txt, a header, a page).
     */
    static PropertySource<?> confinedTo(String pluginId, PropertySource<?> loaded) {
        String prefix = "runway.plugin." + pluginId + ".";
        Map<String, Object> kept = new LinkedHashMap<>();
        List<String> dropped = new ArrayList<>();
        if (loaded instanceof org.springframework.core.env.EnumerablePropertySource<?> enumerable) {
            for (String name : enumerable.getPropertyNames()) {
                if (name.startsWith(prefix)) {
                    kept.put(name, literal(enumerable.getProperty(name)));
                } else {
                    dropped.add(name);
                }
            }
        }
        if (!dropped.isEmpty()) {
            log.warn("plugin-settings/{}.yml: ignoring {} key(s) outside {}: {}", pluginId, dropped.size(),
                    prefix + "*", dropped);
        }
        return new org.springframework.core.env.MapPropertySource(loaded.getName(), kept);
    }

    /** {@code value} escaped to read as written when it is text ({@link PlaceholderText#literal}); else unchanged. */
    private static Object literal(Object value) {
        if (value instanceof CharSequence text && text.toString().contains("${")) {
            return PlaceholderText.literal(text.toString());
        }
        return value;
    }

    /** True if {@code dir} is world-writable on a POSIX filesystem. Best-effort: false where perms aren't POSIX. */
    private static boolean isWorldWritable(Path dir) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(dir);
            return perms.contains(PosixFilePermission.OTHERS_WRITE);
        } catch (UnsupportedOperationException | IOException notPosixOrUnreadable) {
            return false;
        }
    }

    /**
     * The folder discovery reads, resolved by {@link mx.quest.runway.storage.StorageLayout#resolvePluginsRoot} —
     * the same rule the {@code StorageLayout} bean (and so the console's plugin install/uninstall) uses.
     */
    static Path pluginsRoot(Environment env) {
        return mx.quest.runway.storage.StorageLayout.resolvePluginsRoot(env.getProperty(ROOT_PROPERTY),
                Path.of(env.getProperty(EXTERNAL_PROPERTY, "external")));
    }

    private static void warnIfDevtoolsClassLoader(ClassLoader classLoader) {
        if (classLoader != null && classLoader.getClass().getName().contains("RestartClassLoader")) {
            log.warn("running under the Spring DevTools restart classloader — plugin classes may not resolve; "
                    + "always test plugins against the packaged jar, never a devtools run");
        }
    }
}
