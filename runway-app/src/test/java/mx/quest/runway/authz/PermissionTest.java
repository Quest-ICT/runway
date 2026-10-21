package mx.quest.runway.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import mx.quest.runway.spi.model.Realm;
import org.junit.jupiter.api.Test;

/** The realm-tagged permission boundary record: validation and value semantics. */
class PermissionTest {

    @Test
    void requiresAKeyAndARealm() {
        assertThrows(IllegalArgumentException.class, () -> new Permission(null, Realm.TENANT));
        assertThrows(IllegalArgumentException.class, () -> new Permission("  ", Realm.TENANT));
        assertThrows(NullPointerException.class, () -> new Permission("EDIT_PAGES", null));
    }

    @Test
    void isEqualByKeyAndRealm() {
        assertEquals(new Permission("EDIT_PAGES", Realm.TENANT), new Permission("EDIT_PAGES", Realm.TENANT));
        // The same key in a different realm is a different permission (realm-meaningful).
        assertNotEquals(new Permission("VIEW", Realm.TENANT), new Permission("VIEW", Realm.APPLICATION));
    }
}
