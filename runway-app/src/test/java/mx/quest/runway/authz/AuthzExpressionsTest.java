package mx.quest.runway.authz;
import mx.quest.runway.platform.TestPlatformSettings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.RunwaySecurityProperties;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.User;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** The @authz method-security helper: principal from the SecurityContext, tenancy from the resolver, fail-closed. */
class AuthzExpressionsTest {

    private record Wiring(AuthzExpressions authz, SiteResolver siteResolver) {}

    private static Wiring wire(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        RunwaySecurityProperties security = new RunwaySecurityProperties();
        security.setAppMasterId("app-master");
        AuthorizationService service = new AuthorizationService(
                new RoleStore(layout, yaml), new GrantStore(layout, yaml), new PermissionCatalog(), security);
        SiteResolver siteResolver = new SiteResolver(new SiteStore(layout, yaml), TestPlatformSettings.empty());
        return new Wiring(new AuthzExpressions(service, new PermissionCatalog(), siteResolver), siteResolver);
    }

    private static RunwayUserDetails user(String id, Realm realm, boolean master, List<String> sites, String... roles) {
        return new RunwayUserDetails(new User(id, id, "$h", "", "", true, master, realm, Set.of(roles), sites));
    }

    private static void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthenticationDenies(@TempDir Path tmp) {
        assertFalse(wire(tmp).authz().may("VIEW_AUDIT"));
    }

    @Test
    void aForeignPrincipalTypeDenies(@TempDir Path tmp) {
        authenticate("bob");
        assertFalse(wire(tmp).authz().may("VIEW_AUDIT"));
    }

    @Test
    void anUndeclaredKeyDenies(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        authenticate(user("app-master", Realm.APPLICATION, true, List.of(), Roles.SYSTEM_SUPERADMIN));
        assertFalse(w.authz().may("NOT_A_PERMISSION"), "even the master cannot pass an undeclared key");
    }

    @Test
    void theApplicationMasterMayExercisePrivilegedPermissions(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        authenticate(user("app-master", Realm.APPLICATION, true, List.of(), Roles.SYSTEM_SUPERADMIN));
        assertTrue(w.authz().may("MANAGE_TENANCIES"));
        authenticate(user("op", Realm.APPLICATION, false, List.of(), Roles.SYSTEM_OPERATOR));
        assertFalse(w.authz().may("VIEW_AUDIT"), "an ordinary operator holds nothing");
    }

    @Test
    void theBoundTenancyIsResolvedForTenantPermissions(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        authenticate(user("boss", Realm.TENANT, true, List.of("acme"), Roles.SUPERADMIN));
        try {
            w.siteResolver().bind(new Site("acme", "Acme", null, Locale.ENGLISH, null, null, null));
            assertTrue(w.authz().may("EDIT_PAGES"), "master of the bound tenancy");
        } finally {
            w.siteResolver().clear();
        }
    }

    @Test
    void theExplicitTenancyFormScopesToThatTenancyAndRejectsAppKeys(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        authenticate(user("boss", Realm.TENANT, true, List.of("acme"), Roles.SUPERADMIN));
        assertTrue(w.authz().mayInTenancy("EDIT_PAGES", "acme"), "master of acme");
        assertFalse(w.authz().mayInTenancy("EDIT_PAGES", "beta"), "not master of beta");
        assertFalse(w.authz().mayInTenancy("VIEW_AUDIT", "acme"), "an application key is not valid in the tenancy form");
    }
}
