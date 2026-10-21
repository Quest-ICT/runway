package mx.quest.runway.recovery;

import jakarta.mail.MessagingException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import mx.quest.runway.console.OperatorMessageSource;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.mail.PlatformMailer;
import mx.quest.runway.security.AppUserStore;
import mx.quest.runway.security.SiteUserStore;
import mx.quest.runway.security.TenantMasterStore;
import mx.quest.runway.security.User;
import mx.quest.runway.spi.model.Realm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * The password-recovery engine, shared by both realm controllers. It turns a "forgot password" request into an
 * emailed, time-limited, single-use reset link (over the {@link PlatformMailer platform mail account}) and
 * applies the chosen new password when a valid link is redeemed.
 *
 * <p><b>No account enumeration.</b> A request never reveals whether an email or account exists: the controller
 * always shows the same neutral confirmation, and this service simply emails whichever matching account(s) it
 * finds (possibly none). Only <b>enabled</b> accounts with a matching recovery email are mailed.
 *
 * <p><b>Realm isolation.</b> A grant carries its {@link Realm} and (for a tenant) its {@code siteId}; reset is
 * scoped — an application grant can never reset a tenant account, and a tenant grant is honoured only on its own
 * site's host (the controller passes the host-resolved site, which must equal the grant's). Tenant lookups cover
 * a site's own sub-users plus the tenant-superadmins assigned to it; application lookups cover the app users.
 *
 * <p><b>Audit.</b> Each link actually issued is recorded as {@code recovery.requested} and each successful reset
 * as {@code recovery.completed}, with the account's username as the actor — to the application activity log for
 * an application account, to the site's audit log for a tenant account. A request matching no account records
 * nothing: the typed address is anonymous input (never written to a journal), and an unmatched request changed
 * nothing. Neither the token, the link nor the password is ever recorded.
 */
