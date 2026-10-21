package mx.quest.runway.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
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
import org.springframework.security.access.AccessDeniedException;

/** The bounded-grant rules: no self-escalation, reserved-key limits, grantable-only, master-target. */
class AuthorizationAdminServiceTest {

    private record Wiring(AuthorizationAdminService admin, AuthorizationService authz, RoleStore roles,
                          mx.quest.runway.security.SiteUserStore siteUsers, GrantStore grants) {}

    private static Wiring wire(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        return wire(layout, new GrantStore(layout, new YamlStore()));
    }

    /** A grants store whose next site write fails, as a full disk or a permission problem would make it. */
    private static final class FailingGrantStore extends GrantStore {
        boolean failNextWrite;

        FailingGrantStore(StorageLayout layout) {
            super(layout, new YamlStore());
        }

        @Override
        public void saveSiteGrants(String siteId, GrantFile grants) {
            failIfAsked();
            super.saveSiteGrants(siteId, grants);
        }

        /** The console's changes go through the atomic update: fail it as a full disk fails its write. */
        @Override
        public GrantFile updateSiteGrants(String siteId, java.util.function.UnaryOperator<GrantFile> change) {
            failIfAsked();
            return super.updateSiteGrants(siteId, change);
        }

        private void failIfAsked() {
            if (failNextWrite) {
                failNextWrite = false;
                throw new java.io.UncheckedIOException(new java.io.IOException("No space left on device"));
            }
        }
    }

    private static Wiring wire(StorageLayout layout, GrantStore grants) {
        YamlStore yaml = new YamlStore();
        RoleStore roles = new RoleStore(layout, yaml);
        PermissionCatalog catalog = new PermissionCatalog();
        RunwaySecurityProperties security = new RunwaySecurityProperties();
        security.setAppMasterId("app-master");
        AuthorizationService authz = new AuthorizationService(roles, grants, catalog, security);
        mx.quest.runway.security.UserStore users = new mx.quest.runway.security.UserStore(yaml);
        mx.quest.runway.security.AppUserStore appUsers =
                new mx.quest.runway.security.AppUserStore(layout, users, security);
        mx.quest.runway.security.SiteUserStore siteUsers =
                new mx.quest.runway.security.SiteUserStore(layout, users);
        return new Wiring(new AuthorizationAdminService(roles, grants, catalog, authz, security, appUsers, siteUsers),
                authz, roles, siteUsers, grants);
    }

    private static RunwayUserDetails user(String id, Realm realm, boolean master, List<String> sites, String... roles) {
        return new RunwayUserDetails(new User(id, id, "$h", "", "", true, master, realm, Set.of(roles), sites));
    }

    private static RunwayUserDetails tenantWildcard() {
        return user("ad", Realm.TENANT, false, List.of(), Roles.ADMIN);
    }

    @Test
    void anAdminCanDefineARoleAndAssignItEndToEnd(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.admin().saveRole(tenantWildcard(), "acme", new Role("editor", Realm.TENANT, Set.of("EDIT_PAGES"), Set.of()));
        w.admin().assignRole(tenantWildcard(), "acme", "sub", "editor");

        RunwayUserDetails sub = user("sub", Realm.TENANT, false, List.of());
        assertTrue(w.authz().mayAccess(sub, Permissions.EDIT_PAGES, "acme"), "assigned role grants the permission");
    }

    @Test
    void revokingAGrantFileRoleWorksButAnAccessTierIsRejectedNotSilentlyNoOped(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.admin().saveRole(tenantWildcard(), "acme", new Role("blogger", Realm.TENANT, Set.of("EDIT_PAGES"), Set.of()));
        // A sub-user created with OPERATOR in its OWN record (the access tier), plus a matrix-assigned custom role.
        w.siteUsers().save("acme", new User("sub", "sub", "$h", "", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));
        w.admin().assignRole(tenantWildcard(), "acme", "sub", "blogger"); // grant-file layer

        // Revoking the grant-file custom role works.
        w.admin().revokeRole(tenantWildcard(), "acme", "sub", "blogger");
        RunwayUserDetails after = user("sub", Realm.TENANT, false, List.of(), Roles.OPERATOR);
        assertFalse(w.authz().mayAccess(after, Permissions.EDIT_PAGES, "acme"), "the custom role was revoked");

        // Revoking the access tier (held in the user's own record, not the grant file) is REJECTED with a clear
        // message — the old behaviour silently no-oped because the grant-file delete found nothing to remove.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> w.admin().revokeRole(tenantWildcard(), "acme", "sub", Roles.OPERATOR));
        assertTrue(ex.getMessage().contains("access tier"), "explains why, was: " + ex.getMessage());
    }

