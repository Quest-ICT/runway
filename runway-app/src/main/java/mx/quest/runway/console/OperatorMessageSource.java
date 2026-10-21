package mx.quest.runway.console;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import mx.quest.runway.i18n.I18nProperties;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginRuntime;
import mx.quest.runway.storage.StorageLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.stereotype.Component;

/**
 * The operator console's own i18n bundle — entirely separate from the per-tenant {@code SiteAwareMessageSource},
 * so an operator request never resolves a {@code #{}} key against a tenant's site strings. The classpath bundle
 * ({@code i18n/operator-messages[_xx].properties}, shipped in the jar) is the default; an external override
 * ({@code external/i18n/operator-messages[_xx].properties}) takes precedence, so an operator can re-translate
 * or correct the console copy without a rebuild. Reloadable on the same cadence as tenant i18n
 * ({@code runway.i18n.cache-seconds}).
 *
 * <p><b>Plugin bundles.</b> Each loaded plugin that ships an {@code i18n/messages[_xx].properties} bundle in its
 * install folder contributes it to the console — appended <b>last</b>, so a plugin may <b>add</b> keys (its own
 * permission / settings / menu / card labels, rendered by the generic console via {@code #{...}}) but can
 * <b>never override</b> a core key (basenames are consulted in order; the first match wins, and core comes
 * first). Two plugins declaring the same key resolve deterministically by load order. This is the i18n arm of
 * the plugin resource seams (alongside {@code /__plugin/<id>/static/**} and the {@code __plugin__/<id>}
 * templates): a plugin's strings live <b>with the plugin</b>, never in core.
 */
@Component
public class OperatorMessageSource extends ReloadableResourceBundleMessageSource {

    private static final Logger log = LoggerFactory.getLogger(OperatorMessageSource.class);

    public OperatorMessageSource(StorageLayout layout, I18nProperties properties,
                                 ObjectProvider<PluginRuntime> pluginRuntime) {
        List<String> basenames = new ArrayList<>();
        // CORE first (the first basename that has a code wins): external override, then the classpath default.
        basenames.add("file:" + layout.external().resolve("i18n").resolve("operator-messages"));
        basenames.add("classpath:i18n/operator-messages");
        // PLUGINS last — add-only, never overriding core.
        appendPluginBundles(basenames, pluginRuntime);

        setBasenames(basenames.toArray(String[]::new));
        setDefaultEncoding("UTF-8");
        setCacheMillis(Math.max(0, properties.getCacheSeconds()) * 1000L);
        setFallbackToSystemLocale(false);   // an unset locale falls back to the default bundle, not the host's
        setUseCodeAsDefaultMessage(true);   // a missing key renders the code, never throws mid-render
    }

    private static void appendPluginBundles(List<String> basenames, ObjectProvider<PluginRuntime> pluginRuntime) {
        PluginRuntime runtime = pluginRuntime.getIfAvailable(
                () -> PluginRuntime.empty(OperatorMessageSource.class.getClassLoader()));
        int added = 0;
        for (LoadedPlugin plugin : runtime.loaded()) {
            Path i18nDir = plugin.installDir().resolve("i18n");
            if (Files.isDirectory(i18nDir)) {
                basenames.add("file:" + i18nDir.resolve("messages"));
                added++;
            }
        }
        if (added > 0) {
            log.info("appended {} plugin i18n bundle(s) to the operator message source", added);
        }
    }
}
