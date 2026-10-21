package mx.quest.runway.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.MessagingException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import mx.quest.runway.console.OperatorMessageSource;
import mx.quest.runway.mail.PlatformMailer;
import mx.quest.runway.recovery.PasswordRecoveryService.ResetOutcome;
import mx.quest.runway.security.AppUserStore;
import mx.quest.runway.security.SiteUserStore;
import mx.quest.runway.security.TenantMasterStore;
import mx.quest.runway.security.User;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The recovery engine's behaviour: non-enumerating email lookup (app + tenant scopes), send via the platform
 * mailer, realm/site-scoped reset, single use, and the guards (mail unconfigured, disabled account). Stores are
 * mocked; the token store is real (temp dir); the mailer is captured.
 */
class PasswordRecoveryServiceTest {

    private static final Function<String, String> LINK = token -> "https://host/reset?token=" + token;
    private static final Locale EN = Locale.ENGLISH;

    private AppUserStore appUsers;
    private SiteUserStore siteUsers;
    private TenantMasterStore tenantMasters;
    private RecoveryTokenStore tokens;
    private PlatformMailer mailer;
    private PasswordRecoveryService service;
    private OperatorMessageSource messages;

    private static final PasswordEncoder ENCODER = new PasswordEncoder() {
        @Override public String encode(CharSequence raw) {
            return "ENC:" + raw;
        }

        @Override public boolean matches(CharSequence raw, String encoded) {
            return encoded.equals("ENC:" + raw);
        }
    };

    private static User user(String principalId, String username, String email, boolean enabled, Realm realm) {
        return new User(principalId, username, "ENC:old", username, email, enabled, false, realm, Set.of(), List.of());
    }

    @BeforeEach
    void setUp(@TempDir Path root) {
        appUsers = Mockito.mock(AppUserStore.class);
        siteUsers = Mockito.mock(SiteUserStore.class);
        tenantMasters = Mockito.mock(TenantMasterStore.class);
        mailer = Mockito.mock(PlatformMailer.class);
        when(mailer.isConfigured()).thenReturn(true);

        StorageProperties props = new StorageProperties();
        props.setExternal(root.resolve("external"));
        props.setSites(root.resolve("external").resolve("sites"));
        tokens = new RecoveryTokenStore(StorageLayout.from(props), new YamlStore());

        messages = Mockito.mock(OperatorMessageSource.class);
        // Resolve every key to its default (3rd arg) — so the emailed body carries the real link text.
        when(messages.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));

