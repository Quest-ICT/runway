package mx.quest.runway.authz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.RunwaySecurityProperties;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.User;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The mayAccess precedence: realm match, fail-closed, master/wildcard short-circuits, and effective resolution. */
class AuthorizationServiceTest {

    private record Wiring(AuthorizationService authz, RoleStore roles, GrantStore grants) {}

    private static Wiring wire(Path tmp, String appMasterId) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        RoleStore roles = new RoleStore(layout, yaml);
        GrantStore grants = new GrantStore(layout, yaml);
        RunwaySecurityProperties security = new RunwaySecurityProperties();
        security.setAppMasterId(appMasterId);
        return new Wiring(new AuthorizationService(roles, grants, new PermissionCatalog(), security), roles, grants);
    }

    private static RunwayUserDetails user(String id, Realm realm, boolean master, List<String> sites, String... roles) {
        return new RunwayUserDetails(new User(id, id, "$h", "", "", true, master, realm, Set.of(roles), sites));
    }

    // --- gate conditions ---------------------------------------------------------------------

    @Test
    void aPermissionOfTheWrongRealmIsDenied(@TempDir Path tmp) {
        Wiring w = wire(tmp, "app-master");
        // An application user can never exercise a TENANT permission.
        assertFalse(w.authz().mayAccess(user("a", Realm.APPLICATION, false, List.of(), Roles.SYSTEM_ADMIN),
                Permissions.EDIT_PAGES, null));
    }

    @Test
    void aTenantUserCannotExerciseAnApplicationPermission(@TempDir Path tmp) {
        Wiring w = wire(tmp, "app-master");
        // The mirror of the above (guarding against an inverted realm check): even a tenant SUPERADMIN wildcard
        // is denied an APPLICATION-realm permission — authenticating in the tenant realm never grants the app one.
        RunwayUserDetails tenantBoss = user("boss", Realm.TENANT, true, List.of("acme"), Roles.SUPERADMIN);
        assertFalse(w.authz().mayAccess(tenantBoss, Permissions.MANAGE_APP_USERS, "acme"),
                "a tenant superadmin cannot exercise an application-realm permission");
        assertFalse(w.authz().mayAccess(tenantBoss, Permissions.MANAGE_APP_USERS, null),
                "…nor with no tenancy bound");
    }

    @Test
    void aTenantPermissionWithNoResolvedTenancyIsDenied(@TempDir Path tmp) {
        Wiring w = wire(tmp, "app-master");
        // No bound tenancy (tenancyId == null): even a tenant wildcard cannot pass — and the store is never
        // probed with a null id, so this denies cleanly instead of throwing.
        assertFalse(w.authz().mayAccess(user("ad", Realm.TENANT, false, List.of(), Roles.ADMIN),
                Permissions.EDIT_PAGES, null));
    }

    @Test
    void anUndeclaredPermissionIsDenied(@TempDir Path tmp) {
        Wiring w = wire(tmp, "app-master");
        Permission madeUp = new Permission("TOTALLY_MADE_UP", Realm.APPLICATION);
        // Even the wildcard cannot pass an undeclared permission.
        assertFalse(w.authz().mayAccess(user("a", Realm.APPLICATION, false, List.of(), Roles.SYSTEM_ADMIN), madeUp, null));
    }

    // --- the precedence ----------------------------------------------------------------------

    @Test
    void theApplicationMasterPassesEverything(@TempDir Path tmp) {
        Wiring w = wire(tmp, "app-master");
        RunwayUserDetails master = user("app-master", Realm.APPLICATION, true, List.of(), Roles.SYSTEM_SUPERADMIN);
        assertTrue(w.authz().mayAccess(master, Permissions.MANAGE_TENANCIES, null), "even master/wildcard-only");
        assertTrue(w.authz().mayAccess(master, Permissions.VIEW_AUDIT, null));
    }

    @Test
    void aWildcardAdminPassesEverythingIncludingPrivileged(@TempDir Path tmp) {
        Wiring w = wire(tmp, "app-master");
        RunwayUserDetails admin = user("ad", Realm.APPLICATION, false, List.of(), Roles.SYSTEM_ADMIN);
        assertTrue(w.authz().mayAccess(admin, Permissions.MANAGE_TENANCIES, null), "privileged passes by wildcard");
        assertTrue(w.authz().mayAccess(admin, Permissions.VIEW_AUDIT, null));
    }

    @Test
    void anOrdinaryUserNeedsTheConcretePermission(@TempDir Path tmp) {
        Wiring w = wire(tmp, "app-master");
        RunwayUserDetails operator = user("op", Realm.APPLICATION, false, List.of(), Roles.SYSTEM_OPERATOR);
        // SYSTEMOPERATOR is a seed role with no permissions and is not in the role file → nothing granted.
        assertFalse(w.authz().mayAccess(operator, Permissions.VIEW_AUDIT, null));
        assertFalse(w.authz().mayAccess(operator, Permissions.MANAGE_TENANCIES, null), "never grantable to ordinary users");
    }

    @Test
    void theTenantMasterShortCircuitIsTenancyScoped(@TempDir Path tmp) {
        Wiring w = wire(tmp, "app-master");
        // A tenant-master assigned to acme only.
        RunwayUserDetails boss = user("boss", Realm.TENANT, true, List.of("acme"), Roles.SUPERADMIN);
        assertTrue(w.authz().mayAccess(boss, Permissions.EDIT_PAGES, "acme"), "master of acme");
        assertFalse(w.authz().mayAccess(boss, Permissions.EDIT_PAGES, "beta"), "not master of beta — no short-circuit");
    }

    // --- effective resolution (roles + closure + direct grants + override deltas) ------------

    @Test
    void aCustomRoleGrantsViaTheEffectiveSet(@TempDir Path tmp) {
        Wiring w = wire(tmp, "app-master");
        w.roles().saveSiteRoles("acme", List.of(
                new Role("viewer", Realm.TENANT, Set.of("EDIT_PAGES"), Set.of()),
                new Role("lead", Realm.TENANT, Set.of("CONFIGURE_SITE"), Set.of("viewer"))));
        // A sub-user carrying the "lead" role gets lead's own + inherited (viewer) permissions.
        RunwayUserDetails sub = user("sub", Realm.TENANT, false, List.of(), "lead");
        assertTrue(w.authz().mayAccess(sub, Permissions.EDIT_PAGES, "acme"), "inherited from viewer");
        assertTrue(w.authz().mayAccess(sub, Permissions.CONFIGURE_SITE, "acme"));
        assertFalse(w.authz().mayAccess(sub, Permissions.MANAGE_USERS, "acme"), "not granted");
    }

    @Test
    void directGrantsAndPerTenancyRoleAssignmentsApply(@TempDir Path tmp) {
        Wiring w = wire(tmp, "app-master");
        w.roles().saveSiteRoles("acme", List.of(new Role("editor", Realm.TENANT, Set.of("EDIT_PAGES"), Set.of())));
        // permissions.yml: assign "editor" to sub-1 and grant MANAGE_DOCUMENTS directly to sub-1.
        w.grants().saveSiteGrants("acme", new GrantFile(
                Map.of("sub-1", Set.of("editor")), Map.of("sub-1", Set.of("MANAGE_DOCUMENTS"))));
        RunwayUserDetails sub = user("sub-1", Realm.TENANT, false, List.of()); // no roles on the record itself

        assertTrue(w.authz().mayAccess(sub, Permissions.EDIT_PAGES, "acme"), "role assigned via permissions.yml");
        assertTrue(w.authz().mayAccess(sub, Permissions.MANAGE_DOCUMENTS, "acme"), "direct user grant");
        assertFalse(w.authz().mayAccess(sub, Permissions.DELETE_USERS, "acme"));
    }
}
