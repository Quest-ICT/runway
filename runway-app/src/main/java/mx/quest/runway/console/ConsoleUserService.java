package mx.quest.runway.console;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import mx.quest.runway.authz.AuthorizationAdminService;
import mx.quest.runway.security.AppUserStore;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.RunwaySecurityProperties;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.SiteUserStore;
import mx.quest.runway.security.TenantMasterStore;
import mx.quest.runway.security.User;
import mx.quest.runway.spi.model.Realm;
import org.springframework.stereotype.Service;

/**
 * Edits and suspends console users after they were created — the operations the two Users screens
 * ({@code /__app/users}, {@code /__admin/users}) and the application console's Site masters screen
 * ({@code /__app/masters}, suspend / resume only) call. It changes a user's <b>profile</b> (display name, recovery
 * email), <b>access tier</b>, and <b>enabled</b> flag; it never changes the username (the login name), the
 * password (the separate reset action) or custom roles (the permissions matrix).
 *
 * <h2>Guards, all enforced here, server-side</h2>
 * <ul>
 *   <li><b>The pinned application superadmin</b> may have its profile edited, but never its tier changed and never
 *       be suspended. {@link AppUserStore#save} enforces the same rule structurally (it throws on any role or
 *       enabled change to the pin); this service refuses first, with a clear reason.</li>
 *   <li><b>Not yourself:</b> an operator cannot suspend their own account or change their own tier. Either would
 *       lock them out of the screen they are using — and a self-demotion cannot be undone by the same person.</li>
 *   <li><b>Bounded grant:</b> changing a tier both revokes the old tier key and grants the new one, so the actor
 *       must be allowed to grant <em>both</em> ({@link AuthorizationAdminService#mayGrantRole}) — the same rule the
 *       permissions matrix applies, so nobody raises or lowers a user beyond their own authority.</li>
 *   <li><b>Tenant scope:</b> a tenant edit reads and writes only the bound tenancy's {@code users.yml}; the tenant
 *       superadmin lives in a different store (the tenant-master store) and is out of reach by construction. A
 *       record that nevertheless carries the master flag or a master key (a hand-edited file) is refused.</li>
 * </ul>
 *
 * <p>Suspension is the record's existing {@code enabled} flag: a suspended user cannot sign in (Spring's provider
 * honours {@link RunwayUserDetails#isEnabled()}), cannot use password recovery (it mails enabled accounts only),
 * and a session already open is dropped on its next request by {@code AccountStateFilter}.
 *
 * <p>Failures throw {@link UserEditException} carrying a stable {@code code} the controllers localize.
 */
@Service
public class ConsoleUserService {

    /** Longest accepted display name — a label, not a document. */
    static final int MAX_DISPLAY_NAME = 128;
    /** Longest accepted recovery email (the RFC 5321 path limit). */
    static final int MAX_EMAIL = 254;
    /** Deliberately loose: one {@code @}, something on each side, no whitespace. Delivery is the real test. */
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");

    private static final Set<String> APP_TIERS = Set.of(Roles.SYSTEM_ADMIN, Roles.SYSTEM_OPERATOR);
    private static final Set<String> SITE_TIERS = Set.of(Roles.ADMIN, Roles.OPERATOR);

    private final AppUserStore appUsers;
    private final SiteUserStore siteUsers;
    private final RunwaySecurityProperties security;
    private final AuthorizationAdminService admin;
    private final TenantMasterStore masters;

    public ConsoleUserService(AppUserStore appUsers, SiteUserStore siteUsers, RunwaySecurityProperties security,
                              AuthorizationAdminService admin, TenantMasterStore masters) {
        this.appUsers = Objects.requireNonNull(appUsers, "appUsers");
        this.siteUsers = Objects.requireNonNull(siteUsers, "siteUsers");
        this.security = Objects.requireNonNull(security, "security");
        this.admin = Objects.requireNonNull(admin, "admin");
        this.masters = Objects.requireNonNull(masters, "masters");
    }

    /** A refused edit, with a stable {@code code} ({@code op.users.error.<code>}) for the localized message. */
    public static final class UserEditException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String code;

        UserEditException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    // --- application realm ------------------------------------------------------------------

