package mx.quest.runway.authz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginPermissionDecl;
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
import org.springframework.security.access.AccessDeniedException;

/**
 * The role-permission deltas: a plugin permission defaults to a seed role, and a scope can grant it
 * to other roles or revoke it from the default — stored as deltas so a plugin default change propagates where
 * not overridden. Covers both the {@code mayAccess} fold and the bounded-grant admin ops. (Plugin defaults may
 * only target the OPERATOR seed role; the wildcard tier ADMIN passes everything by short-circuit, so a custom
 * role is used for the "ordinary non-default" cases.)
 */
class RoleDeltaTest {

    private static final String WIDGETS = "demo|widgets"; // a tenant permission defaulting to OPERATOR

    private record World(AuthorizationService authz, AuthorizationAdminService admin, RoleStore roles,
                         GrantStore grants, PermissionCatalog catalog) {}

    private static World world(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        RoleStore roles = new RoleStore(layout, yaml);
        GrantStore grants = new GrantStore(layout, yaml);
        RunwaySecurityProperties security = new RunwaySecurityProperties();
        security.setAppMasterId("app-master");

        PluginDescriptor descriptor = new PluginDescriptor("demo", "Demo", "1.0.0", null, null, null,
                null, null,
                List.of(new PluginPermissionDecl("widgets", Realm.TENANT, "demo.widgets", List.of(Roles.OPERATOR))),
                List.of(), List.of(), java.util.Map.of(), List.of(), List.of(), null);
        PermissionCatalog catalog = new PermissionCatalog(
                new PluginPermissionContributions(List.of(new LoadedPlugin(descriptor, tmp))));

        AuthorizationService authz = new AuthorizationService(roles, grants, catalog, security);
        mx.quest.runway.security.UserStore users = new mx.quest.runway.security.UserStore(yaml);
        mx.quest.runway.security.AppUserStore appUsers =
                new mx.quest.runway.security.AppUserStore(layout, users, security);
        mx.quest.runway.security.SiteUserStore siteUsers =
                new mx.quest.runway.security.SiteUserStore(layout, users);
        AuthorizationAdminService admin =
                new AuthorizationAdminService(roles, grants, catalog, authz, security, appUsers, siteUsers);
        return new World(authz, admin, roles, grants, catalog);
    }

    private static RunwayUserDetails user(String id, boolean master, List<String> sites, String... roles) {
        return new RunwayUserDetails(new User(id, id, "$h", "", "", true, master, Realm.TENANT, Set.of(roles), sites));
    }

    private Permission widgets(World world) {
        return world.catalog().find(WIDGETS).orElseThrow();
    }

    @Test
    void aDeclaredDefaultGrantsThePermissionToThatRole(@TempDir Path tmp) {
        World world = world(tmp);
        world.roles().saveSiteRoles("acme", List.of(new Role("viewer", Realm.TENANT, Set.of(), Set.of())));
        assertTrue(world.authz().mayAccess(user("op", false, List.of(), Roles.OPERATOR), widgets(world), "acme"),
                "OPERATOR holds it by default");
        assertFalse(world.authz().mayAccess(user("v", false, List.of(), "viewer"), widgets(world), "acme"),
                "a custom role is not a default holder");
    }

    @Test
    void aRoleRevokeDeltaRemovesADefault(@TempDir Path tmp) {
        World world = world(tmp);
        world.grants().saveSiteGrants("acme", GrantFile.empty().withRoleRevoke(Roles.OPERATOR, WIDGETS));
        assertFalse(world.authz().mayAccess(user("op", false, List.of(), Roles.OPERATOR), widgets(world), "acme"),
                "the revoke delta removed the default");
    }

    @Test
    void aRoleGrantDeltaAddsToANonDefaultRole(@TempDir Path tmp) {
        World world = world(tmp);
        world.roles().saveSiteRoles("acme", List.of(new Role("viewer", Realm.TENANT, Set.of(), Set.of())));
        world.grants().saveSiteGrants("acme", GrantFile.empty().withRoleGrant("viewer", WIDGETS));
        assertTrue(world.authz().mayAccess(user("v", false, List.of(), "viewer"), widgets(world), "acme"),
                "the grant delta added it to the custom role");
    }

    @Test
    void theDeltaFoldRespectsCustomRoleInheritance(@TempDir Path tmp) {
        World world = world(tmp);
        // 'lead' inherits 'viewer'; a grant delta on 'viewer' must reach a 'lead' user through the closure.
        world.roles().saveSiteRoles("acme", List.of(
                new Role("viewer", Realm.TENANT, Set.of(), Set.of()),
                new Role("lead", Realm.TENANT, Set.of(), Set.of("viewer"))));
        world.grants().saveSiteGrants("acme", GrantFile.empty().withRoleGrant("viewer", WIDGETS));
        assertTrue(world.authz().mayAccess(user("sub", false, List.of(), "lead"), widgets(world), "acme"),
                "the delta flows through the role-name closure");
    }

