package mx.quest.runway.console;

import java.nio.file.Path;
import java.util.Map;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.RunningPlugins;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.web.FileStampValidity;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.cache.ICacheEntryValidity;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.FileTemplateResolver;
import org.thymeleaf.templateresource.FileTemplateResource;
import org.thymeleaf.templateresource.ITemplateResource;

/**
 * Resolves a plugin-contributed template named {@code __plugin__/<id>/<name>} to the file
 * {@code <installDir>/templates/<name>.html} (with its paired {@code .th.xml}), so a plugin can ship dashboard
 * cards, admin-fragment panels, and any presentation-time templates from <b>its own namespace</b> — the plugin
 * self-containment rule (assets served from the plugin, never copied into tenants). The kernel knows only this
 * generic seam; there is zero plugin-specific code in the chrome.
 *
 * <p>Two safeguards, mirroring {@code SiteTemplateResolver}: the {@code __plugin__/<id>/} prefix makes the
 * cached template name plugin-unique (Thymeleaf caches by name), and the id must name a
 * <b>loaded</b> plugin while the name is confined under that plugin's {@code templates/} directory (traversal
 * can never escape it). Decoupled logic on, cache on (each parse valid while its files are unchanged); ordered
 * after the site (1) and operator (2) resolvers.
 */
public class PluginTemplateResolver extends FileTemplateResolver {

    /** The reserved name prefix for plugin templates. */
    public static final String PREFIX = "__plugin__/";

    private static final String TEMPLATES_DIR = "templates";
    private static final String SUFFIX = ".html";

    private final RunningPlugins runningPlugins;

    public PluginTemplateResolver(RunningPlugins runningPlugins) {
        this.runningPlugins = runningPlugins;
        setName("pluginTemplateResolver");
        setTemplateMode(TemplateMode.HTML);
        setUseDecoupledLogic(true);
        // Cache on; a parse stays valid only while its files are unchanged on disk (computeValidity). Plugin
        // templates are operator-editable install-dir files that no event evicts by name, and a virtual render's
        // ETag folds their bytes — so the parse must follow the file, not a timer (the former 30 s TTL served
        // the old parse under the new ETag for up to 30 s).
        setCacheable(true);
        setCharacterEncoding("UTF-8");
        setCheckExistence(true);
        setOrder(3);
    }

    @Override
    protected boolean computeResolvable(IEngineConfiguration configuration, String ownerTemplate,
            String template, Map<String, Object> templateResolutionAttributes) {
        return template != null && template.startsWith(PREFIX);
    }

    @Override
    protected ITemplateResource computeTemplateResource(IEngineConfiguration configuration, String ownerTemplate,
            String template, String resourceName, String characterEncoding,
            Map<String, Object> templateResolutionAttributes) {
        return new FileTemplateResource(resolveFile(template).toString(), characterEncoding);
    }

    /** A cached parse stays valid while its {@code .html}/{@code .th.xml} are unchanged ({@link FileStampValidity}). */
    @Override
    protected ICacheEntryValidity computeValidity(IEngineConfiguration configuration, String ownerTemplate,
            String template, Map<String, Object> templateResolutionAttributes) {
        return FileStampValidity.forTemplate(resolveFile(template));
    }

    /** Maps {@code __plugin__/<id>/<name>} to the absolute {@code .html} file under the loaded plugin's dir. */
    private Path resolveFile(String template) {
        String rest = template.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new IllegalArgumentException("template name must be " + PREFIX + "<id>/<name>: " + template);
        }
        String pluginId = rest.substring(0, slash);
        String name = rest.substring(slash + 1);
        Path installDir = installDirOf(pluginId);
        return PathSafety.resolveConfined(installDir.resolve(TEMPLATES_DIR), name + SUFFIX);
    }

    /** The install dir of a currently-loaded plugin; only a running plugin can contribute a template. */
    private Path installDirOf(String pluginId) {
        for (LoadedPlugin plugin : runningPlugins.installed()) {
            if (plugin.id().equals(pluginId)) {
                return plugin.installDir();
            }
        }
        throw new IllegalArgumentException("no loaded plugin '" + pluginId + "' to resolve its template");
    }
}