    @Test
    void effectiveRolePermissionKeysGivesTheWildcardEverythingAndAConcreteRoleItsOwn(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.admin().saveRole(tenantWildcard(), "acme", new Role("editor", Realm.TENANT, Set.of("EDIT_PAGES"), Set.of()));

        Set<String> wildcard = w.authz().effectiveRolePermissionKeys(Realm.TENANT, "acme", Roles.ADMIN);
        assertTrue(wildcard.contains(Permissions.EDIT_PAGES.key()) && wildcard.contains(Permissions.MANAGE_USERS.key()),
                "the wildcard tier effectively holds every grantable permission (not an empty row)");

        Set<String> operator = w.authz().effectiveRolePermissionKeys(Realm.TENANT, "acme", Roles.OPERATOR);
        assertTrue(operator.isEmpty(), "the seed OPERATOR role starts blank — no core or plugin permissions");

        Set<String> editor = w.authz().effectiveRolePermissionKeys(Realm.TENANT, "acme", "editor");
        assertEquals(Set.of(Permissions.EDIT_PAGES.key()), editor, "a custom role holds exactly its stored permission");
    }

    @Test
    void aNonAdminCannotManageRoles(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        RunwayUserDetails operator = user("op", Realm.TENANT, false, List.of(), Roles.OPERATOR);
        assertThrows(AccessDeniedException.class, () -> w.admin().saveRole(operator, "acme",
                new Role("x", Realm.TENANT, Set.of("EDIT_PAGES"), Set.of())));
    }

    @Test
    void noSelfEscalation(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.admin().saveRole(tenantWildcard(), "acme", new Role("editor", Realm.TENANT, Set.of("EDIT_PAGES"), Set.of()));
        w.admin().saveRole(tenantWildcard(), "acme", new Role("boss", Realm.TENANT, Set.of("MANAGE_USERS"), Set.of()));

        // An "editor"-holder may grant "editor" (they hold it) but not "boss" (they do not).
        RunwayUserDetails editorHolder = user("e", Realm.TENANT, false, List.of(), "editor");
        w.admin().assignRole(editorHolder, "acme", "t", "editor"); // ok
        assertThrows(AccessDeniedException.class, () -> w.admin().assignRole(editorHolder, "acme", "t", "boss"));
        // …and may grant a permission they hold, but not one they do not.
        w.admin().grantPermission(editorHolder, "acme", "t", Permissions.EDIT_PAGES); // ok
        assertThrows(AccessDeniedException.class,
                () -> w.admin().grantPermission(editorHolder, "acme", "t", Permissions.MANAGE_USERS));
    }

    @Test
    void theWildcardKeyIsGrantableOnlyByAdministrators(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        RunwayUserDetails operator = user("op", Realm.TENANT, false, List.of(), Roles.OPERATOR);
        assertThrows(AccessDeniedException.class, () -> w.admin().assignRole(operator, "acme", "t", Roles.ADMIN));
        w.admin().assignRole(tenantWildcard(), "acme", "t", Roles.ADMIN); // a wildcard may grant the wildcard key
    }

    @Test
    void theMasterKeyIsNeverGrantable(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        // Even a tenant master cannot hand out the master key.
        RunwayUserDetails master = user("boss", Realm.TENANT, true, List.of("acme"), Roles.SUPERADMIN);
        assertThrows(AccessDeniedException.class, () -> w.admin().assignRole(master, "acme", "t", Roles.SUPERADMIN));
    }

    @Test
    void privilegedPermissionsAreNeverGrantableAndRolesRejectThem(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        RunwayUserDetails appAdmin = user("ad", Realm.APPLICATION, false, List.of(), Roles.SYSTEM_ADMIN);
        // master/wildcard-only permission can't be granted, even by a wildcard.
        assertThrows(AccessDeniedException.class,
                () -> w.admin().grantPermission(appAdmin, null, "t", Permissions.MANAGE_TENANCIES));
        // …nor placed into a custom role.
        assertThrows(IllegalArgumentException.class, () -> w.admin().saveRole(appAdmin, null,
                new Role("superops", Realm.APPLICATION, Set.of("MANAGE_TENANCIES"), Set.of())));
    }

    @Test
    void reservedRoleNamesAndMasterTargetsAreRejected(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        RunwayUserDetails appAdmin = user("ad", Realm.APPLICATION, false, List.of(), Roles.SYSTEM_ADMIN);
        assertThrows(IllegalArgumentException.class, () -> w.admin().saveRole(appAdmin, null,
                new Role(Roles.SYSTEM_ADMIN, Realm.APPLICATION, Set.of(), Set.of())));
        // The application master's authorization cannot be reassigned.
        assertThrows(AccessDeniedException.class,
                () -> w.admin().grantPermission(appAdmin, null, "app-master", Permissions.VIEW_AUDIT));
    }

