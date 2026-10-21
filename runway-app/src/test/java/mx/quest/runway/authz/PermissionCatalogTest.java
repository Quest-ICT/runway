package mx.quest.runway.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mx.quest.runway.spi.model.Realm;
import org.junit.jupiter.api.Test;

/** The declared catalog: fail-closed lookup, the grantable/privileged split, and realm tagging. */
class PermissionCatalogTest {

    private final PermissionCatalog catalog = new PermissionCatalog();

    @Test
    void declaredPermissionsResolveAndUnknownOnesFailClosed() {
        assertTrue(catalog.find("EDIT_PAGES").isPresent());
        assertTrue(catalog.isDeclared("MANAGE_TENANCIES")); // privileged ones are still declared
        assertTrue(catalog.find("NOPE_NOT_A_PERMISSION").isEmpty(), "an unknown key is undeclared (fail-closed)");
        assertFalse(catalog.isDeclared("NOPE_NOT_A_PERMISSION"));
    }

    @Test
    void grantablePermissionsAreAssignableAndPrivilegedOnesAreNot() {
        assertTrue(catalog.isGrantable(Permissions.EDIT_PAGES));
        assertTrue(catalog.isGrantable(Permissions.VIEW_TENANCIES));
        assertTrue(catalog.isGrantable(Permissions.MANAGE_SITE_HOSTS), "hosts editing is delegable to roles/users");
        assertFalse(catalog.isGrantable(Permissions.MANAGE_TENANCIES), "master/wildcard-only is never grantable");
        assertFalse(catalog.isGrantable(Permissions.CONFIGURE_PLATFORM));
    }

    @Test
    void grantableSetsAreRealmScoped() {
        assertTrue(catalog.grantable(Realm.TENANT).contains(Permissions.EDIT_PAGES));
        assertFalse(catalog.grantable(Realm.TENANT).contains(Permissions.VIEW_TENANCIES), "app permission, not tenant");

        assertTrue(catalog.grantable(Realm.APPLICATION).contains(Permissions.VIEW_TENANCIES));
        assertFalse(catalog.grantable(Realm.APPLICATION).contains(Permissions.MANAGE_TENANCIES), "privileged, not grantable");
        assertFalse(catalog.grantable(Realm.APPLICATION).contains(Permissions.EDIT_PAGES), "tenant permission, not app");
    }

    @Test
    void everyPermissionIsRealmTaggedAndAllIncludesBothClasses() {
        assertEquals(Realm.TENANT, Permissions.EDIT_PAGES.realm());
        assertEquals(Realm.APPLICATION, Permissions.MANAGE_TENANCIES.realm());
        assertTrue(catalog.all().contains(Permissions.EDIT_PAGES));         // grantable
        assertTrue(catalog.all().contains(Permissions.MANAGE_TENANCIES));   // privileged
    }
}
