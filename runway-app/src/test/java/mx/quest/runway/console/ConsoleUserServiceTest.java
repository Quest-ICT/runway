package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.authz.AuthorizationAdminService;
import mx.quest.runway.security.AppUserStore;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.RunwaySecurityProperties;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.SiteUserStore;
import mx.quest.runway.security.TenantMasterStore;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ConsoleUserService} guards in isolation, with the bounded-grant decision mocked so the "not allowed to
 * grant/revoke this tier" branches (unreachable through the master/wildcard-gated screens today) are proven too.
 */
class ConsoleUserServiceTest {

    private AppUserStore appUsers;
    private SiteUserStore siteUsers;
    private TenantMasterStore masters;
    private AuthorizationAdminService admin;
    private ConsoleUserService service;
    private final RunwayUserDetails actor = new RunwayUserDetails(new User("boss", "boss", "h", "Boss", "", true,
            false, Realm.APPLICATION, Set.of(Roles.SYSTEM_ADMIN), List.of()));

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(Files.createTempDirectory("runway-cus"));
        StorageLayout layout = StorageLayout.from(props);
        UserStore store = new UserStore(new YamlStore());
        RunwaySecurityProperties security = new RunwaySecurityProperties();
        security.setAppMasterId("app-master");
        appUsers = new AppUserStore(layout, store, security);
        siteUsers = new SiteUserStore(layout, store);
        admin = mock(AuthorizationAdminService.class);
        when(admin.mayGrantRole(any(), any(), any())).thenReturn(true);
        masters = new TenantMasterStore(layout, store);
        service = new ConsoleUserService(appUsers, siteUsers, security, admin, masters);
        masters.save(new User("tm", "tm", "h", "Tenant Master", "", true, true, Realm.TENANT,
                Set.of(Roles.SUPERADMIN), List.of("acme")));
        masters.save(new User("app-master", "pinclash", "h", "Clash", "", true, true, Realm.TENANT,
                Set.of(Roles.SUPERADMIN), List.of("acme")));
        masters.save(new User("boss", "bossclash", "h", "Clash", "", true, true, Realm.TENANT,
                Set.of(Roles.SUPERADMIN), List.of("acme")));
        appUsers.save(new User("app-master", "root", "h", "Root", "", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        appUsers.save(new User("op", "op", "h", "Op", "", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR, "CUSTOM"), List.of()));
        siteUsers.save("acme", new User("ed", "ed", "h", "Ed", "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of()));
        siteUsers.save("acme", new User("odd", "odd", "h", "Odd", "", true, true, Realm.TENANT,
                Set.of(Roles.SUPERADMIN), List.of()));
    }

    private static String code(Runnable action) {
        return assertThrows(ConsoleUserService.UserEditException.class, action::run).code();
    }

    @Test
    void aTierChangeReplacesOnlyTheTierKeyAndKeepsOtherRoles() {
        User updated = service.updateAppUser(actor, "op", "Op", "", Roles.SYSTEM_ADMIN);
        assertEquals(Set.of(Roles.SYSTEM_ADMIN, "CUSTOM"), updated.roles());
        assertEquals(updated, appUsers.findByPrincipalId("op").orElseThrow(), "persisted");
    }

    @Test
    void anActorWhoMayNotGrantTheNewTierIsRefused() {
        when(admin.mayGrantRole(any(), eq(Roles.SYSTEM_ADMIN), any())).thenReturn(false);
        assertEquals("tierNotAllowed", code(() -> service.updateAppUser(actor, "op", "Op", "", Roles.SYSTEM_ADMIN)));
        assertEquals(Set.of(Roles.SYSTEM_OPERATOR, "CUSTOM"), appUsers.findByPrincipalId("op").orElseThrow().roles());
    }

    @Test
    void anActorWhoMayNotRevokeTheCurrentTierIsRefused() {
        when(admin.mayGrantRole(any(), eq(Roles.OPERATOR), eq("acme"))).thenReturn(false);
        assertEquals("tierNotAllowed", code(() -> service.updateSiteUser(actor, "acme", "ed", "", "", Roles.ADMIN)));
        assertEquals(Set.of(Roles.OPERATOR), siteUsers.findByPrincipalId("acme", "ed").orElseThrow().roles());
    }

    @Test
    void aBlankTierOrTheSameTierChangesNoRoles() {
        assertEquals(Set.of(Roles.SYSTEM_OPERATOR, "CUSTOM"), service.updateAppUser(actor, "op", "X", "", " ").roles());
        when(admin.mayGrantRole(any(), any(), any())).thenReturn(false); // not consulted for a no-op tier
        assertEquals(Set.of(Roles.SYSTEM_OPERATOR, "CUSTOM"),
                service.updateAppUser(actor, "op", "X", "", Roles.SYSTEM_OPERATOR).roles());
    }

    @Test
    void aBlankDisplayNameFallsBackToTheUsernameAndEmailIsTrimmed() {
        User updated = service.updateAppUser(actor, "op", "  ", " op@example.com ", null);
        assertEquals("op", updated.displayName());
        assertEquals("op@example.com", updated.recoveryEmail());
    }

    @Test
    void emailShapeAndLengthAreChecked() {
        assertEquals("invalidEmail", code(() -> service.updateAppUser(actor, "op", "", "a@b@c", null)));
        assertEquals("invalidEmail", code(() -> service.updateAppUser(actor, "op", "", "no-at-sign", null)));
        assertEquals("invalidEmail", code(() -> service.updateAppUser(actor, "op", "",
                "a@" + "b".repeat(ConsoleUserService.MAX_EMAIL), null)));
        assertEquals("displayNameTooLong", code(() -> service.updateAppUser(actor, "op",
                "n".repeat(ConsoleUserService.MAX_DISPLAY_NAME + 1), "", null)));
        service.updateAppUser(actor, "op", "n".repeat(ConsoleUserService.MAX_DISPLAY_NAME), "", null); // boundary ok
    }

    @Test
    void thePinIsNeverSuspendedOrReTieredEvenByItself() {
        RunwayUserDetails pin = new RunwayUserDetails(appUsers.findByPrincipalId("app-master").orElseThrow());
        assertEquals("pinnedSuspend", code(() -> service.setAppUserEnabled(actor, "app-master", false)));
        assertEquals("pinnedTier", code(() -> service.updateAppUser(pin, "app-master", "R", "", Roles.SYSTEM_ADMIN)));
        assertTrue(appUsers.findByPrincipalId("app-master").orElseThrow().enabled());
        // Resuming the (always enabled) pin is a harmless no-op, not an error.
        assertTrue(service.setAppUserEnabled(actor, "app-master", true).enabled());
    }

    @Test
    void selfSuspendAndSelfTierAreRefusedButSelfResumeAndProfileAreNot() {
        RunwayUserDetails op = new RunwayUserDetails(appUsers.findByPrincipalId("op").orElseThrow());
        assertEquals("selfSuspend", code(() -> service.setAppUserEnabled(op, "op", false)));
        assertEquals("selfTier", code(() -> service.updateAppUser(op, "op", "", "", Roles.SYSTEM_ADMIN)));
        assertEquals("Me", service.updateAppUser(op, "op", "Me", "", null).displayName());
    }

    @Test
    void suspendAndResumeFlipOnlyTheEnabledFlag() {
        User suspended = service.setSiteUserEnabled(actor, "acme", "ed", false);
        assertFalse(suspended.enabled());
        assertEquals(Set.of(Roles.OPERATOR), suspended.roles());
        assertTrue(service.setSiteUserEnabled(actor, "acme", "ed", true).enabled());
    }

    @Test
    void tenantEditsAreScopedToTheGivenTenancyAndRefuseAMasterShapedRecord() {
        assertEquals("noSuchUser", code(() -> service.setSiteUserEnabled(actor, "other", "ed", false)));
        assertEquals("protected", code(() -> service.setSiteUserEnabled(actor, "acme", "odd", false)));
        assertEquals("invalidTier", code(() -> service.updateSiteUser(actor, "acme", "ed", "", "",
                Roles.SYSTEM_ADMIN)));
    }

    // --- tenant superadmins -------------------------------------------------------------------------------

    @Test
    void aTenantSuperadminIsSuspendedAndResumedWithEverythingElseKept() {
        User suspended = service.setTenantMasterEnabled(actor, "tm", false);
        assertFalse(suspended.enabled());
        User stored = masters.findByPrincipalId("tm").orElseThrow();
        assertFalse(stored.enabled(), "persisted");
        assertEquals(List.of("acme"), stored.sites(), "assignments kept");
        assertEquals(Set.of(Roles.SUPERADMIN), stored.roles(), "role kept");
        assertEquals("h", stored.passwordHash(), "password kept");
        assertTrue(service.setTenantMasterEnabled(actor, "tm", true).enabled());
        assertTrue(masters.findByPrincipalId("tm").orElseThrow().enabled());
    }

    @Test
    void tenantSuperadminSuspensionHasTheSameGuards() {
        assertEquals("noSuchUser", code(() -> service.setTenantMasterEnabled(actor, "nobody", false)));
        // A tenant identity that shares the pinned app superadmin's id is never suspended.
        assertEquals("pinnedSuspend", code(() -> service.setTenantMasterEnabled(actor, "app-master", false)));
        // Nor one that shares the actor's own id.
        assertEquals("selfSuspend", code(() -> service.setTenantMasterEnabled(actor, "boss", false)));
        assertTrue(masters.findByPrincipalId("app-master").orElseThrow().enabled());
        assertTrue(masters.findByPrincipalId("boss").orElseThrow().enabled());
        // Resuming is never refused by those guards.
        assertTrue(service.setTenantMasterEnabled(actor, "boss", true).enabled());
    }

    @Test
    void anAppUserIdIsNotATenantSuperadmin() {
        assertEquals("noSuchUser", code(() -> service.setTenantMasterEnabled(actor, "op", false)),
                "the app-user store is never consulted");
        assertTrue(appUsers.findByPrincipalId("op").orElseThrow().enabled());
    }
}
