package mx.quest.runway.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Role persistence: round-trips, per-tenancy isolation, validate-on-save, and fail-closed-on-load. */
class RoleStoreTest {

    private static RoleStore store(Path tmp, YamlStore yaml) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        return new RoleStore(StorageLayout.from(props), yaml);
    }

    private static Role tenantRole(String name, String perm, String... inherits) {
        return new Role(name, Realm.TENANT, Set.of(perm), Set.of(inherits));
    }

    @Test
    void appRolesRoundTripThroughAGraph(@TempDir Path tmp) {
        RoleStore store = store(tmp, new YamlStore());
        store.saveAppRoles(List.of(
                new Role("auditor", Realm.APPLICATION, Set.of("VIEW_AUDIT"), Set.of()),
                new Role("ops", Realm.APPLICATION, Set.of("VIEW_BANDWIDTH"), Set.of("auditor"))));

        RoleGraph graph = store.appRoles();
        assertEquals(Set.of("VIEW_BANDWIDTH", "VIEW_AUDIT"), graph.effectivePermissionKeys("ops"));
    }

    @Test
    void siteRolesAreIsolatedPerTenancy(@TempDir Path tmp) {
        RoleStore store = store(tmp, new YamlStore());
        store.saveSiteRoles("acme", List.of(tenantRole("editor", "EDIT_PAGES")));
        store.saveSiteRoles("beta", List.of(tenantRole("reviewer", "APPROVE_PAGES")));

        assertTrue(store.siteRoles("acme").hasRole("editor"));
        assertTrue(store.siteRoles("acme").effectivePermissionKeys("reviewer").isEmpty(), "beta's role is invisible to acme");
        assertTrue(store.siteRoles("beta").hasRole("reviewer"));
    }

    @Test
    void saveRejectsAnInvalidGraph(@TempDir Path tmp) {
        RoleStore store = store(tmp, new YamlStore());
        assertThrows(IllegalStateException.class, () -> store.saveSiteRoles("acme",
                List.of(tenantRole("a", "EDIT_PAGES", "b"), tenantRole("b", "MANAGE_USERS", "a")))); // cycle
    }

    @Test
    void aHandEditedInvalidFileFailsClosedOnLoad(@TempDir Path tmp) throws IOException {
        YamlStore yaml = new YamlStore();
        RoleStore store = store(tmp, yaml);
        // Write a cyclic file directly, bypassing the save-time validation (simulating a tamper).
        Path file = StorageLayout.from(propsFor(tmp)).siteDir("acme").resolve("roles.yml");
        yaml.write(file, new RoleFile(List.of(tenantRole("x", "EDIT_PAGES", "y"), tenantRole("y", "MANAGE_USERS", "x"))));

        assertThrows(IllegalStateException.class, () -> store.siteRoles("acme"));
    }

    private static StorageProperties propsFor(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        return props;
    }
}