@Service
public class PasswordRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryService.class);

    /** How long an emailed reset link stays valid. */
    static final Duration TOKEN_TTL = Duration.ofHours(1);

    /** The outcome of redeeming a reset link (the password validity is the controller's concern). */
    public enum ResetOutcome {
        /** The password was reset. */
        OK,
        /** The link was unknown, expired, already used, or for a different realm/site. */
        INVALID_TOKEN,
        /** The link was valid but the account no longer exists or is disabled. */
        ACCOUNT_UNAVAILABLE
    }

    private final AppUserStore appUsers;
    private final SiteUserStore siteUsers;
    private final TenantMasterStore tenantMasters;
    private final RecoveryTokenStore tokens;
    private final PlatformMailer mailer;
    private final PasswordEncoder encoder;
    private final OperatorMessageSource messages;
    private final AppActivityLog activity; // nullable — the bare test form records nothing
    private final SiteLogService siteLog;  // nullable

    /** Bare form (unit tests): no audit sinks. */
    public PasswordRecoveryService(AppUserStore appUsers, SiteUserStore siteUsers, TenantMasterStore tenantMasters,
                                   RecoveryTokenStore tokens, PlatformMailer mailer, PasswordEncoder encoder,
                                   OperatorMessageSource messages) {
        this(appUsers, siteUsers, tenantMasters, tokens, mailer, encoder, messages, null, null);
    }

    @Autowired
    public PasswordRecoveryService(AppUserStore appUsers, SiteUserStore siteUsers, TenantMasterStore tenantMasters,
                                   RecoveryTokenStore tokens, PlatformMailer mailer, PasswordEncoder encoder,
                                   OperatorMessageSource messages, AppActivityLog activity,
                                   SiteLogService siteLog) {
        this.activity = activity;
        this.siteLog = siteLog;
        this.appUsers = appUsers;
        this.siteUsers = siteUsers;
        this.tenantMasters = tenantMasters;
        this.tokens = tokens;
        this.mailer = mailer;
        this.encoder = encoder;
        this.messages = messages;
    }

    /** Whether the platform mail account is configured — recovery cannot email a link without it. */
    public boolean isAvailable() {
        return mailer.isConfigured();
    }

    // --- Request (forgot password) --------------------------------------------------------------------

    /**
     * Application realm: email a reset link to every enabled application user whose recovery email matches.
     *
     * @param email   the address the user typed
     * @param brand   the platform name (for the email subject/body)
     * @param linkFor builds the absolute reset URL from a plaintext token
     * @param locale  the requester's locale (for the email text)
     */
    public void requestApp(String email, String brand, Function<String, String> linkFor, Locale locale) {
        if (!guard(email)) {
            return;
        }
        String needle = normalizeEmail(email);
        for (User user : appUsers.list()) {
            if (matches(user, needle)) {
                sendLink(Realm.APPLICATION, null, user, brand, linkFor, locale);
            }
        }
    }

    /**
     * Tenant realm: email a reset link to every enabled account of {@code siteId} whose recovery email matches —
     * the site's own sub-users, plus the tenant-superadmins assigned to that site.
     */
    public void requestTenant(String siteId, String email, String brand, Function<String, String> linkFor,
                              Locale locale) {
        if (siteId == null || siteId.isBlank() || !guard(email)) {
            return;
        }
        String needle = normalizeEmail(email);
        Set<String> mailed = new HashSet<>();
        for (User user : siteUsers.list(siteId)) {
            if (matches(user, needle) && mailed.add(user.principalId())) {
                sendLink(Realm.TENANT, siteId, user, brand, linkFor, locale);
            }
        }
        for (User master : tenantMasters.assignedTo(siteId)) {
            if (matches(master, needle) && mailed.add(master.principalId())) {
                sendLink(Realm.TENANT, siteId, master, brand, linkFor, locale);
            }
        }
    }

    private boolean guard(String email) {
        if (!mailer.isConfigured()) {
            log.warn("password recovery requested but the platform mail account is not configured — no link sent");
            return false;
        }
        return normalizeEmail(email) != null;
    }

    private void sendLink(Realm realm, String siteId, User user, String brand, Function<String, String> linkFor,
                          Locale locale) {
        if (!user.enabled()) {
            return; // never send a link to a disabled account
        }
        String token = tokens.issue(realm, siteId, user.principalId(), user.username(), TOKEN_TTL,
                System.currentTimeMillis());
        String link = linkFor.apply(token);
        long hours = TOKEN_TTL.toHours();
        String subject = messages.getMessage("recovery.email.subject", new Object[]{brand},
                "Reset your password", locale);
        String body = messages.getMessage("recovery.email.body",
                new Object[]{user.username(), brand, link, hours},
                "We received a request to reset the password for \"" + user.username() + "\" on " + brand
                        + ".\n\nTo choose a new password, open this link:\n" + link
                        + "\n\nThe link expires in " + hours + " hour(s). If you did not request this, ignore this "
                        + "email — your password will not change.\n", locale);
        try {
            mailer.send(user.recoveryEmail(), subject, body);
            log.info("password-recovery link emailed for {} account '{}'", realm, user.username());
            audit(realm, siteId, user.username(), "recovery.requested", "link emailed");
        } catch (MessagingException e) {
            log.warn("password-recovery link send failed for account '{}': {}", user.username(), e.toString());
            audit(realm, siteId, user.username(), "recovery.requested", "link not sent: mail failed");
        }
    }

    /** Records a recovery event to the realm's journal (app activity log / the site's audit log); best-effort. */
    private void audit(Realm realm, String siteId, String username, String action, String detail) {
        if (realm == Realm.APPLICATION) {
            if (activity != null) {
                activity.record(username, action, detail);
            }
        } else if (siteLog != null && siteId != null) {
            siteLog.audit(siteId, username, action, detail);
        }
    }

    // --- Redeem (reset) -------------------------------------------------------------------------------

    /** Peek at an application reset link without consuming it (the reset GET), returning the grant if valid. */
    public Optional<RecoveryToken> validateApp(String token) {
        return validate(token, Realm.APPLICATION, null);
    }

    /** Peek at a tenant reset link for {@code siteId} without consuming it (the reset GET). */
    public Optional<RecoveryToken> validateTenant(String token, String siteId) {
        return validate(token, Realm.TENANT, siteId);
    }

    /** Redeem an application reset link and set the new password (the reset POST). */
    public ResetOutcome resetApp(String token, String newPassword) {
        return reset(token, Realm.APPLICATION, null, newPassword);
    }

    /** Redeem a tenant reset link for {@code siteId} and set the new password (the reset POST). */
    public ResetOutcome resetTenant(String token, String siteId, String newPassword) {
        return reset(token, Realm.TENANT, siteId, newPassword);
    }

    private Optional<RecoveryToken> validate(String token, Realm realm, String siteId) {
        return tokens.peek(token, System.currentTimeMillis()).filter(t -> inScope(t, realm, siteId));
    }

    private ResetOutcome reset(String token, Realm realm, String siteId, String newPassword) {
        long now = System.currentTimeMillis();
        // Scope-check on a peek first, so a link for another realm/site is rejected WITHOUT consuming it.
        if (tokens.peek(token, now).filter(t -> inScope(t, realm, siteId)).isEmpty()) {
            return ResetOutcome.INVALID_TOKEN;
        }
        Optional<RecoveryToken> grant = tokens.consume(token, now).filter(t -> inScope(t, realm, siteId));
        if (grant.isEmpty()) {
            return ResetOutcome.INVALID_TOKEN; // lost a race, or scope changed between peek and consume
        }
        return apply(grant.get(), newPassword);
    }

    private ResetOutcome apply(RecoveryToken grant, String newPassword) {
        String hash = encoder.encode(newPassword);
        if (grant.realm() == Realm.APPLICATION) {
            Optional<User> user = appUsers.findByPrincipalId(grant.principalId()).filter(User::enabled);
            if (user.isEmpty()) {
                return ResetOutcome.ACCOUNT_UNAVAILABLE;
            }
            appUsers.save(user.get().withPasswordHash(hash));
            log.info("password reset via recovery for APPLICATION account '{}'", grant.username());
            audit(Realm.APPLICATION, null, grant.username(), "recovery.completed", "password reset by email link");
            return ResetOutcome.OK;
        }
        // TENANT: a site sub-user first, else a tenant-superadmin assigned to the site.
        String siteId = grant.siteId();
        Optional<User> subUser = byPrincipalId(siteUsers.list(siteId), grant.principalId());
        if (subUser.isPresent()) {
            siteUsers.save(siteId, subUser.get().withPasswordHash(hash));
            log.info("password reset via recovery for TENANT sub-user '{}' of site '{}'", grant.username(), siteId);
            audit(Realm.TENANT, siteId, grant.username(), "recovery.completed", "password reset by email link");
            return ResetOutcome.OK;
        }
        Optional<User> master = byPrincipalId(tenantMasters.assignedTo(siteId), grant.principalId());
        if (master.isPresent()) {
            tenantMasters.save(master.get().withPasswordHash(hash));
            log.info("password reset via recovery for tenant-superadmin '{}' (via site '{}')", grant.username(), siteId);
            audit(Realm.TENANT, siteId, grant.username(), "recovery.completed", "password reset by email link");
            return ResetOutcome.OK;
        }
        return ResetOutcome.ACCOUNT_UNAVAILABLE;
    }

    private static Optional<User> byPrincipalId(List<User> users, String principalId) {
        return users.stream().filter(u -> u.principalId().equals(principalId) && u.enabled()).findFirst();
    }

    private static boolean inScope(RecoveryToken token, Realm realm, String siteId) {
        if (token.realm() != realm) {
            return false;
        }
        if (realm != Realm.TENANT) {
            return true;
        }
        return siteId != null && siteId.equals(token.siteId());
    }

    private static boolean matches(User user, String needleEmail) {
        String email = user.recoveryEmail();
        return email != null && !email.isBlank() && email.trim().equalsIgnoreCase(needleEmail);
    }

    private static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
