package mx.quest.runway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The application store's master-pin guard (on mutation) and fail-closed load validation. */
class AppUserStoreTest {

    private static final String PIN = "m-1";

    private static AppUserStore store(Path tmp, String pin) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        RunwaySecurityProperties security = new RunwaySecurityProperties();
        security.setAppMasterId(pin);
        return new AppUserStore(StorageLayout.from(props), new UserStore(new YamlStore()), security);
    }

    private static User master() {
        return new User(PIN, "root", "$argon2id$h", "Root", "r@x", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of());
    }

    private static User operator() {
        return new User("op-1", "olive", "$argon2id$h", "Olive", "o@x", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of());
    }

    // --- mutation guard ----------------------------------------------------------------------

    @Test
    void savesAndFindsOrdinaryUsers(@TempDir Path tmp) {
        AppUserStore store = store(tmp, PIN);
        store.save(operator());
        assertEquals("olive", store.findByUsername("OLIVE").orElseThrow().username()); // case-insensitive
        assertEquals("op-1", store.findByPrincipalId("op-1").orElseThrow().principalId());
    }

    @Test
    void theMasterCannotBeDeletedDisabledDemotedOrRenamed(@TempDir Path tmp) {
        AppUserStore store = store(tmp, PIN);
        store.save(master()); // seed (no existing record yet → allowed)

        assertThrows(IllegalStateException.class, () -> store.delete(PIN));
        assertThrows(IllegalStateException.class, () -> store.save(master().withEnabled(false)));
        assertThrows(IllegalStateException.class, () -> store.save(
                new User(PIN, "root", "$h", "Root", "", true, false, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of())));
        assertThrows(IllegalStateException.class, () -> store.save(
                new User(PIN, "root", "$h", "Root", "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_ADMIN), List.of())));
        assertThrows(IllegalStateException.class, () -> store.save(
                new User(PIN, "renamed", "$h", "Root", "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of())));
    }

    @Test
    void theMasterMayChangeItsPasswordAndProfile(@TempDir Path tmp) {
        AppUserStore store = store(tmp, PIN);
        store.save(master());

        store.save(master().withPasswordHash("$argon2id$new"));
        assertEquals("$argon2id$new", store.findByPrincipalId(PIN).orElseThrow().passwordHash());

        store.save(master().withProfile("Root Admin", "new@x"));
        assertEquals("Root Admin", store.findByPrincipalId(PIN).orElseThrow().displayName());
        assertEquals("new@x", store.findByPrincipalId(PIN).orElseThrow().recoveryEmail());
    }

    @Test
    void theGuardAppliesOnlyToThePinnedId(@TempDir Path tmp) {
        // An ordinary user (not the pin) is freely mutable even if hand-flagged master.
        AppUserStore store = store(tmp, PIN);
        User notPinned = new User("op-2", "x", "$h", "", "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_ADMIN), List.of());
        store.save(notPinned);
        assertTrue(store.delete("op-2"));
    }

    // --- fail-closed load validation ---------------------------------------------------------

    @Test
    void validateAcceptsAWellFormedStore(@TempDir Path tmp) {
        AppUserStore store = store(tmp, PIN);
        store.save(master());
        store.save(operator());
        store.validate(); // no throw
    }

    @Test
    void validateRejectsTamperedStores(@TempDir Path tmp) {
        AppUserStore store = store(tmp, PIN);
        store.save(master());
        // A second master smuggled in (bypassing the pinned-id guard with a different id).
        store.save(new User("m-2", "ghost", "$h", "", "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        assertThrows(IllegalStateException.class, store::validate);
    }

    @Test
    void validateRejectsAPinMismatch(@TempDir Path tmp) {
        AppUserStore store = store(tmp, "different-pin");
        store.save(master()); // master id is m-1, but the configured pin is different-pin
        assertThrows(IllegalStateException.class, store::validate);
    }

    @Test
    void validateUnprovisionedRejectsAStrayMasterButAcceptsAnEmptyStore(@TempDir Path tmp) {
        AppUserStore unprovisioned = store(tmp, null);
        unprovisioned.validate(); // empty + no pin → valid (WIZARD state)

        unprovisioned.save(new User("x", "x", "$h", "", "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        assertThrows(IllegalStateException.class, unprovisioned::validate);
    }

    @Test
    void validateRejectsAStraySuperadminRoleOnANonMaster(@TempDir Path tmp) {
        AppUserStore store = store(tmp, PIN);
        store.save(master());
        // A non-master record holding the platform-superadmin role — a live, guard-free superadmin.
        store.save(new User("op-3", "shadow", "$h", "", "", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        assertThrows(IllegalStateException.class, store::validate);
    }

    @Test
    void validateRejectsDuplicateUsernames(@TempDir Path tmp) throws java.io.IOException {
        // save() refuses a duplicate now (UsernameTakenException), so the clash arrives as a hand edit would.
        new YamlStore().write(tmp.resolve("app-users.yml"), new UserFile(List.of(master(),
                new User("op-4", "ROOT", "$h", "", "", true, false, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_OPERATOR), List.of())))); // "ROOT" collides with the master's "root"
        AppUserStore store = store(tmp, PIN);
        assertThrows(IllegalStateException.class, store::validate);
    }
}