    /**
     * Updates an application user's display name, recovery email and tier.
     *
     * @param tier {@code SYSTEMADMIN} or {@code SYSTEMOPERATOR}; {@code null}/blank keeps the current tier (the
     *             pinned superadmin's form sends none)
     * @return the saved record
     * @throws UserEditException on an unknown user, invalid input, or a guard refusal
     */
    public User updateAppUser(RunwayUserDetails actor, String principalId, String displayName, String email,
                              String tier) {
        User target = appUsers.findByPrincipalId(principalId)
                .orElseThrow(() -> new UserEditException("noSuchUser", "no such user"));
        User updated = withProfile(target, displayName, email);
        String newTier = blank(tier) ? null : tier.trim();
        if (newTier != null && !target.hasRole(newTier)) {
            if (isAppPin(principalId)) {
                throw new UserEditException("pinnedTier", "the application superadmin's tier cannot be changed");
            }
            updated = withTier(updated, newTier, APP_TIERS, actor, null);
        }
        appUsers.save(updated); // the store re-checks the pin structurally
        return updated;
    }

    /**
     * Suspends ({@code enabled=false}) or resumes an application user.
     *
     * @return the saved record
     * @throws UserEditException on an unknown user, the pinned superadmin, or the actor's own account
     */
    public User setAppUserEnabled(RunwayUserDetails actor, String principalId, boolean enabled) {
        User target = appUsers.findByPrincipalId(principalId)
                .orElseThrow(() -> new UserEditException("noSuchUser", "no such user"));
        if (!enabled && isAppPin(principalId)) {
            throw new UserEditException("pinnedSuspend", "the application superadmin cannot be suspended");
        }
        requireNotSelf(actor, principalId, enabled);
        User updated = target.withEnabled(enabled);
        appUsers.save(updated);
        return updated;
    }

    // --- tenant realm -----------------------------------------------------------------------

    /**
     * Updates one of {@code tenancyId}'s sub-users: display name, recovery email and tier ({@code ADMIN} or
     * {@code OPERATOR}; blank keeps the current tier).
     *
     * @return the saved record
     * @throws UserEditException on an unknown user (in THIS tenancy), invalid input, or a guard refusal
     */
    public User updateSiteUser(RunwayUserDetails actor, String tenancyId, String principalId, String displayName,
                               String email, String tier) {
        User target = siteUser(tenancyId, principalId);
        User updated = withProfile(target, displayName, email);
        String newTier = blank(tier) ? null : tier.trim();
        if (newTier != null && !target.hasRole(newTier)) {
            updated = withTier(updated, newTier, SITE_TIERS, actor, tenancyId);
        }
        siteUsers.save(tenancyId, updated);
        return updated;
    }

    /**
     * Suspends or resumes one of {@code tenancyId}'s sub-users.
     *
     * @return the saved record
     * @throws UserEditException on an unknown user (in THIS tenancy) or the actor's own account
     */
    public User setSiteUserEnabled(RunwayUserDetails actor, String tenancyId, String principalId, boolean enabled) {
        User target = siteUser(tenancyId, principalId);
        requireNotSelf(actor, principalId, enabled);
        User updated = target.withEnabled(enabled);
        siteUsers.save(tenancyId, updated);
        return updated;
    }

    // --- tenant superadmins (application-managed) --------------------------------------------

    /**
     * Suspends ({@code enabled=false}) or resumes a tenant superadmin — the global identity in the tenant-master
     * store, managed from the application console. A suspended superadmin cannot sign in to any of its sites, and
     * every open session of it ends on its next request ({@code AccountStateFilter}); the password, the site
     * assignments and the record itself are kept, so Resume restores exactly what was there.
     *
     * <p>The same guards as an application user's suspension, applied to this store: never the pinned
     * application superadmin's identity, never the actor's own identity. Both are structurally unlikely (the actor
     * is an application user, a tenant superadmin a tenant identity) and are refused anyway rather than relied on.
     * Suspending a site's only superadmin is allowed: unlike deletion it is undone with one click, and locking a
     * site's management out is a legitimate reason to suspend.
     *
     * @return the saved record
     * @throws UserEditException on an unknown superadmin, the pinned identity, or the actor's own identity
     */
    public User setTenantMasterEnabled(RunwayUserDetails actor, String principalId, boolean enabled) {
        User target = masters.findByPrincipalId(principalId)
                .orElseThrow(() -> new UserEditException("noSuchUser", "no such user"));
        if (!enabled && isAppPin(principalId)) {
            throw new UserEditException("pinnedSuspend", "the application superadmin cannot be suspended");
        }
        requireNotSelf(actor, principalId, enabled);
        User updated = target.withEnabled(enabled);
        masters.save(updated);
        return updated;
    }

