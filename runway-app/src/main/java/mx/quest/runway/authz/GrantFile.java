package mx.quest.runway.authz;

import java.util.Map;
import java.util.Set;

/**
 * The on-disk shape of a grant store — per-principal role assignments and direct permission grants, plus the
 * <b>role-level deltas</b> from plugins' declared {@code defaultRoles}. These are the assignments that vary by
 * scope: the application file ({@code external/app-grants.yml}) and each tenancy's own file
 * ({@code sites/<id>/permissions.yml}, which travels in its backup). Keyed by stable principal id / role name;
 * values are role names / permission keys.
 *
 * <p>The role deltas realize the locked model "stored as DELTAS from the declared defaults, so plugin default
 * changes propagate where not overridden": {@code roleGrants} adds a (grantable) permission to a role beyond
 * its declared defaults; {@code roleRevokes} removes one a role would otherwise hold by default. The effective
 * set a role grants is then {@code (declared-defaults − roleRevokes) ∪ roleGrants} (see
 * {@link AuthorizationService#effectivePermissionKeys}).
 *
 * <p><b>Back-compatible:</b> a grant file written before role deltas existed simply omits the two new
 * sections; an absent map reads as empty (the 2-arg constructor and {@code null} handling guarantee it), so
 * old {@code app-grants.yml} / {@code permissions.yml} keep loading unchanged. Plaintext by design
 * (integrity, not confidentiality, is the guard).
 *
 * @param roles       principal id → assigned role names (copied, unmodifiable; {@code null} → empty)
 * @param permissions principal id → directly-granted permission keys (copied, unmodifiable; {@code null} → empty)
 * @param roleGrants  role name → permission keys added to that role (delta beyond declared defaults)
 * @param roleRevokes role name → permission keys removed from that role (delta from declared defaults)
 */
public record GrantFile(Map<String, Set<String>> roles, Map<String, Set<String>> permissions,
                        Map<String, Set<String>> roleGrants, Map<String, Set<String>> roleRevokes) {

    public GrantFile {
        roles = copy(roles);
        permissions = copy(permissions);
        roleGrants = copy(roleGrants);
        roleRevokes = copy(roleRevokes);
    }

    /** Back-compatible constructor for a file with no role deltas (the shape before role deltas existed). */
    public GrantFile(Map<String, Set<String>> roles, Map<String, Set<String>> permissions) {
        this(roles, permissions, Map.of(), Map.of());
    }

    /** An empty grant store (an absent file). */
    public static GrantFile empty() {
        return new GrantFile(Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** The role names assigned to {@code principalId} in this scope. */
    public Set<String> rolesOf(String principalId) {
        return roles.getOrDefault(principalId, Set.of());
    }

    /** The permission keys granted directly to {@code principalId} in this scope. */
    public Set<String> permissionsOf(String principalId) {
        return permissions.getOrDefault(principalId, Set.of());
    }

    /** The permission keys added to {@code roleName} beyond its declared defaults. */
    public Set<String> roleGrantsOf(String roleName) {
        return roleGrants.getOrDefault(roleName, Set.of());
    }

    /** The permission keys removed from {@code roleName} relative to its declared defaults. */
    public Set<String> roleRevokesOf(String roleName) {
        return roleRevokes.getOrDefault(roleName, Set.of());
    }

    /** This file with {@code roleName} added to {@code principalId}'s role assignments. */
    public GrantFile withUserRole(String principalId, String roleName) {
        return new GrantFile(plus(roles, principalId, roleName), permissions, roleGrants, roleRevokes);
    }

    /** This file with {@code roleName} removed from {@code principalId}'s role assignments. */
    public GrantFile withoutUserRole(String principalId, String roleName) {
        return new GrantFile(minus(roles, principalId, roleName), permissions, roleGrants, roleRevokes);
    }

    /** This file with {@code permissionKey} added to {@code principalId}'s direct grants. */
    public GrantFile withUserPermission(String principalId, String permissionKey) {
        return new GrantFile(roles, plus(permissions, principalId, permissionKey), roleGrants, roleRevokes);
    }

    /** This file with {@code permissionKey} removed from {@code principalId}'s direct grants. */
    public GrantFile withoutUserPermission(String principalId, String permissionKey) {
        return new GrantFile(roles, minus(permissions, principalId, permissionKey), roleGrants, roleRevokes);
    }

    /** This file with {@code permissionKey} added to {@code roleName}'s grant delta. */
    public GrantFile withRoleGrant(String roleName, String permissionKey) {
        return new GrantFile(roles, permissions, plus(roleGrants, roleName, permissionKey), roleRevokes);
    }

    /** This file with {@code permissionKey} removed from {@code roleName}'s grant delta. */
    public GrantFile withoutRoleGrant(String roleName, String permissionKey) {
        return new GrantFile(roles, permissions, minus(roleGrants, roleName, permissionKey), roleRevokes);
    }

    /** This file with {@code permissionKey} added to {@code roleName}'s revoke delta. */
    public GrantFile withRoleRevoke(String roleName, String permissionKey) {
        return new GrantFile(roles, permissions, roleGrants, plus(roleRevokes, roleName, permissionKey));
    }

    /** This file with {@code permissionKey} removed from {@code roleName}'s revoke delta. */
    public GrantFile withoutRoleRevoke(String roleName, String permissionKey) {
        return new GrantFile(roles, permissions, roleGrants, minus(roleRevokes, roleName, permissionKey));
    }

    /**
     * This file with every trace of {@code roleName} removed: it is taken off every principal that held it, and its
     * grant and revoke deltas are dropped. Used when a custom role is deleted, so its holders lose what it gave them
     * and a role created later under the same name starts clean.
     */
    public GrantFile withoutRole(String roleName) {
        Map<String, Set<String>> held = new java.util.LinkedHashMap<>();
        roles.forEach((principal, names) -> {
            Set<String> kept = new java.util.LinkedHashSet<>(names);
            kept.remove(roleName);
            if (!kept.isEmpty()) {
                held.put(principal, kept);
            }
        });
        Map<String, Set<String>> grants = new java.util.LinkedHashMap<>(roleGrants);
        grants.remove(roleName);
        Map<String, Set<String>> revokes = new java.util.LinkedHashMap<>(roleRevokes);
        revokes.remove(roleName);
        return new GrantFile(held, permissions, grants, revokes);
    }

    private static Map<String, Set<String>> plus(Map<String, Set<String>> base, String key, String value) {
        Map<String, Set<String>> out = new java.util.LinkedHashMap<>(base);
        Set<String> values = new java.util.LinkedHashSet<>(out.getOrDefault(key, Set.of()));
        values.add(value);
        out.put(key, values);
        return out;
    }

    private static Map<String, Set<String>> minus(Map<String, Set<String>> base, String key, String value) {
        if (!base.containsKey(key)) {
            return base;
        }
        Map<String, Set<String>> out = new java.util.LinkedHashMap<>(base);
        Set<String> values = new java.util.LinkedHashSet<>(out.get(key));
        values.remove(value);
        if (values.isEmpty()) {
            out.remove(key);
        } else {
            out.put(key, values);
        }
        return out;
    }

    private static Map<String, Set<String>> copy(Map<String, Set<String>> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> out = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> out.put(key, value == null ? Set.of() : Set.copyOf(value)));
        return Map.copyOf(out);
    }
}
