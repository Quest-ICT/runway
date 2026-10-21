package mx.quest.runway.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import mx.quest.runway.security.AppUserStore;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.RunwaySecurityProperties;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.SiteUserStore;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The grants file is written by read-modify-write, and nothing serialised it. Two administrators writing
 * the same scope at the same moment lost one change, and an assignment landing between a role delete's two writes left
 * the holder with a role the graph no longer knows (which still resolves to itself and keeps its console grants).
 *
 * <p>Each test makes the second writer start while the first is inside its critical section and gives it time to
 * finish there; with the scope's lock it cannot, so the outcome is the same as running the two one after the other.
 */
class GrantWriteConcurrencyTest {

    /** How long the second writer is given to slip in. Long enough to finish a small file write many times over. */
    private static final long WINDOW_MILLIS = 300;

    /** A role store that runs a hook just before it writes the roles file. */
    static final class HookedRoleStore extends RoleStore {
        Runnable beforeWrite = () -> { };

        HookedRoleStore(StorageLayout layout, YamlStore yaml) {
            super(layout, yaml);
        }

        @Override
        public void saveSiteRoles(String siteId, List<Role> roles) {
            Runnable hook = beforeWrite;
            beforeWrite = () -> { };
            hook.run();
            super.saveSiteRoles(siteId, roles);
        }
    }

    private record Wiring(AuthorizationAdminService admin, HookedRoleStore roles, GrantStore grants) {
    }

    private static Wiring wire(Path dir) {
        StorageProperties props = new StorageProperties();
        props.setExternal(dir);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        HookedRoleStore roles = new HookedRoleStore(layout, yaml);
        GrantStore grants = new GrantStore(layout, yaml);
        PermissionCatalog catalog = new PermissionCatalog();
        RunwaySecurityProperties security = new RunwaySecurityProperties();
        security.setAppMasterId("app-master");
        AuthorizationService authz = new AuthorizationService(roles, grants, catalog, security);
        UserStore users = new UserStore(yaml);
        return new Wiring(new AuthorizationAdminService(roles, grants, catalog, authz, security,
                new AppUserStore(layout, users, security), new SiteUserStore(layout, users)), roles, grants);
    }

    private static RunwayUserDetails tenantAdmin() {
        return new RunwayUserDetails(new User("ad", "ad", "$h", "", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of()));
    }

    /** Starts {@code work} on another thread and gives it {@link #WINDOW_MILLIS} to finish; returns the thread. */
    private static Thread startAndWait(Runnable work) {
        Thread other = new Thread(work, "second-admin");
        other.start();
        try {
            other.join(WINDOW_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return other;
    }

    @Test
    void anAssignmentDuringARoleDeleteNeverLeavesAHolderOfTheGoneRole(@TempDir Path dir) throws Exception {
        Wiring w = wire(dir);
        w.admin().createRole(tenantAdmin(), "acme", "editor", Set.of("EDIT_PAGES"), Set.of());
        w.admin().assignRole(tenantAdmin(), "acme", "sub", "editor");
        AtomicReference<Throwable> second = new AtomicReference<>();
        AtomicReference<Thread> thread = new AtomicReference<>();
        // Between the delete's grants write and its roles write, a second admin assigns the role to someone else.
        w.roles().beforeWrite = () -> thread.set(startAndWait(() -> {
            try {
                w.admin().assignRole(tenantAdmin(), "acme", "x", "editor");
            } catch (RuntimeException refused) {
                second.set(refused);
            }
        }));

        w.admin().deleteRole(tenantAdmin(), "acme", "editor");
        thread.get().join();

        assertFalse(w.roles().siteRoles("acme").hasRole("editor"), "the role is gone");
        assertEquals(Set.of(), w.grants().siteGrants("acme").rolesOf("x"), "and nobody holds it");
        assertEquals(Set.of(), w.grants().siteGrants("acme").rolesOf("sub"));
        assertInstanceOf(IllegalArgumentException.class, second.get(),
                "the second admin ran after the delete and was told there is no such role");
    }

    @Test
    void twoChangesToTheSameScopeAtTheSameMomentBothStay(@TempDir Path dir) throws Exception {
        Wiring w = wire(dir);
        AtomicReference<Thread> thread = new AtomicReference<>();
        w.grants().updateSiteGrants("acme", current -> {
            thread.set(startAndWait(() -> w.grants().updateSiteGrants("acme",
                    latest -> latest.withUserPermission("b", "EDIT_PAGES"))));
            return current.withUserPermission("a", "EDIT_PAGES");
        });
        thread.get().join();

        GrantFile stored = w.grants().siteGrants("acme");
        assertTrue(stored.permissionsOf("a").contains("EDIT_PAGES"), "the first change");
        assertTrue(stored.permissionsOf("b").contains("EDIT_PAGES"), "and the second, not lost");
    }

    @Test
    void twoSitesDoNotWaitForEachOther(@TempDir Path dir) throws Exception {
        Wiring w = wire(dir);
        AtomicReference<Thread> thread = new AtomicReference<>();
        w.grants().updateSiteGrants("acme", current -> {
            thread.set(startAndWait(() -> w.grants().updateSiteGrants("beta",
                    latest -> latest.withUserPermission("b", "EDIT_PAGES"))));
            assertFalse(thread.get().isAlive(), "another site's change finished while this one was in progress");
            return current.withUserPermission("a", "EDIT_PAGES");
        });
        assertTrue(w.grants().siteGrants("beta").permissionsOf("b").contains("EDIT_PAGES"));
    }
}
