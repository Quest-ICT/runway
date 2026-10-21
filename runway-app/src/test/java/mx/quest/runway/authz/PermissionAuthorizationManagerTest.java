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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** The request-gate manager: fail-closed on no/foreign principal, and the bound tenancy resolved per request. */
class PermissionAuthorizationManagerTest {

    private record Wiring(RequestAuthorizers authorizers, SiteResolver siteResolver) {}

    private static Wiring wire(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        RunwaySecurityProperties security = new RunwaySecurityProperties();
        security.setAppMasterId("app-master");
        AuthorizationService authz = new AuthorizationService(
                new RoleStore(layout, yaml), new GrantStore(layout, yaml), new PermissionCatalog(), security);
        SiteResolver siteResolver = new SiteResolver(new SiteStore(layout, yaml), TestPlatformSettings.empty());
        return new Wiring(new RequestAuthorizers(authz, siteResolver), siteResolver);
    }

    private static RunwayUserDetails user(String id, Realm realm, boolean master, List<String> sites, String... roles) {
        return new RunwayUserDetails(new User(id, id, "$h", "", "", true, master, realm, Set.of(roles), sites));
    }

    private static AuthorizationResult check(PermissionAuthorizationManager manager, Authentication auth) {
        return manager.authorize(() -> auth, new RequestAuthorizationContext(new MockHttpServletRequest()));
    }

    private static Authentication authed(RunwayUserDetails principal) {
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private static Site site(String id) {
        return new Site(id, id, null, Locale.ENGLISH, null, null, null);
    }

    @Test
    void noAuthenticationDenies(@TempDir Path tmp) {
        PermissionAuthorizationManager gate = wire(tmp).authorizers().requirePermission(Permissions.VIEW_AUDIT);
        assertFalse(check(gate, null).isGranted());
    }

    @Test
    void aForeignPrincipalTypeDenies(@TempDir Path tmp) {
        PermissionAuthorizationManager gate = wire(tmp).authorizers().requirePermission(Permissions.VIEW_AUDIT);
        Authentication notRunway = UsernamePasswordAuthenticationToken.authenticated("bob", null, List.of());
        assertFalse(check(gate, notRunway).isGranted());
    }

    @Test
    void anApplicationGateResolvesWithoutASite(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        PermissionAuthorizationManager privileged = w.authorizers().requirePermission(Permissions.MANAGE_TENANCIES);
        assertTrue(check(privileged, authed(user("app-master", Realm.APPLICATION, true, List.of(), Roles.SYSTEM_SUPERADMIN)))
                .isGranted(), "the application master passes a privileged gate");
        assertFalse(check(privileged, authed(user("op", Realm.APPLICATION, false, List.of(), Roles.SYSTEM_OPERATOR)))
                .isGranted(), "an ordinary operator does not");
    }

    @Test
    void aTenantGateResolvesTheBoundTenancy(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        PermissionAuthorizationManager editPages = w.authorizers().requirePermission(Permissions.EDIT_PAGES);
        RunwayUserDetails boss = user("boss", Realm.TENANT, true, List.of("acme"), Roles.SUPERADMIN);
        try {
            w.siteResolver().bind(site("acme"));
            assertTrue(check(editPages, authed(boss)).isGranted(), "master of the bound tenancy passes");
            w.siteResolver().clear();
            w.siteResolver().bind(site("beta"));
            assertFalse(check(editPages, authed(boss)).isGranted(), "not master of a different bound tenancy");
        } finally {
            w.siteResolver().clear();
        }
    }
}
