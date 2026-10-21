package mx.quest.runway.authz;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.spi.model.Realm;
import org.junit.jupiter.api.Test;

/** Plugin-declared permissions merge into the catalog namespaced; collisions and reserved-role defaults fail. */
class PluginPermissionMergeTest {

    private static LoadedPlugin plugin(String id, String permissionsYaml) {
        String yaml = "id: " + id + "\nversion: 1.0.0\n" + permissionsYaml;
        PluginDescriptor descriptor = PluginDescriptor.read(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        return new LoadedPlugin(descriptor, Path.of("/tmp/plugins", id));
    }

    private static PluginPermissionContributions contributions(LoadedPlugin... plugins) {
        return new PluginPermissionContributions(List.of(plugins));
    }

    @Test
    void pluginGrantablePermissionsMergeNamespacedAndAreGrantable() {
        PermissionCatalog catalog = new PermissionCatalog(contributions(plugin("contact", """
                permissions:
                  - { id: settings, realm: tenant, label: contact.perm.settings, defaultRoles: [OPERATOR] }
                  - { id: dashboard, realm: application, label: contact.perm.dashboard }
                """)));
        assertTrue(catalog.isDeclared("contact|settings"), "namespaced by plugin id");
        assertTrue(catalog.find("contact|settings").isPresent());
        assertTrue(catalog.find("contact|settings").get().realm() == Realm.TENANT);
        assertTrue(catalog.isGrantable(catalog.find("contact|settings").get()), "plugin permissions are grantable");
        assertTrue(catalog.isDeclared("contact|dashboard"));
        // the kernel baseline is still intact alongside the plugin's
        assertTrue(catalog.isDeclared("EDIT_PAGES"));
    }

    @Test
    void aReservedDefaultRoleIsAHardFail() {
        assertThrows(IllegalStateException.class, () -> contributions(plugin("evil", """
                permissions:
                  - { id: x, realm: tenant, label: l, defaultRoles: [ADMIN] }
                """)), "a plugin may not default a permission to the wildcard admin tier");
    }

    @Test
    void anUnknownOrCrossRealmDefaultRoleIsIgnoredNotFatal() {
        // SYSTEM_OPERATOR is an application role; on a TENANT permission it is cross-realm — ignored, not fatal.
        PermissionCatalog catalog = new PermissionCatalog(contributions(plugin("forms", """
                permissions:
                  - { id: edit, realm: tenant, label: l, defaultRoles: [SYSTEM_OPERATOR, MADE_UP] }
                """)));
        assertTrue(catalog.isDeclared("forms|edit"), "the permission still merges; bad default roles are ignored");
    }

    @Test
    void aCollidingKeyFailsStartup() {
        // Two declarations with the same id in one plugin namespace to the same key — caught at merge.
        assertThrows(IllegalStateException.class, () -> new PermissionCatalog(contributions(plugin("dup", """
                permissions:
                  - { id: settings, realm: tenant, label: a }
                  - { id: settings, realm: tenant, label: b }
                """))));
    }
}
