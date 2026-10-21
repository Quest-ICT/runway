package mx.quest.runway.storage;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The resolved, absolute, normalized storage paths derived from {@link StorageProperties}. This is
 * the kernel's single source of truth for "where does X live on disk", and the only place site
 * directories are resolved (always through {@link PathSafety}, so a site id can never escape the
 * sites root).
 */
public final class StorageLayout {

    private final Path external;
    private final Path sites;
    private final Path backups;
    private final Path plugins;

    private StorageLayout(Path external, Path sites, Path backups, Path plugins) {
        this.external = external;
        this.sites = sites;
        this.backups = backups;
        this.plugins = plugins;
    }

    /** Derives the layout from properties, applying the documented defaults (plugins under {@code <external>}). */
    public static StorageLayout from(StorageProperties props) {
        return from(props, null);
    }

    /**
     * Derives the layout from properties plus the {@code runway.plugins.root} override.
     *
     * @param pluginsRoot the configured {@code runway.plugins.root}; {@code null} or blank means the default
     *                    {@code <external>/plugins}. Resolved by {@link #resolvePluginsRoot}, the same rule the
     *                    plugin loader applies, so the console installs exactly where the loader discovers.
     */
    public static StorageLayout from(StorageProperties props, String pluginsRoot) {
        Objects.requireNonNull(props, "props");
        Path external = abs(props.getExternal());
        Path sites = props.getSites() != null ? abs(props.getSites()) : external.resolve("sites");
        Path backups = props.getBackups() != null ? abs(props.getBackups()) : abs(Path.of("backups"));
        return new StorageLayout(external, sites, backups, resolvePluginsRoot(pluginsRoot, external));
    }

    /**
     * THE rule for where plugins live, shared by the loader ({@code PluginContextInitializer}, which runs before
     * any bean exists) and by this layout (which the console's install / uninstall / enable / disable use): the
     * configured {@code runway.plugins.root} when non-blank, else {@code <external>/plugins}. Before the rule was
     * shared, a configured root moved discovery but not the console, which kept installing where nothing loaded.
     *
     * @param explicit the configured root, or {@code null}/blank for the default
     * @param external the external folder (relative or absolute)
     * @return the absolute, normalized plugins root
     */
    public static Path resolvePluginsRoot(String explicit, Path external) {
        if (explicit != null && !explicit.isBlank()) {
            return abs(Path.of(explicit.trim()));
        }
        return abs(external).resolve("plugins");
    }

    private static Path abs(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /** The external folder root. */
    public Path external() {
        return external;
    }

    /** The root holding all per-site folders. */
    public Path sites() {
        return sites;
    }

    /** The backups directory. */
    public Path backups() {
        return backups;
    }

    /**
     * The folder for one site, resolved traversal-safely under {@link #sites()}.
     *
     * @throws IllegalArgumentException if {@code siteId} is unsafe or would escape the sites root
     */
    public Path siteDir(String siteId) {
        return PathSafety.resolveWithin(sites, siteId);
    }

    /**
     * The root holding all plugin install folders: {@code runway.plugins.root} when set, else
     * {@code <external>/plugins} ({@link #resolvePluginsRoot}) — always the folder the loader discovers from.
     */
    public Path plugins() {
        return plugins;
    }

    /**
     * The install folder for one plugin, resolved traversal-safely under {@link #plugins()}.
     *
     * @throws IllegalArgumentException if {@code pluginId} is unsafe or would escape the plugins root
     */
    public Path pluginDir(String pluginId) {
        return PathSafety.resolveWithin(plugins(), pluginId);
    }

    /**
     * The directory holding the UI-owned app-level plugin settings files, <b>outside</b> the install dir so they
     * survive upgrade/uninstall ({@code external/plugin-settings}).
     */
    public Path pluginSettings() {
        return external.resolve("plugin-settings");
    }

    /** The app-level settings file for one plugin ({@code external/plugin-settings/<id>.yml}), traversal-safe. */
    public Path pluginSettingsFile(String pluginId) {
        return PathSafety.resolveWithin(pluginSettings(), pluginId + ".yml");
    }

    /**
     * The app-level IP blocklist file ({@code external/blocklist.yml}), holding the operator's <b>manual</b> IP/CIDR
     * blocks so they survive a restart. This is a <b>platform</b> abuse-defense concern (never per-tenant); the file
     * lives at the external root, outside {@code sites/}, so it never travels in any tenant's backup. Auto-escalated
     * blocks are ephemeral and are never written here (see the protection plugin's {@code IpBlocklist}).
     */
    public Path blocklistFile() {
        return external.resolve("blocklist.yml");
    }

    /**
     * The directory holding a tenancy's per-plugin config files ({@code sites/<id>/plugins}). It lives
     * <b>inside</b> the tenant folder, so this plugin-GENERATED tenant data travels in the tenant's backup
     * (the self-containment rule: plugin code/assets belong to the plugin, plugin-generated tenant data to the
     * tenant). Traversal-safe in {@code siteId}.
     */
    public Path sitePluginConfigDir(String siteId) {
        return siteDir(siteId).resolve("plugins");
    }

    /** A tenancy's per-tenant config file for one plugin ({@code sites/<id>/plugins/<plugin>.yml}), traversal-safe. */
    public Path sitePluginConfigFile(String siteId, String pluginId) {
        return PathSafety.resolveWithin(sitePluginConfigDir(siteId), pluginId + ".yml");
    }

    /**
     * A tenancy's per-instance content-widget config file ({@code sites/<id>/widget-instances.yml}). One file per
     * site holds every placed widget instance's configuration, keyed by the anchor's {@code <type>/<instance>}
     * pair (WIDGETS: the store the site manager's <b>Content widgets</b> screen writes and
     * {@link mx.quest.runway.widget.WidgetModelBuilder} reads). It lives <b>inside</b> the tenant folder, so this
     * plugin-GENERATED tenant data travels in the tenant's backup (the self-containment rule). Traversal-safe in
     * {@code siteId}.
     */
    public Path widgetInstancesFile(String siteId) {
        return siteDir(siteId).resolve("widget-instances.yml");
    }

    /**
     * The operator-editable seed-template folder ({@code external/seed/site}) holding overrides of the starter
     * presentation copied into every <b>newly created</b> site. A file present here shadows the platform default
     * baked into the classpath; absent, the classpath default is used (see the app-realm <b>Site templates</b>
     * screen and the seeder). App-level platform config — never a tenant folder — so it lives at the external root,
     * outside {@code sites/}, and never travels in any tenant's backup.
     */
    public Path seedSiteDir() {
        return external.resolve("seed").resolve("site");
    }

    /**
     * Root of the <b>named seed-template catalog</b> ({@code external/seed/site-templates}). Each subfolder is one
     * template a new site can be born from; the reserved {@code default} template falls back to the classpath.
     * Supersedes {@link #seedSiteDir()} (whose contents migrate into {@code site-templates/default} on first run).
     */
    public Path seedTemplatesDir() {
        return external.resolve("seed").resolve("site-templates");
    }

    /**
     * The folder for one named seed template, resolved traversal-safely under {@link #seedTemplatesDir()}.
     *
     * @throws IllegalArgumentException if {@code templateId} is unsafe or would escape the catalog root
     */
    public Path seedTemplateDir(String templateId) {
        return PathSafety.resolveWithin(seedTemplatesDir(), templateId);
    }
}