    // --- create / delete / DAG hierarchy -----------------------------------------

    @Test
    void createRolePersistsANewRole(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.admin().createRole(tenantWildcard(), "acme", "editor", Set.of("EDIT_PAGES"), Set.of());
        assertTrue(w.roles().siteRoles("acme").hasRole("editor"), "the new role is in the graph");
        assertTrue(w.roles().siteRoles("acme").effectivePermissionKeys("editor").contains("EDIT_PAGES"));
    }

    @Test
    void createRoleRejectsADuplicateName(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.admin().createRole(tenantWildcard(), "acme", "editor", Set.of(), Set.of());
        assertThrows(IllegalArgumentException.class,
                () -> w.admin().createRole(tenantWildcard(), "acme", "editor", Set.of(), Set.of()));
    }

    @Test
    void createRoleRejectsAReservedOrPredefinedName(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        assertThrows(IllegalArgumentException.class,
                () -> w.admin().createRole(tenantWildcard(), "acme", Roles.ADMIN, Set.of(), Set.of()));
        // A seeded predefined role name (OPERATOR) is also taken.
        assertThrows(IllegalArgumentException.class,
                () -> w.admin().createRole(tenantWildcard(), "acme", Roles.OPERATOR, Set.of(), Set.of()));
    }

    @Test
    void createRoleRejectsAMalformedName(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        assertThrows(IllegalArgumentException.class,
                () -> w.admin().createRole(tenantWildcard(), "acme", "bad name/../x", Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> w.admin().createRole(tenantWildcard(), "acme", "  ", Set.of(), Set.of()));
    }

    @Test
    void deleteRoleRemovesACustomRole(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.admin().createRole(tenantWildcard(), "acme", "editor", Set.of(), Set.of());
        w.admin().deleteRole(tenantWildcard(), "acme", "editor");
        assertTrue(!w.roles().siteRoles("acme").hasRole("editor"), "the role is gone");
    }

    @Test
    void deletingARoleTakesItsPermissionsAwayFromItsHolders(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.admin().createRole(tenantWildcard(), "acme", "editor", Set.of("EDIT_PAGES"), Set.of());
        w.admin().grantToRole(tenantWildcard(), "acme", "editor", Permissions.MANAGE_DOCUMENTS);
        w.admin().assignRole(tenantWildcard(), "acme", "sub", "editor");
        RunwayUserDetails sub = user("sub", Realm.TENANT, false, List.of());
        assertTrue(w.authz().mayAccess(sub, Permissions.EDIT_PAGES, "acme"));
        assertTrue(w.authz().mayAccess(sub, Permissions.MANAGE_DOCUMENTS, "acme"));

        w.admin().deleteRole(tenantWildcard(), "acme", "editor");

        assertFalse(w.authz().mayAccess(sub, Permissions.EDIT_PAGES, "acme"), "the stored permission went with it");
        assertFalse(w.authz().mayAccess(sub, Permissions.MANAGE_DOCUMENTS, "acme"),
                "the permission granted to the role from the console went with it too");
        assertTrue(w.grants().siteGrants("acme").rolesOf("sub").isEmpty(), "no holder keeps the deleted name");
        w.admin().createRole(tenantWildcard(), "acme", "editor", Set.of(), Set.of());
        assertTrue(w.grants().siteGrants("acme").roleGrantsOf("editor").isEmpty(),
                "a role created later under the same name starts clean");
    }

    @Test
    void deleteRoleRejectsAReservedRole(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        assertThrows(IllegalArgumentException.class,
                () -> w.admin().deleteRole(tenantWildcard(), "acme", Roles.ADMIN));
    }

    @Test
    void deleteRoleRejectsARoleStillInherited(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.admin().createRole(tenantWildcard(), "acme", "base", Set.of("EDIT_PAGES"), Set.of());
        w.admin().createRole(tenantWildcard(), "acme", "boss", Set.of(), Set.of("base"));
        w.admin().assignRole(tenantWildcard(), "acme", "sub", "base");
        // Deleting "base" would leave "boss" with a dangling inherit edge — the store rejects it.
        assertThrows(IllegalStateException.class,
                () -> w.admin().deleteRole(tenantWildcard(), "acme", "base"));
        assertEquals(Set.of("base"), w.grants().siteGrants("acme").rolesOf("sub"),
                "a refused delete leaves the role's holders as they were");
    }

    @Test
    void aDeleteThatFailsHalfwayNeverLeavesTheRolesGrantsLive(@TempDir Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        FailingGrantStore failing = new FailingGrantStore(layout);
        Wiring w = wire(layout, failing);
        w.admin().createRole(tenantWildcard(), "acme", "editor", Set.of("EDIT_PAGES"), Set.of());
        w.admin().grantToRole(tenantWildcard(), "acme", "editor", Permissions.MANAGE_DOCUMENTS);
        w.admin().assignRole(tenantWildcard(), "acme", "sub", "editor");
        RunwayUserDetails sub = user("sub", Realm.TENANT, false, List.of());

        failing.failNextWrite = true;
        assertThrows(java.io.UncheckedIOException.class,
                () -> w.admin().deleteRole(tenantWildcard(), "acme", "editor"));
        // The grants write failed first, so nothing changed: the role is still defined and can be deleted again.
        // (The other order removed the role and then failed, leaving the console grant live on its holder, with
        // no role left to delete.)
        assertTrue(w.roles().siteRoleList("acme").stream().anyMatch(role -> role.name().equals("editor")),
                "the role is still defined");
        assertTrue(w.authz().mayAccess(sub, Permissions.MANAGE_DOCUMENTS, "acme"), "the state before the delete");

        w.admin().deleteRole(tenantWildcard(), "acme", "editor");
        assertFalse(w.authz().mayAccess(sub, Permissions.MANAGE_DOCUMENTS, "acme"));
        assertFalse(w.authz().mayAccess(sub, Permissions.EDIT_PAGES, "acme"));
        assertFalse(w.roles().siteRoles("acme").hasRole("editor"));
    }

    @Test
    void addInheritEdgeComposesPermissionsDownward(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.admin().createRole(tenantWildcard(), "acme", "base", Set.of("EDIT_PAGES"), Set.of());
        w.admin().createRole(tenantWildcard(), "acme", "boss", Set.of("MANAGE_USERS"), Set.of());
        w.admin().addRoleInherit(tenantWildcard(), "acme", "boss", "base");
        Set<String> effective = w.roles().siteRoles("acme").effectivePermissionKeys("boss");
        assertTrue(effective.contains("MANAGE_USERS") && effective.contains("EDIT_PAGES"),
                "boss inherits base's permissions plus its own");
    }

    @Test
    void addInheritEdgeRejectsACycle(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.admin().createRole(tenantWildcard(), "acme", "a", Set.of(), Set.of());
        w.admin().createRole(tenantWildcard(), "acme", "b", Set.of(), Set.of("a")); // b → a
        // a → b would close the loop; the cycle-checked graph rejects it, and the store is left untouched.
        assertThrows(IllegalStateException.class, () -> w.admin().addRoleInherit(tenantWildcard(), "acme", "a", "b"));
        assertTrue(w.roles().siteRoles("acme").effectiveRoleNames("a").equals(Set.of("a")),
                "the rejected edge did not persist");
    }

    @Test
    void addInheritEdgeRejectsSelfAndUnknownRoles(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.admin().createRole(tenantWildcard(), "acme", "a", Set.of(), Set.of());
        assertThrows(IllegalArgumentException.class, () -> w.admin().addRoleInherit(tenantWildcard(), "acme", "a", "a"));
        assertThrows(IllegalArgumentException.class, () -> w.admin().addRoleInherit(tenantWildcard(), "acme", "a", "ghost"));
    }

    @Test
    void removeInheritEdgeDropsTheEdge(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.admin().createRole(tenantWildcard(), "acme", "base", Set.of("EDIT_PAGES"), Set.of());
        w.admin().createRole(tenantWildcard(), "acme", "boss", Set.of(), Set.of("base"));
        w.admin().removeRoleInherit(tenantWildcard(), "acme", "boss", "base");
        assertTrue(!w.roles().siteRoles("acme").effectivePermissionKeys("boss").contains("EDIT_PAGES"),
                "the edge is gone so the inherited permission no longer applies");
    }

    @Test
    void roleMutationsAreReservedToAdministrators(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        RunwayUserDetails operator = user("op", Realm.TENANT, false, List.of(), Roles.OPERATOR);
        assertThrows(AccessDeniedException.class,
                () -> w.admin().createRole(operator, "acme", "x", Set.of(), Set.of()));
        assertThrows(AccessDeniedException.class,
                () -> w.admin().deleteRole(operator, "acme", "x"));
        assertThrows(AccessDeniedException.class,
                () -> w.admin().addRoleInherit(operator, "acme", "x", "y"));
    }
}
