package mx.quest.runway.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import mx.quest.runway.security.Roles;
import mx.quest.runway.spi.model.Realm;
import org.junit.jupiter.api.Test;

/** The DAG resolver: transitive downward closure, and fail-closed validation of the seal/refs/cycles. */
class RoleGraphTest {

    private static Role role(String name, Set<String> perms, String... inherits) {
        return new Role(name, Realm.TENANT, perms, Set.of(inherits));
    }

    @Test
    void effectivePermissionsAreTheTransitiveDownwardUnion() {
        RoleGraph graph = RoleGraph.of(List.of(
                role("editor", Set.of("EDIT_PAGES")),
                role("publisher", Set.of("APPROVE_PAGES"), "editor"),
                role("lead", Set.of("CONFIGURE_SITE"), "publisher")));

        assertEquals(Set.of("EDIT_PAGES"), graph.effectivePermissionKeys("editor"));
        assertEquals(Set.of("EDIT_PAGES", "APPROVE_PAGES"), graph.effectivePermissionKeys("publisher"));
        assertEquals(Set.of("EDIT_PAGES", "APPROVE_PAGES", "CONFIGURE_SITE"), graph.effectivePermissionKeys("lead"));
    }

    @Test
    void diamondInheritanceUnionsWithoutDoubleCounting() {
        RoleGraph graph = RoleGraph.of(List.of(
                role("base", Set.of("EDIT_PAGES")),
                role("left", Set.of("MANAGE_USERS"), "base"),
                role("right", Set.of("MANAGE_DOCUMENTS"), "base"),
                role("top", Set.of(), "left", "right")));
        assertEquals(Set.of("EDIT_PAGES", "MANAGE_USERS", "MANAGE_DOCUMENTS"), graph.effectivePermissionKeys("top"));
    }

    @Test
    void anUnknownRoleGrantsNothing() {
        assertTrue(RoleGraph.of(List.of()).effectivePermissionKeys("nope").isEmpty());
    }

    @Test
    void duplicateNamesAreRejected() {
        assertThrows(IllegalStateException.class, () -> RoleGraph.of(List.of(
                role("dup", Set.of("EDIT_PAGES")), role("dup", Set.of("MANAGE_USERS")))));
    }

    @Test
    void theReservedChainIsSealed() {
        // A custom role may neither BE a reserved key nor INHERIT one.
        assertThrows(IllegalStateException.class, () -> RoleGraph.of(List.of(
                new Role(Roles.ADMIN, Realm.TENANT, Set.of(), Set.of()))));
        assertThrows(IllegalStateException.class, () -> RoleGraph.of(List.of(
                role("sneaky", Set.of(), Roles.ADMIN))));
        assertThrows(IllegalStateException.class, () -> RoleGraph.of(List.of(
                new Role("appSneaky", Realm.APPLICATION, Set.of(), Set.of(Roles.SYSTEM_SUPERADMIN)))));
    }

    @Test
    void inheritingAnUnknownRoleIsRejected() {
        assertThrows(IllegalStateException.class, () -> RoleGraph.of(List.of(
                role("orphan", Set.of(), "ghost"))));
    }

    @Test
    void cyclesAreRejected() {
        assertThrows(IllegalStateException.class, () -> RoleGraph.of(List.of(
                role("a", Set.of(), "b"), role("b", Set.of(), "a"))));
        assertThrows(IllegalStateException.class, () -> RoleGraph.of(List.of(
                role("self", Set.of(), "self"))));
    }
}