        service = new PasswordRecoveryService(appUsers, siteUsers, tenantMasters, tokens, mailer, ENCODER, messages);
    }

    // --- Request --------------------------------------------------------------------------------------

    @Test
    void requestAppEmailsAMatchingUserWithTheLink() throws MessagingException {
        when(appUsers.list()).thenReturn(List.of(user("u1", "admin", "boss@corp.com", true, Realm.APPLICATION)));

        service.requestApp("BOSS@corp.com", "Runway", LINK, EN); // case-insensitive match

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(to.capture(), anyString(), body.capture());
        assertEquals("boss@corp.com", to.getValue(), "the link is sent to the account's recovery email");
        assertTrue(body.getValue().contains("reset?token="), "the email carries the reset link");
    }

    @Test
    void requestAppSendsNothingWhenNoEmailMatches() throws MessagingException {
        when(appUsers.list()).thenReturn(List.of(user("u1", "admin", "boss@corp.com", true, Realm.APPLICATION)));
        service.requestApp("nobody@nowhere.com", "Runway", LINK, EN);
        verify(mailer, never()).send(any(), any(), any());
    }

    @Test
    void requestSendsNothingWhenMailIsNotConfigured() throws MessagingException {
        when(mailer.isConfigured()).thenReturn(false);
        when(appUsers.list()).thenReturn(List.of(user("u1", "admin", "boss@corp.com", true, Realm.APPLICATION)));
        service.requestApp("boss@corp.com", "Runway", LINK, EN);
        verify(mailer, never()).send(any(), any(), any());
    }

    @Test
    void aDisabledAccountIsNeverEmailed() throws MessagingException {
        when(appUsers.list()).thenReturn(List.of(user("u1", "admin", "boss@corp.com", false, Realm.APPLICATION)));
        service.requestApp("boss@corp.com", "Runway", LINK, EN);
        verify(mailer, never()).send(any(), any(), any());
    }

    @Test
    void requestTenantCoversSubUsersAndAssignedMasters() throws MessagingException {
        when(siteUsers.list("acme")).thenReturn(List.of(user("s1", "editor", "ed@acme.com", true, Realm.TENANT)));
        when(tenantMasters.assignedTo("acme")).thenReturn(List.of());
        service.requestTenant("acme", "ed@acme.com", "Acme", LINK, EN);
        verify(mailer).send(eq("ed@acme.com"), anyString(), anyString());
    }

    // --- Reset ----------------------------------------------------------------------------------------

    @Test
    void resetAppSetsTheNewPasswordAndConsumesTheToken() {
        User u = user("u1", "admin", "boss@corp.com", true, Realm.APPLICATION);
        when(appUsers.findByPrincipalId("u1")).thenReturn(java.util.Optional.of(u));
        String token = tokens.issue(Realm.APPLICATION, null, "u1", "admin",
                PasswordRecoveryService.TOKEN_TTL, System.currentTimeMillis());

        assertEquals(ResetOutcome.OK, service.resetApp(token, "brand-new-pass"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(appUsers).save(saved.capture());
        assertEquals("ENC:brand-new-pass", saved.getValue().passwordHash(), "the new password is encoded + stored");
        // Single use: a second redeem fails.
        assertEquals(ResetOutcome.INVALID_TOKEN, service.resetApp(token, "again-1234"));
    }

    @Test
    void anApplicationTokenCannotResetInTheTenantRealm() {
        String appToken = tokens.issue(Realm.APPLICATION, null, "u1", "admin",
                PasswordRecoveryService.TOKEN_TTL, System.currentTimeMillis());
        assertEquals(ResetOutcome.INVALID_TOKEN, service.resetTenant(appToken, "acme", "brand-new-pass"),
                "an app grant is never honoured on a tenant reset");
        // And it was NOT consumed by the rejected cross-realm attempt.
        assertTrue(service.validateApp(appToken).isPresent(), "the rejected attempt did not consume the app grant");
    }

    @Test
    void aTenantTokenIsHonouredOnlyOnItsOwnSite() {
        User u = user("s1", "editor", "ed@acme.com", true, Realm.TENANT);
        when(siteUsers.list("acme")).thenReturn(List.of(u));
        String token = tokens.issue(Realm.TENANT, "acme", "s1", "editor",
                PasswordRecoveryService.TOKEN_TTL, System.currentTimeMillis());

        assertEquals(ResetOutcome.INVALID_TOKEN, service.resetTenant(token, "other-site", "brand-new-pass"),
                "a grant for site 'acme' cannot reset on another site's host");
        assertEquals(ResetOutcome.OK, service.resetTenant(token, "acme", "brand-new-pass"));
        verify(siteUsers).save(eq("acme"), any(User.class));
    }

    @Test
    void aTenantResetFallsBackToAnAssignedMaster() {
        User master = user("m1", "owner", "owner@acme.com", true, Realm.TENANT);
        when(siteUsers.list("acme")).thenReturn(List.of()); // not a sub-user
        when(tenantMasters.assignedTo("acme")).thenReturn(List.of(master));
        String token = tokens.issue(Realm.TENANT, "acme", "m1", "owner",
                PasswordRecoveryService.TOKEN_TTL, System.currentTimeMillis());

        assertEquals(ResetOutcome.OK, service.resetTenant(token, "acme", "brand-new-pass"));
        verify(tenantMasters).save(any(User.class));
    }

    // --- Audit ----------------------------------------------------------------------------------------

    private PasswordRecoveryService audited(mx.quest.runway.log.AppActivityLog activity,
                                            mx.quest.runway.log.SiteLogService siteLog) {
        return new PasswordRecoveryService(appUsers, siteUsers, tenantMasters, tokens, mailer, ENCODER, messages,
                activity, siteLog);
    }

    @Test
    void anIssuedAppLinkIsJournaledWithoutTheTokenOrTheTypedAddress() throws MessagingException {
        var activity = Mockito.mock(mx.quest.runway.log.AppActivityLog.class);
        var siteLog = Mockito.mock(mx.quest.runway.log.SiteLogService.class);
        when(appUsers.list()).thenReturn(List.of(user("u1", "admin", "boss@corp.com", true, Realm.APPLICATION)));

        audited(activity, siteLog).requestApp("boss@corp.com", "Runway", LINK, EN);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(activity).record(eq("admin"), eq("recovery.requested"), detail.capture());
        assertTrue(!detail.getValue().contains("token") && !detail.getValue().contains("boss@corp.com"),
                "no token, link or address in the journal: " + detail.getValue());
        verify(siteLog, never()).audit(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anUnmatchedRequestJournalsNothing() {
        var activity = Mockito.mock(mx.quest.runway.log.AppActivityLog.class);
        when(appUsers.list()).thenReturn(List.of(user("u1", "admin", "boss@corp.com", true, Realm.APPLICATION)));
        audited(activity, null).requestApp("attacker-typed@evil.test", "Runway", LINK, EN);
        verify(activity, never()).record(anyString(), anyString(), anyString());
    }

    @Test
    void aFailedSendIsStillJournaledAsRequested() throws MessagingException {
        var activity = Mockito.mock(mx.quest.runway.log.AppActivityLog.class);
        when(appUsers.list()).thenReturn(List.of(user("u1", "admin", "boss@corp.com", true, Realm.APPLICATION)));
        Mockito.doThrow(new MessagingException("smtp down")).when(mailer).send(anyString(), anyString(), anyString());
        audited(activity, null).requestApp("boss@corp.com", "Runway", LINK, EN);
        verify(activity).record(eq("admin"), eq("recovery.requested"),
                org.mockito.ArgumentMatchers.contains("mail failed"));
    }

    @Test
    void aTenantLinkIsJournaledToThatSiteOnly() {
        var activity = Mockito.mock(mx.quest.runway.log.AppActivityLog.class);
        var siteLog = Mockito.mock(mx.quest.runway.log.SiteLogService.class);
        when(siteUsers.list("acme")).thenReturn(List.of(user("s1", "editor", "ed@acme.com", true, Realm.TENANT)));
        when(tenantMasters.assignedTo("acme")).thenReturn(List.of());

        audited(activity, siteLog).requestTenant("acme", "ed@acme.com", "Acme", LINK, EN);

        verify(siteLog).audit(eq("acme"), eq("editor"), eq("recovery.requested"), anyString());
        verify(activity, never()).record(anyString(), anyString(), anyString());
    }

    @Test
    void aCompletedResetIsJournaledButAFailedOneIsNot() {
        var activity = Mockito.mock(mx.quest.runway.log.AppActivityLog.class);
        PasswordRecoveryService svc = audited(activity, null);
        User u = user("u1", "admin", "boss@corp.com", true, Realm.APPLICATION);
        when(appUsers.findByPrincipalId("u1")).thenReturn(java.util.Optional.of(u));

        assertEquals(ResetOutcome.INVALID_TOKEN, svc.resetApp("bogus", "N3w-Passw0rd!"));
        verify(activity, never()).record(anyString(), anyString(), anyString());

        String token = tokens.issue(Realm.APPLICATION, null, "u1", "admin",
                PasswordRecoveryService.TOKEN_TTL, System.currentTimeMillis());
        assertEquals(ResetOutcome.OK, svc.resetApp(token, "N3w-Passw0rd!"));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(activity).record(eq("admin"), eq("recovery.completed"), detail.capture());
        assertTrue(!detail.getValue().contains("N3w-Passw0rd!") && !detail.getValue().contains(token),
                "neither the password nor the token is journaled");
    }

    @Test
    void aCompletedTenantResetIsJournaledToItsSite() {
        var siteLog = Mockito.mock(mx.quest.runway.log.SiteLogService.class);
        when(siteUsers.list("acme")).thenReturn(List.of(user("s1", "editor", "ed@acme.com", true, Realm.TENANT)));
        String token = tokens.issue(Realm.TENANT, "acme", "s1", "editor",
                PasswordRecoveryService.TOKEN_TTL, System.currentTimeMillis());
        assertEquals(ResetOutcome.OK, audited(null, siteLog).resetTenant(token, "acme", "N3w-Passw0rd!"));
        verify(siteLog).audit(eq("acme"), eq("editor"), eq("recovery.completed"), anyString());
    }
}
