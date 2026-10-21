package mx.quest.runway.authz;

import java.util.Set;
import mx.quest.runway.security.Roles;
import mx.quest.runway.spi.model.Realm;

/**
 * The sealed reserved chain — the master and wildcard role keys, per realm. These are <b>code-defined</b>:
 * the master and wildcard tiers are recognized here, never read from a role file, so a hand-edit
 * can never invent or relocate them. A custom role may not bear a reserved key, nor inherit one (the seal,
 * enforced by {@link RoleGraph} on construction).
 *
 * <ul>
 *   <li><b>Master</b> ({@code SYSTEMSUPERADMIN} / {@code SUPERADMIN}) — the immutable hierarchy top; the
 *       authoritative master is the pinned principal id, the role key is only a label.</li>
 *   <li><b>Wildcard</b> ({@code SYSTEMADMIN} / {@code ADMIN}) — full access except master-only operations;
 *       the wildcard is derived from holding this key, never from a writable flag.</li>
 * </ul>
 *
 * <p>The seed operator roles ({@code SYSTEMOPERATOR} / {@code OPERATOR}) are <b>not</b> reserved — they are
 * ordinary, editable roles that happen to be seeded.
 */
public final class ReservedRoles {

    private static final Set<String> MASTER_KEYS = Set.of(Roles.SYSTEM_SUPERADMIN, Roles.SUPERADMIN);
    private static final Set<String> WILDCARD_KEYS = Set.of(Roles.SYSTEM_ADMIN, Roles.ADMIN);

    private ReservedRoles() {
    }

    /** Whether {@code roleName} is a reserved key (master or wildcard) — sealed from the custom-role graph. */
    public static boolean isReserved(String roleName) {
        return MASTER_KEYS.contains(roleName) || WILDCARD_KEYS.contains(roleName);
    }

    /** Whether {@code roleName} is a wildcard key (full access except master-only). */
    public static boolean isWildcard(String roleName) {
        return WILDCARD_KEYS.contains(roleName);
    }

    /** Whether {@code roleName} is a master key (the hierarchy top). */
    public static boolean isMaster(String roleName) {
        return MASTER_KEYS.contains(roleName);
    }

    /** The wildcard key for {@code realm} ({@code SYSTEMADMIN} for application, {@code ADMIN} for tenant). */
    public static String wildcardKey(Realm realm) {
        return realm == Realm.APPLICATION ? Roles.SYSTEM_ADMIN : Roles.ADMIN;
    }

    /**
     * Whether {@code roleName} is a reserved key <b>of {@code realm}</b>. The reserved keys are realm-specific
     * ({@code SYSTEM*} for application, the plain forms for tenant), but {@link #isReserved}/{@link #isWildcard}
     * are realm-agnostic — so an assignment path must use this to reject the <em>other</em> realm's reserved key
     * (a tenant admin must not write {@code SYSTEMADMIN} into a tenant grant store, and vice versa).
     */
    public static boolean isReservedOfRealm(String roleName, Realm realm) {
        return realm == Realm.APPLICATION
                ? Roles.SYSTEM_SUPERADMIN.equals(roleName) || Roles.SYSTEM_ADMIN.equals(roleName)
                : Roles.SUPERADMIN.equals(roleName) || Roles.ADMIN.equals(roleName);
    }
}
