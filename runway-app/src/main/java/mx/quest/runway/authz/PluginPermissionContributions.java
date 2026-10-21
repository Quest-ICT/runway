package mx.quest.runway.authz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginRuntime;
import mx.quest.runway.security.Roles;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.PathSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Extracts the grantable permissions a plugin declares from the loaded plugins, <b>namespaced</b>
 * by plugin id ({@code <pluginId>.<declId>}) so two plugins both declaring (say) {@code settings} never collide.
 * A plugin contributes <b>permissions, never roles</b>; {@code defaultRoles} are validated here: a
 * reserved master/wildcard-chain key is a <b>hard fail</b> (a plugin must not default a permission to an admin
 * tier), while an unknown or cross-realm role is ignored with a warning. The {@link PermissionCatalog} merges
 * the result; {@code mayAccess} and the bounded-grant admin service are untouched (they key off the catalog by
 * string), so a plugin permission lights up authorization for free.
 */
@Component
public class PluginPermissionContributions {

    private static final Logger log = LoggerFactory.getLogger(PluginPermissionContributions.class);

    private final List<Permission> grantable;
    private final Map<String, Set<String>> defaultsByRole;

    /** Spring entry point — reads the plugins the initializer loaded. */
    @Autowired
    public PluginPermissionContributions(ObjectProvider<PluginRuntime> runtimeProvider) {
        this(runtimeProvider.getIfAvailable(() -> PluginRuntime.empty(
                PluginPermissionContributions.class.getClassLoader())).loaded());
    }

    /** Core logic over an explicit loaded-plugin list (testable without a Spring context). */
    public PluginPermissionContributions(List<LoadedPlugin> loaded) {
        List<Permission> permissions = new ArrayList<>();
        Map<String, Set<String>> defaults = new LinkedHashMap<>();
        for (LoadedPlugin plugin : loaded) {
            for (var decl : plugin.descriptor().permissions()) {
                // both parts are safe ids (validated in the descriptor), so the '|' separator — which a safe id
                // can never contain — makes <pluginId>|<declId> injective (no two plugins can collide on a key).
                String key = PathSafety.compoundKey(plugin.id(), decl.id());
                collectDefaultRoles(plugin.id(), key, decl.realm(), decl.defaultRoles(), defaults);
                permissions.add(new Permission(key, decl.realm()));
            }
        }
        this.grantable = List.copyOf(permissions);
        defaults.replaceAll((role, keys) -> Set.copyOf(keys));
        this.defaultsByRole = Map.copyOf(defaults);
    }

    /** The namespaced, grantable plugin permissions to merge into the catalog. */
    public List<Permission> grantable() {
        return grantable;
    }

    /**
     * Role key → the plugin-permission keys that default to it ({@code defaultRoles}). The fold in
     * {@link AuthorizationService#effectivePermissionKeys} grants these to a role unless a tenancy's
     * {@code roleRevokes} delta removes them. Only <b>valid</b> defaults are captured (a reserved tier is a
     * hard error at construction; an unknown/cross-realm role is dropped) — so this never names an admin tier.
     */
    public Map<String, Set<String>> defaultsByRole() {
        return defaultsByRole;
    }

    private static void collectDefaultRoles(String pluginId, String key, Realm realm, List<String> defaultRoles,
                                            Map<String, Set<String>> into) {
        for (String role : defaultRoles) {
            if (ReservedRoles.isReserved(role)) {
                throw new IllegalStateException("plugin '" + pluginId + "' permission '" + key
                        + "' defaults to reserved role '" + role + "' — a plugin may not grant an admin tier");
            }
            if (isSeedRoleFor(role, realm)) {
                into.computeIfAbsent(role, k -> new LinkedHashSet<>()).add(key);
            } else {
                log.warn("plugin '{}' permission '{}' defaultRoles names unknown/cross-realm role '{}' — ignoring",
                        pluginId, key, role);
            }
        }
    }

    private static boolean isSeedRoleFor(String role, Realm realm) {
        return realm == Realm.APPLICATION ? Roles.SYSTEM_OPERATOR.equals(role) : Roles.OPERATOR.equals(role);
    }
}