    // --- shared -----------------------------------------------------------------------------

    private User siteUser(String tenancyId, String principalId) {
        User target = siteUsers.findByPrincipalId(tenancyId, principalId)
                .orElseThrow(() -> new UserEditException("noSuchUser", "no such user"));
        if (target.master() || target.hasRole(Roles.SUPERADMIN) || target.realm() != Realm.TENANT) {
            // Never in a well-formed users.yml (the tenant superadmin lives in the tenant-master store).
            throw new UserEditException("protected", "this account cannot be edited here");
        }
        return target;
    }

    /**
     * {@code target} with a new display name (blank ⇒ the username) and recovery email (blank ⇒ none), after the
     * checks every console profile edit shares. Package-visible for the site-masters screen.
     *
     * @throws UserEditException when the name is too long or the email is not an address
     */
    static User withProfile(User target, String displayName, String email) {
        String name = blank(displayName) ? target.username() : displayName.trim();
        if (name.length() > MAX_DISPLAY_NAME) {
            throw new UserEditException("displayNameTooLong",
                    "the display name is longer than " + MAX_DISPLAY_NAME + " characters");
        }
        return target.withProfile(name, recoveryEmail(email));
    }

    /**
     * A recovery email as stored: trimmed, or {@code ""} for none.
     *
     * @throws UserEditException when a non-blank value is not an address
     */
    static String recoveryEmail(String email) {
        String mail = blank(email) ? "" : email.trim();
        if (!mail.isEmpty() && (mail.length() > MAX_EMAIL || !EMAIL.matcher(mail).matches())) {
            throw new UserEditException("invalidEmail", "'" + mail + "' is not a valid email address");
        }
        return mail;
    }

    /**
     * Replaces the realm tier key in {@code user}'s roles with {@code newTier}, after checking the tier is valid,
     * the actor is not changing their own tier, and the actor may grant both the old and the new key.
     */
    private User withTier(User user, String newTier, Set<String> realmTiers, RunwayUserDetails actor,
                          String tenancyId) {
        if (!realmTiers.contains(newTier)) {
            throw new UserEditException("invalidTier", "invalid access tier '" + newTier + "'");
        }
        if (actor.principalId().equals(user.principalId())) {
            throw new UserEditException("selfTier", "you cannot change your own access tier");
        }
        Set<String> roles = new HashSet<>(user.roles());
        for (String old : realmTiers) {
            if (roles.contains(old) && !admin.mayGrantRole(actor, old, tenancyId)) {
                throw new UserEditException("tierNotAllowed", "not authorized to revoke the '" + old + "' tier");
            }
        }
        if (!admin.mayGrantRole(actor, newTier, tenancyId)) {
            throw new UserEditException("tierNotAllowed", "not authorized to grant the '" + newTier + "' tier");
        }
        roles.removeAll(realmTiers);
        roles.add(newTier);
        return user.withRoles(roles);
    }

    private static void requireNotSelf(RunwayUserDetails actor, String principalId, boolean enabling) {
        if (!enabling && actor.principalId().equals(principalId)) {
            throw new UserEditException("selfSuspend", "you cannot suspend your own account");
        }
    }

    private boolean isAppPin(String principalId) {
        String pin = security.getAppMasterId();
        return pin != null && !pin.isBlank() && pin.equals(principalId);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** The tier key a record holds in {@code realm} (for the edit form's preselection), or empty. */
    static String tierOf(User user) {
        Set<String> tiers = user.realm() == Realm.APPLICATION ? APP_TIERS : SITE_TIERS;
        return user.roles().stream().filter(tiers::contains).findFirst().orElse("");
    }
}