    @Test
    void adminGrantToRoleAndRevokeFromRoleAreDeltaCorrect(@TempDir Path tmp) {
        World world = world(tmp);
        world.roles().saveSiteRoles("acme", List.of(new Role("viewer", Realm.TENANT, Set.of(), Set.of())));
        RunwayUserDetails boss = user("boss", true, List.of("acme"), Roles.SUPERADMIN); // tenant master of acme

        world.admin().grantToRole(boss, "acme", "viewer", widgets(world)); // non-default role → grant delta
        assertTrue(world.authz().mayAccess(user("v", false, List.of(), "viewer"), widgets(world), "acme"));

        world.admin().revokeFromRole(boss, "acme", Roles.OPERATOR, widgets(world)); // default → revoke delta
        assertFalse(world.authz().mayAccess(user("op", false, List.of(), Roles.OPERATOR), widgets(world), "acme"));
    }

    @Test
    void aPermissionStoredOnTheRoleInTheRolesFileCanBeRevokedAndRegranted(@TempDir Path tmp) {
        World world = world(tmp);
        // A key typed into the roles file (the console never writes it there) — until 1.0.0 it could not be revoked.
        world.roles().saveSiteRoles("acme", List.of(new Role("viewer", Realm.TENANT, Set.of(WIDGETS), Set.of()),
                new Role("lead", Realm.TENANT, Set.of(), Set.of("viewer"))));
        RunwayUserDetails boss = user("boss", true, List.of("acme"), Roles.SUPERADMIN);
        RunwayUserDetails viewer = user("v", false, List.of(), "viewer");
        assertTrue(world.authz().mayAccess(viewer, widgets(world), "acme"), "stored on the role");
        assertTrue(world.authz().effectiveRolePermissionKeys(Realm.TENANT, "acme", "viewer").contains(WIDGETS),
                "the matrix shows it ticked");

        world.admin().revokeFromRole(boss, "acme", "viewer", widgets(world));
        assertFalse(world.authz().mayAccess(viewer, widgets(world), "acme"), "the console revoke takes it away");
        assertFalse(world.authz().mayAccess(user("l", false, List.of(), "lead"), widgets(world), "acme"),
                "from a role inheriting it too");
        assertFalse(world.authz().effectiveRolePermissionKeys(Realm.TENANT, "acme", "viewer").contains(WIDGETS),
                "and the matrix shows it unticked");

        world.admin().grantToRole(boss, "acme", "viewer", widgets(world));
        assertTrue(world.authz().mayAccess(viewer, widgets(world), "acme"), "granting it again clears the revoke");
        assertTrue(world.grants().siteGrants("acme").roleRevokesOf("viewer").isEmpty(), "no stale revoke is left");
    }

    @Test
    void aRevokeWinsOverAGrantOfTheSameKeyOnTheSameRole(@TempDir Path tmp) {
        World world = world(tmp);
        world.roles().saveSiteRoles("acme", List.of(new Role("viewer", Realm.TENANT, Set.of(), Set.of())));
        // A hand-edited grant file naming WIDGETS in BOTH deltas of 'viewer' must resolve fail-closed (denied).
        world.grants().saveSiteGrants("acme", GrantFile.empty()
                .withRoleGrant("viewer", WIDGETS).withRoleRevoke("viewer", WIDGETS));
        assertFalse(world.authz().mayAccess(user("v", false, List.of(), "viewer"), widgets(world), "acme"),
                "deny (revoke) wins over grant on the same role");
    }

    @Test
    void aCrossRealmPermissionOrReservedRoleIsRejected(@TempDir Path tmp) {
        World world = world(tmp);
        RunwayUserDetails boss = user("boss", true, List.of("acme"), Roles.SUPERADMIN); // tenant master of acme
        // A tenant admin must not write an APPLICATION permission key into the tenant grant store.
        assertThrows(IllegalArgumentException.class,
                () -> world.admin().grantPermission(boss, "acme", "sub", new Permission("VIEW_APP_DASHBOARD", Realm.APPLICATION)));
        // ...nor the OTHER realm's reserved (wildcard) role key.
        assertThrows(IllegalArgumentException.class,
                () -> world.admin().assignRole(boss, "acme", "sub", Roles.SYSTEM_ADMIN));
    }

    @Test
    void roleDeltasAreGatedAndValidated(@TempDir Path tmp) {
        World world = world(tmp);
        RunwayUserDetails plain = user("op", false, List.of(), Roles.OPERATOR); // not a master/wildcard
        assertThrows(AccessDeniedException.class,
                () -> world.admin().grantToRole(plain, "acme", "viewer", widgets(world)),
                "only administrators may manage role deltas");

        RunwayUserDetails boss = user("boss", true, List.of("acme"), Roles.SUPERADMIN);
        assertThrows(IllegalArgumentException.class,
                () -> world.admin().grantToRole(boss, "acme", Roles.SUPERADMIN, widgets(world)),
                "a delta on an admin tier is inert and rejected");
        assertThrows(IllegalArgumentException.class,
                () -> world.admin().grantToRole(boss, "acme", "viewer",
                        new Permission("MANAGE_TENANCIES", Realm.TENANT)),
                "a master/wildcard-only permission may never sit on a role");
    }
}
