package mx.quest.runway.security;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.PathSafety;

/**
 * A kernel-internal identity record — the persisted shape of a user in one of the two realms. Like
 * {@link mx.quest.runway.site.Site} it is internal, not an SPI boundary type; it is stored as YAML
 * (one of {@code app-users.yml}, {@code site-users.yml}, or a tenant's {@code users.yml}) and holds
 * only the <b>opaque Argon2id PHC string</b>, never a reversible secret.
 *
 * <p>Identity is the stable, path-safe {@link #principalId() principalId} — <b>not</b> the renameable
 * {@link #username()}. The pinned superadmin (the undeletable/undemotable master) is matched by this
 * id, so a username change can never shed or steal the pin. {@link #username()} is the login name,
 * unique only within the record's store scope and matched case-insensitively at login.
 *
 * @param principalId  the stable synthetic identity (path/key-safe; never {@code null})
 * @param username     the login name (non-blank; trimmed; case-insensitive at login)
 * @param passwordHash the opaque Argon2id PHC string ({@code null} normalised to empty — a user with
 *                     no hash can never authenticate, which is the fail-safe default)
 * @param displayName  a human label ({@code null} normalised to empty)
 * @param recoveryEmail an out-of-band recovery address ({@code null} normalised to empty)
 * @param enabled      whether the account may authenticate (a hand-edited file missing this defaults
 *                     to {@code false} — fail-safe)
 * @param master       the superadmin pin marker — the structural, immutable-from-within-the-realm flag
 * @param realm        the realm this identity belongs to (never {@code null}); realms never cross
 * @param roles        coarse role keys (defensively copied, unmodifiable; never {@code null})
 * @param sites        tenancy assignments for a tenant-master identity ({@code "*"} = all tenancies);
 *                     empty for app users and ordinary per-tenant users
 *
 * <p>The record is {@link Serializable} because it rides inside {@link RunwayUserDetails} — the principal
 * Spring Security stores in the {@code HttpSession}, which is serialized into the Hazelcast sessions map when
 * {@code runway.cluster.enabled=true}. Every component is serializable (the {@link Realm} enum, and the
 * {@code Set}/{@code List} copies produced in the canonical constructor), so no custom writeObject is needed.
 */
public record User(String principalId, String username, String passwordHash, String displayName,
                   String recoveryEmail, boolean enabled, boolean master, Realm realm,
                   Set<String> roles, List<String> sites) implements Serializable {

    private static final long serialVersionUID = 1L;

    public User {
        principalId = PathSafety.requireSafeId(principalId); // stable, path/key-safe identity; throws if not
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("user " + principalId + " has a blank username");
        }
        username = username.trim();
        Objects.requireNonNull(realm, "realm");
        passwordHash = (passwordHash == null) ? "" : passwordHash;
        displayName = (displayName == null) ? "" : displayName;
        recoveryEmail = (recoveryEmail == null) ? "" : recoveryEmail;
        roles = (roles == null) ? Set.of() : Set.copyOf(roles);
        sites = (sites == null) ? List.of() : List.copyOf(sites);
    }

    /** Whether this identity holds the given coarse role key. */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** Whether a tenant-master identity is assigned to {@code siteId} (directly or via {@code "*"}). */
    public boolean assignedTo(String siteId) {
        return sites.contains("*") || sites.contains(siteId);
    }

    /** This identity with a new password hash (the only field changed). */
    public User withPasswordHash(String newHash) {
        return new User(principalId, username, newHash, displayName, recoveryEmail, enabled, master, realm, roles, sites);
    }

    /** This identity with its enabled flag set. */
    public User withEnabled(boolean nowEnabled) {
        return new User(principalId, username, passwordHash, displayName, recoveryEmail, nowEnabled, master, realm, roles, sites);
    }

    /** This identity with updated profile fields (display name + recovery email). */
    public User withProfile(String newDisplayName, String newRecoveryEmail) {
        return new User(principalId, username, passwordHash, newDisplayName, newRecoveryEmail, enabled, master, realm, roles, sites);
    }

    /**
     * This identity with its coarse role set replaced (used to change a console user's access tier). The pinned
     * application superadmin's roles are immutable — {@link AppUserStore#save} rejects such a change.
     */
    public User withRoles(Set<String> newRoles) {
        return new User(principalId, username, passwordHash, displayName, recoveryEmail, enabled, master, realm, newRoles, sites);
    }

    /**
     * Redacts the password hash — the record's auto-generated {@code toString} would otherwise spill the
     * Argon2id PHC string into any log line that prints a {@code User}.
     */
    @Override
    public String toString() {
        return "User[principalId=" + principalId + ", username=" + username + ", realm=" + realm
                + ", enabled=" + enabled + ", master=" + master + ", roles=" + roles + ", sites=" + sites
                + ", passwordHash=<redacted:" + passwordHash.length() + ">]";
    }
}
