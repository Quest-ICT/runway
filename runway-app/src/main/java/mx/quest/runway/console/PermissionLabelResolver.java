package mx.quest.runway.console;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginRuntime;
import mx.quest.runway.storage.PathSafety;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Resolves a grantable-permission <b>key</b> to a human display <b>label</b> for the permissions matrix. The
 * label key is the permission's declared i18n key — a plugin permission's {@code plugin.yml} {@code label}, or
 * for a core permission the {@code op.perm.<KEY>} convention — resolved through the operator console's
 * {@link OperatorMessageSource} (which already carries plugin bundles). An unresolved key falls back to the
 * raw permission key, so the matrix is always readable even for a plugin that ships no i18n bundle.
 *
 * <p>Called directly from the matrix template ({@code ${@permissionLabelResolver.label(p.key(), #locale)}}),
 * so no controller wiring changes.
 */
@Component("permissionLabelResolver")
public class PermissionLabelResolver {

    private final OperatorMessageSource messages;
    private final Map<String, String> pluginLabelKeys; // permission key -> plugin.yml label (message) key

    public PermissionLabelResolver(OperatorMessageSource messages, ObjectProvider<PluginRuntime> pluginRuntime) {
        this.messages = messages;
        this.pluginLabelKeys = buildPluginLabelKeys(pluginRuntime);
    }

    private static Map<String, String> buildPluginLabelKeys(ObjectProvider<PluginRuntime> pluginRuntime) {
        PluginRuntime runtime = pluginRuntime.getIfAvailable(
                () -> PluginRuntime.empty(PermissionLabelResolver.class.getClassLoader()));
        Map<String, String> map = new HashMap<>();
        for (LoadedPlugin plugin : runtime.loaded()) {
            for (var decl : plugin.descriptor().permissions()) {
                if (decl.label() != null && !decl.label().isBlank()) {
                    map.put(PathSafety.compoundKey(plugin.id(), decl.id()), decl.label());
                }
            }
        }
        return Map.copyOf(map);
    }

    /** The resolved display label for one permission key (falls back to the key itself). */
    public String label(String permissionKey, Locale locale) {
        String labelKey = pluginLabelKeys.getOrDefault(permissionKey, "op.perm." + permissionKey);
        return messages.getMessage(labelKey, null, permissionKey, locale);
    }

    /** The resolved, comma-joined labels for a role's held permission keys (an em-dash when none). */
    public String joinHeld(List<String> heldKeys, Locale locale) {
        if (heldKeys == null || heldKeys.isEmpty()) {
            return "—";
        }
        return heldKeys.stream().map(key -> label(key, locale)).collect(Collectors.joining(", "));
    }
}
