package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.authz.AuthorizationService;
import mx.quest.runway.authz.Permissions;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.User;
import mx.quest.runway.spi.model.Realm;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/** {@link ConsoleNavigation} in isolation: every key present, groups follow their entries and plugin entries. */
class ConsoleNavigationTest {

    private final AuthorizationService authz = mock(AuthorizationService.class);
    private final PlatformAuthority platform = mock(PlatformAuthority.class);
    private final TenantAuthority tenant = mock(TenantAuthority.class);
    private final ConsoleNavigation navigation = new ConsoleNavigation(authz, platform, tenant);
    private final Authentication operator = auth(Realm.APPLICATION);

    private static Authentication auth(Realm realm) {
        RunwayUserDetails principal = new RunwayUserDetails(new User("u", "u", "h", "U", "", true, false, realm,
                Set.of(realm == Realm.APPLICATION ? Roles.SYSTEM_OPERATOR : Roles.OPERATOR), List.of()));
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    @Test
    void everyEntryAndGroupHasAKeySoTheTemplateNeverSeesNull() {
        Map<String, Boolean> app = navigation.visibility(Realm.APPLICATION, operator, null, Set.of());
        Set<String> expected = new HashSet<>();
        ConsoleNavigation.APP.forEach(e -> {
            expected.add(e.key());
            if (e.group() != null) {
                expected.add("grp." + e.group());
            }
        });
        assertEquals(expected, app.keySet());
        assertEquals(Set.of("dashboard", "pages", "review", "fragments", "widgets", "documents", "static", "messages",
                "users",
                "permissions", "insights", "bandwidth", "logs", "config", "seo", "icons", "manifest", "backups",
                "maintenance", "grp.resources", "grp.access", "grp.activity", "grp.settings"),
                navigation.visibility(Realm.TENANT, auth(Realm.TENANT), "acme", Set.of()).keySet());
    }

    @Test
    void aGroupWithNoVisibleEntryIsHiddenUnlessAPluginContributesToIt() {
        Map<String, Boolean> bare = navigation.visibility(Realm.APPLICATION, operator, null, Set.of());
        assertFalse(bare.get("grp.activity"));
        assertFalse(bare.get("logs"));
        assertFalse(bare.get("sites"), "Manage sites needs VIEW_TENANCIES or MANAGE_SITE_HOSTS");
        assertTrue(bare.get("grp.sites"), "Site masters needs only the realm sign-in, so the group stays");

        assertTrue(navigation.visibility(Realm.APPLICATION, operator, null, Set.of("activity")).get("grp.activity"),
                "a plugin's Activity entry keeps the dropdown");
        assertFalse(navigation.visibility(Realm.APPLICATION, operator, null, Set.of("nonsense"))
                .containsKey("grp.nonsense"), "an unknown plugin group invents no key");
    }

    @Test
    void applicationPermissionsAreCheckedWithoutATenancyAndTenantOnesInTheBoundSite() {
        when(authz.mayAccess(any(), eq(Permissions.VIEW_AUDIT), isNull())).thenReturn(true);
        assertTrue(navigation.visibility(Realm.APPLICATION, operator, null, Set.of()).get("logs"));

        Authentication siteOp = auth(Realm.TENANT);
        when(authz.mayAccess(any(), eq(Permissions.DISABLE_PAGES), eq("acme"))).thenReturn(true);
        Map<String, Boolean> site = navigation.visibility(Realm.TENANT, siteOp, "acme", Set.of());
        assertTrue(site.get("pages"), "either of the Pages screen's two permissions opens it");
        assertTrue(site.get("grp.resources"));
        assertFalse(navigation.visibility(Realm.TENANT, siteOp, "beta", Set.of()).get("pages"),
                "a grant in one site is not a grant in another");
    }

    @Test
    void theAdministratorGatesUseTheSameChecksAsTheRoutes() {
        when(platform.isPlatformAdmin(operator)).thenReturn(true);
        Map<String, Boolean> app = navigation.visibility(Realm.APPLICATION, operator, null, Set.of());
        assertTrue(app.get("settings") && app.get("backups") && app.get("permissions"));

        Authentication siteUser = auth(Realm.TENANT);
        when(tenant.isTenantAdmin(siteUser, "acme")).thenReturn(true);
        Map<String, Boolean> site = navigation.visibility(Realm.TENANT, siteUser, "acme", Set.of());
        assertTrue(site.get("permissions") && site.get("logs") && site.get("bandwidth"));
        assertFalse(navigation.visibility(Realm.TENANT, siteUser, null, Set.of()).get("permissions"),
                "no bound site: no site-administrator entries");
    }

    @Test
    void theUsersAndSitesEntriesFollowTheirPermissions() {
        Authentication siteUser = auth(Realm.TENANT);
        assertFalse(navigation.visibility(Realm.TENANT, siteUser, "acme", Set.of()).get("users"));
        when(authz.mayAccess(any(), eq(Permissions.DELETE_USERS), eq("acme"))).thenReturn(true);
        assertTrue(navigation.visibility(Realm.TENANT, siteUser, "acme", Set.of()).get("users"),
                "DELETE_USERS alone opens the (read-only) Users screen");
        when(authz.mayAccess(any(), eq(Permissions.MANAGE_SITE_HOSTS), isNull())).thenReturn(true);
        assertTrue(navigation.visibility(Realm.APPLICATION, operator, null, Set.of()).get("sites"),
                "MANAGE_SITE_HOSTS opens the list its host editor lives on");
    }

    @Test
    void anAnonymousShellShowsOnlyTheOpenEntries() {
        Map<String, Boolean> app = navigation.visibility(Realm.APPLICATION, null, null, Set.of());
        assertTrue(app.get("dashboard") && app.get("users") && app.get("about"));
        assertFalse(app.get("logs") || app.get("settings"));
    }
}
