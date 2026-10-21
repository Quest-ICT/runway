package mx.quest.runway.plugin;

import java.util.List;
import mx.quest.runway.spi.model.Realm;

/**
 * A plugin-declared grantable permission — <b>realm-tagged, never a role</b>. The kernel merges
 * these into its {@code PermissionCatalog} as ordinary grantable permissions, so a plugin permission
 * lights up the {@code mayAccess} engine and the bounded-grant admin surface with no host change. The reserved
 * id {@code settings} gates a plugin's per-tenant configuration editor.
 *
 * @param id           the permission key (unique within the realm; merged into the catalog)
 * @param realm        the realm it applies to ({@code application} | {@code tenant}) — required
 * @param label        an i18n label key for the admin UI
 * @param defaultRoles predefined-role keys granted this permission by default (validated at load:
 *                     a reserved master/wildcard key is a hard error; an unknown/cross-realm key is ignored)
 */
public record PluginPermissionDecl(String id, Realm realm, String label, List<String> defaultRoles) {

    public PluginPermissionDecl {
        defaultRoles = defaultRoles == null ? List.of() : List.copyOf(defaultRoles);
    }
}
