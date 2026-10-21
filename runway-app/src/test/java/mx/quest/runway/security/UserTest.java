package mx.quest.runway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import mx.quest.runway.spi.model.Realm;
import org.junit.jupiter.api.Test;

/** The identity record: validation of the stable id/realm, normalisation, immutability, and withers. */
class UserTest {

    private static User user(String id, String username, Realm realm) {
        return new User(id, username, "$argon2id$hash", "Name", "e@x", true, false, realm, Set.of("ADMIN"), List.of());
    }

    @Test
    void requiresASafePrincipalIdABlankUsernameAndARealm() {
        assertThrows(IllegalArgumentException.class, () -> user("../evil", "u", Realm.APPLICATION));
        assertThrows(IllegalArgumentException.class, () -> user(null, "u", Realm.APPLICATION));
        assertThrows(IllegalArgumentException.class, () -> user("ok", "  ", Realm.APPLICATION));
        assertThrows(NullPointerException.class, () -> user("ok", "u", null));
    }

    @Test
    void normalisesNullablesAndTrimsUsername() {
        User u = new User("u1", "  alice  ", null, null, null, true, false, Realm.TENANT, null, null);
        assertEquals("alice", u.username());
        assertEquals("", u.passwordHash());
        assertEquals("", u.displayName());
        assertEquals(Set.of(), u.roles());
        assertEquals(List.of(), u.sites());
    }

    @Test
    void rolesAndSitesAreDefensivelyCopied() {
        assertThrows(UnsupportedOperationException.class,
                () -> user("u1", "a", Realm.APPLICATION).roles().add("X"));
    }

    @Test
    void assignedToHonoursTheWildcard() {
        User any = new User("m", "master", "h", "", "", true, true, Realm.TENANT, Set.of("SUPERADMIN"), List.of("*"));
        User one = new User("u", "u", "h", "", "", true, false, Realm.TENANT, Set.of("ADMIN"), List.of("acme"));
        assertTrue(any.assignedTo("anything"));
        assertTrue(one.assignedTo("acme"));
        assertFalse(one.assignedTo("other"));
    }

    @Test
    void withersChangeOnlyTheTargetedField() {
        User base = user("u1", "alice", Realm.APPLICATION);
        assertEquals("new", base.withPasswordHash("new").passwordHash());
        assertEquals("alice", base.withPasswordHash("new").username());
        assertFalse(base.withEnabled(false).enabled());
        assertEquals("D", base.withProfile("D", "r@x").displayName());
        assertEquals("r@x", base.withProfile("D", "r@x").recoveryEmail());
        assertTrue(base.hasRole("ADMIN"));
    }
}
