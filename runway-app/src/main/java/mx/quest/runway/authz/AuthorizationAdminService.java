package mx.quest.runway.authz;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import mx.quest.runway.security.AppUserStore;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.RunwaySecurityProperties;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.SiteUserStore;
import mx.quest.runway.security.User;
import mx.quest.runway.spi.model.Realm;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * The authorization-management service — where an administrator defines roles and assigns roles/permissions
 * to users, with the <b>bounded-grant</b> rules enforced server-side: no one grants beyond their own
 * effective authority, the wildcard key is grantable only by the master or a wildcard admin, and the master
 * keys are never grantable in-app. Role definition itself is reserved to administrators (the wildcard tier
 * and above), and a custom role may hold only grantable permissions (never a master/wildcard-only one).
 *
 * <p>Operations take the acting principal and the resolved scope ({@code tenancyId} is {@code null} for the
 * application realm). All checks route through {@link AuthorizationService} so the precedence and tenancy
 * scoping are the same as at request time. Business logic lives here, never in a controller.
 *
 * <h2>Concurrency: one scope, one critical section</h2>
 * Every change runs under its scope's lock ({@link GrantStore#lock}), from the checks to the last write: the checks
 * read the role graph and the grants (does the role exist, may the actor grant it), and a write made between a check
 * and its write could make the check stale. So a role being deleted is never assigned at the same moment, and two
 * matrix writes to the same scope never lose each other. Grant-file changes go through {@link GrantStore}'s atomic
 * update. Scopes are the application realm and each site; different sites never wait for each other.
 */
@Service
public class AuthorizationAdminService {

    private final RoleStore roles;
    private final GrantStore grants;
    private final PermissionCatalog catalog;
    private final AuthorizationService authz;
    private final RunwaySecurityProperties security;
    private final AppUserStore appUsers;
    private final SiteUserStore siteUsers;

    public AuthorizationAdminService(RoleStore roles, GrantStore grants, PermissionCatalog catalog,
                                     AuthorizationService authz, RunwaySecurityProperties security,
                                     AppUserStore appUsers, SiteUserStore siteUsers) {
        this.roles = Objects.requireNonNull(roles, "roles");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.security = Objects.requireNonNull(security, "security");
        this.appUsers = Objects.requireNonNull(appUsers, "appUsers");
        this.siteUsers = Objects.requireNonNull(siteUsers, "siteUsers");
    }

    // --- role definition (administrators only) ----------------------------------------------

    /**
     * The syntactic shape of a custom role name: 1–64 characters, upper/lower letters, digits, underscore and
     * hyphen only. Deliberately conservative — a role name is an identifier that travels into YAML files, grant
     * deltas and log lines, so it excludes whitespace, dots and slashes (path-safe by construction) and anything
     * that could collide with a delimiter or be mistaken for a traversal segment. It is <b>not</b> a filesystem
     * path (roles are stored as values inside a single per-scope role file), but keeping the vocabulary tight is
     * cheap defence and keeps names greppable.
     */
    private static final Pattern ROLE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * Creates a <b>new</b> custom role with the given name, optional direct permissions and optional initial
     * inherit edges, failing fast if the name is malformed, reserved, already taken (as a custom role or a
     * predefined/reserved key), or if any edge/permission is invalid. Unlike {@link #saveRole} (which
     * create-<em>or</em>-replaces), this is strictly a create: a name clash is an error, never a silent
     * overwrite. Reserved to administrators (manage-roles). The {@link RoleStore} re-validates the resulting
     * graph (seal, unknown refs, cycle) before it is persisted, so the invariants cannot be bypassed here.
     *
     * @param actor       the acting principal (its realm fixes the scope)
     * @param tenancyId   the bound tenancy id, or {@code null} for the application realm
     * @param name        the new role's name (validated against {@link #ROLE_NAME}, must be free)
     * @param permissions the permission keys granted directly by the role (grantable only; may be empty)
     * @param inherits    the names of existing custom roles this role inherits (may be empty)
     * @throws IllegalArgumentException  if the name is malformed, reserved or already in use, or a permission
     *                                   is not grantable, or an inherited role does not exist
     * @throws org.springframework.security.access.AccessDeniedException if the actor may not manage roles
     */
    public void createRole(RunwayUserDetails actor, String tenancyId, String name,
                           Set<String> permissions, Set<String> inherits) {
        inScope(tenancyId, () -> {
            requireManageRoles(actor, tenancyId);
            String trimmed = name == null ? "" : name.trim();
            if (!ROLE_NAME.matcher(trimmed).matches()) {
                throw new IllegalArgumentException(
                        "a role name must be 1–64 characters of letters, digits, '_' or '-'");
            }
            if (nameInUse(actor.realm(), tenancyId, trimmed)) {
                throw new IllegalArgumentException("a role named '" + trimmed + "' already exists");
            }
            Role role = new Role(trimmed, actor.realm(),
                    permissions == null ? Set.of() : permissions,
                    inherits == null ? Set.of() : inherits);
            saveRole(actor, tenancyId, role); // re-validates seal/permissions/refs/cycle before persisting
        });
    }

    /** Creates or replaces a custom role (by name). Reserved to administrators; only grantable permissions. */
    public void saveRole(RunwayUserDetails actor, String tenancyId, Role role) {
        inScope(tenancyId, () -> {
            requireRealm(actor, role.realm());
            requireManageRoles(actor, tenancyId);
            if (ReservedRoles.isReserved(role.name())) {
                throw new IllegalArgumentException("'" + role.name() + "' is a reserved role name");
            }
            for (String key : role.permissions()) {
                if (catalog.find(key).filter(catalog::isGrantable).isEmpty()) {
                    throw new IllegalArgumentException(
                            "a role may only hold grantable permissions; '" + key + "' is not");
                }
            }
            List<Role> updated = new ArrayList<>();
            boolean replaced = false;
            for (Role existing : roleList(actor.realm(), tenancyId)) {
                if (existing.name().equals(role.name())) {
                    updated.add(role);
                    replaced = true;
                } else {
                    updated.add(existing);
                }
            }
            if (!replaced) {
                updated.add(role);
            }
            saveRoles(actor.realm(), tenancyId, updated); // RoleStore re-validates seal/refs/cycle
        });
    }

    /**
     * Deletes a custom role. Reserved/system role keys cannot be deleted (they are code-defined, not stored),
     * and the delete is rejected by the store's re-validation if another custom role still inherits the target
     * (removing it would leave a dangling inherit edge — an unknown-ref violation). Reserved to administrators.
     * The role is also removed from the grants file ({@link GrantFile#withoutRole}): its holders lose what it gave
     * them, and a role created later under the same name starts with no holders and no deltas.
     *
     * @throws IllegalArgumentException if {@code roleName} is a reserved key or is not a defined custom role
     * @throws IllegalStateException    if another role still inherits it (the graph rejects the dangling edge);
     *                                  nothing is written then
     * @throws org.springframework.security.access.AccessDeniedException if the actor may not manage roles
     */
    public void deleteRole(RunwayUserDetails actor, String tenancyId, String roleName) {
        inScope(tenancyId, () -> {
            requireManageRoles(actor, tenancyId);
            if (ReservedRoles.isReserved(roleName)) {
                throw new IllegalArgumentException("'" + roleName + "' is a reserved role and cannot be deleted");
            }
            List<Role> current = roleList(actor.realm(), tenancyId);
            if (current.stream().noneMatch(role -> role.name().equals(roleName))) {
                throw new IllegalArgumentException("no such custom role: " + roleName);
            }
            List<Role> remaining = current.stream().filter(role -> !role.name().equals(roleName)).toList();
            RoleGraph.of(remaining); // rejects BEFORE any write if another role still inherits it (dangling ref)
            // Grants first, then roles: two files, two atomic writes, in one critical section (the scope's lock), so no
            // assignment of this role lands between them. A role name the graph no longer knows still resolves to
            // itself (built-in tiers rely on that), so a role gone from the graph but left on its holders keeps giving
            // them its console grants, and a retry finds no such role. In this order a failed roles write leaves the
            // role defined with no holders and no deltas (less access, never more), and the delete can simply be run
            // again.
            updateGrants(actor.realm(), tenancyId, file -> file.withoutRole(roleName));
            saveRoles(actor.realm(), tenancyId, remaining); // re-validates the same graph
        });
    }

    /**
     * Adds an inherit edge {@code roleName → parentName}: {@code roleName} gains {@code parentName}'s effective
     * permissions (the DAG accumulates downward, {@link RoleGraph}). Both must be existing custom roles of the
     * actor's realm; the resulting graph is re-validated by the {@link RoleStore}, so a <b>cycle</b> (or a seal
     * / unknown-ref violation) is rejected there — this method never bypasses the cycle check. Idempotent: adding
     * an edge that already exists is a no-op. Reserved to administrators.
     *
     * @throws IllegalArgumentException if either role is missing/reserved, they are the same, or the edge would
     *                                  form a cycle (surfaced from {@link RoleGraph} via the store)
     * @throws org.springframework.security.access.AccessDeniedException if the actor may not manage roles
     */
    public void addRoleInherit(RunwayUserDetails actor, String tenancyId, String roleName, String parentName) {
        inScope(tenancyId, () -> {
            requireManageRoles(actor, tenancyId);
            Role role = requireCustomRole(actor, tenancyId, roleName);
            requireCustomRole(actor, tenancyId, parentName); // the inherited role must exist too
            if (roleName.equals(parentName)) {
                throw new IllegalArgumentException("a role cannot inherit itself");
            }
            Set<String> edges = new LinkedHashSet<>(role.inherits());
            if (!edges.add(parentName)) {
                return; // already present — nothing to do
            }
            replaceRole(actor, tenancyId, new Role(role.name(), role.realm(), role.permissions(), edges));
        });
    }

    /**
     * Removes the inherit edge {@code roleName → parentName} (idempotent — removing a missing edge is a no-op).
     * The trimmed graph is always valid (dropping an edge cannot create a cycle or a dangling ref), so this only
     * needs the role to exist. Reserved to administrators.
     *
     * @throws IllegalArgumentException if {@code roleName} is not a defined custom role
     * @throws org.springframework.security.access.AccessDeniedException if the actor may not manage roles
     */
    public void removeRoleInherit(RunwayUserDetails actor, String tenancyId, String roleName, String parentName) {
        inScope(tenancyId, () -> {
            requireManageRoles(actor, tenancyId);
            Role role = requireCustomRole(actor, tenancyId, roleName);
            Set<String> edges = new LinkedHashSet<>(role.inherits());
            if (!edges.remove(parentName)) {
                return; // edge not present — nothing to do
            }
            replaceRole(actor, tenancyId, new Role(role.name(), role.realm(), role.permissions(), edges));
        });
    }

    /** Looks up a defined custom role of the actor's realm/scope, or fails with a clean message. */
    private Role requireCustomRole(RunwayUserDetails actor, String tenancyId, String roleName) {
        return roleList(actor.realm(), tenancyId).stream()
                .filter(role -> role.name().equals(roleName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such custom role: " + roleName));
    }

    /** Replaces one existing custom role in place, re-validating the whole graph through the store. */
    private void replaceRole(RunwayUserDetails actor, String tenancyId, Role role) {
        List<Role> updated = roleList(actor.realm(), tenancyId).stream()
                .map(existing -> existing.name().equals(role.name()) ? role : existing).toList();
        saveRoles(actor.realm(), tenancyId, updated); // RoleStore re-validates seal/refs/cycle
    }

    /** Whether {@code name} is already taken — as a reserved key, a predefined seed role, or a custom role. */
    private boolean nameInUse(Realm realm, String tenancyId, String name) {
        if (ReservedRoles.isReserved(name)) {
            return true;
        }
        boolean predefined = realm == Realm.APPLICATION
                ? name.equals(Roles.SYSTEM_OPERATOR)
                : name.equals(Roles.OPERATOR);
        if (predefined) {
            return true;
        }
        return roleList(realm, tenancyId).stream().anyMatch(role -> role.name().equals(name));
    }

    // --- user assignment --------------------------------------------------------------------

    /** Assigns {@code roleName} to {@code targetId}, enforcing bounded-grant. */
    public void assignRole(RunwayUserDetails actor, String tenancyId, String targetId, String roleName) {
        inScope(tenancyId, () -> {
            requireNotMasterTarget(actor, targetId);
            requireRoleOfActorRealm(actor, roleName);
            if (!mayGrantRole(actor, roleName, tenancyId)) {
                throw new AccessDeniedException("not authorized to grant role '" + roleName + "'");
            }
            if (!ReservedRoles.isReserved(roleName) && !graph(actor.realm(), tenancyId).hasRole(roleName)) {
                throw new IllegalArgumentException("no such role: " + roleName);
            }
            updateGrants(actor.realm(), tenancyId, file -> file.withUserRole(targetId, roleName));
        });
    }

    /**
     * Revokes {@code roleName} from {@code targetId} (the actor must have grant authority over it). Removes the
     * grant-file role assignment (the channel {@link #assignRole} and the create form write custom roles to).
     *
     * <p>A role a user holds through their <b>own record</b> — their access tier ({@code OPERATOR}/{@code ADMIN})
     * — is <b>not</b> revocable here: it is the coarse console-access gate and the user's Spring authority
     * (removing it would lock them out), so it is managed on the Users screen, not the permissions matrix. Such
     * an attempt is rejected with a clear message rather than the old <i>silent no-op</i> (the grant-file delete
     * found nothing to remove, so the role stayed with no feedback).
     */
    public void revokeRole(RunwayUserDetails actor, String tenancyId, String targetId, String roleName) {
        inScope(tenancyId, () -> {
            requireNotMasterTarget(actor, targetId);
            requireRoleOfActorRealm(actor, roleName);
            if (!mayGrantRole(actor, roleName, tenancyId)) {
                throw new AccessDeniedException("not authorized to revoke role '" + roleName + "'");
            }
            if (baseRecordRoles(actor.realm(), tenancyId, targetId).contains(roleName)) {
                throw new IllegalArgumentException("'" + roleName + "' is this user's access tier — set it on the "
                        + "Users screen; it cannot be revoked here");
            }
            updateGrants(actor.realm(), tenancyId, file -> file.withoutUserRole(targetId, roleName));
        });
    }

    /** The roles held in {@code targetId}'s own user record (their access tier) — empty if no such user. */
    private Set<String> baseRecordRoles(Realm realm, String tenancyId, String targetId) {
        List<User> users = realm == Realm.APPLICATION ? appUsers.list() : siteUsers.list(tenancyId);
        return users.stream().filter(u -> u.principalId().equals(targetId)).findFirst()
                .map(User::roles).orElse(Set.of());
    }

    /** Grants {@code permission} directly to {@code targetId}, enforcing grantable + bounded-grant. */
    public void grantPermission(RunwayUserDetails actor, String tenancyId, String targetId, Permission permission) {
        inScope(tenancyId, () -> {
            requireNotMasterTarget(actor, targetId);
            requireRealm(actor, permission.realm());
            if (!mayGrantPermission(actor, permission, tenancyId)) {
                throw new AccessDeniedException("not authorized to grant permission '" + permission.key() + "'");
            }
            updateGrants(actor.realm(), tenancyId, file -> file.withUserPermission(targetId, permission.key()));
        });
    }

    /** Revokes a direct permission grant from {@code targetId}. */
    public void revokePermission(RunwayUserDetails actor, String tenancyId, String targetId, Permission permission) {
        inScope(tenancyId, () -> {
            requireNotMasterTarget(actor, targetId);
            requireRealm(actor, permission.realm());
            if (!mayGrantPermission(actor, permission, tenancyId)) {
                throw new AccessDeniedException("not authorized to revoke permission '" + permission.key() + "'");
            }
            updateGrants(actor.realm(), tenancyId, file -> file.withoutUserPermission(targetId, permission.key()));
        });
    }

    /**
     * Rejects a <b>reserved</b> role of the wrong realm before it can be written to a grant store. Non-reserved
     * roles are already realm-scoped: a custom role is looked up in the actor-realm graph, and a foreign seed
     * role (e.g. {@code SYSTEMOPERATOR} in a tenant) fails the existence check — only the reserved keys, which
     * bypass that check and whose {@code isReserved}/{@code isWildcard} tests are realm-agnostic, need this.
     */
    private static void requireRoleOfActorRealm(RunwayUserDetails actor, String roleName) {
        if (ReservedRoles.isReserved(roleName) && !ReservedRoles.isReservedOfRealm(roleName, actor.realm())) {
            throw new IllegalArgumentException(
                    "role '" + roleName + "' is not a role of the " + actor.realm() + " realm");
        }
    }

    // --- role-level permission deltas --------------------------------------------

    /**
     * Grants {@code permission} to {@code roleName} as a scope delta: if the permission is a declared default of
     * the role, the role's revoke-delta is cleared (the default applies again); otherwise it is added to the
     * role's grant-delta. Reserved to administrators (manage-roles), only for grantable permissions of the
     * actor's realm, and never on a master/wildcard tier (those pass everything — a delta would be inert).
     */
    public void grantToRole(RunwayUserDetails actor, String tenancyId, String roleName, Permission permission) {
        inScope(tenancyId, () -> {
            requireManageRoles(actor, tenancyId);
            requireDeltaTarget(actor, roleName, permission);
            boolean held = heldWithoutDeltas(actor.realm(), tenancyId, roleName, permission);
            updateGrants(actor.realm(), tenancyId, file -> held
                    ? file.withoutRoleRevoke(roleName, permission.key())  // un-revoke a default or stored key
                    : file.withRoleGrant(roleName, permission.key()));    // add beyond what the role holds
        });
    }

    /**
     * Revokes {@code permission} from {@code roleName} as a scope delta: a declared default is recorded in the
     * role's revoke-delta (so a later plugin default change does not silently re-grant it); a non-default simply
     * clears the grant-delta. Same authority and realm rules as {@link #grantToRole}.
     */
    public void revokeFromRole(RunwayUserDetails actor, String tenancyId, String roleName, Permission permission) {
        inScope(tenancyId, () -> {
            requireManageRoles(actor, tenancyId);
            requireDeltaTarget(actor, roleName, permission);
            boolean held = heldWithoutDeltas(actor.realm(), tenancyId, roleName, permission);
            updateGrants(actor.realm(), tenancyId, file -> held
                    ? file.withoutRoleGrant(roleName, permission.key())
                            .withRoleRevoke(roleName, permission.key())   // record the revoke of a default/stored key
                    : file.withoutRoleGrant(roleName, permission.key())); // drop a grant beyond them
        });
    }

    /**
     * Whether {@code roleName} holds {@code permission} before any grant or revoke delta: as a plugin's declared
     * default, or stored on the role in the roles file. Only such a key needs a recorded revoke to take away;
     * until 1.0.0 a stored key was missed here, so revoking it from the console did nothing.
     */
    private boolean heldWithoutDeltas(Realm realm, String tenancyId, String roleName, Permission permission) {
        return catalog.defaultsForRole(roleName).contains(permission.key())
                || graph(realm, tenancyId).ownPermissionKeys(roleName).contains(permission.key());
    }

    private void requireDeltaTarget(RunwayUserDetails actor, String roleName, Permission permission) {
        if (actor.realm() != permission.realm()) {
            throw new IllegalArgumentException("realm mismatch: actor is " + actor.realm()
                    + ", permission is " + permission.realm());
        }
        if (!catalog.isGrantable(permission)) {
            throw new IllegalArgumentException("a role may only hold grantable permissions; '"
                    + permission.key() + "' is master/wildcard-only");
        }
        if (ReservedRoles.isMaster(roleName) || ReservedRoles.isWildcard(roleName)) {
            throw new IllegalArgumentException("'" + roleName + "' is an admin tier — deltas on it are inert");
        }
    }

    // --- bounded-grant authority ----------------------------------------------------

    /** Whether {@code actor} may grant/revoke {@code roleName} in the scope. */
    public boolean mayGrantRole(RunwayUserDetails actor, String roleName, String tenancyId) {
        if (ReservedRoles.isMaster(roleName)) {
            return false; // master keys are never grantable in-app
        }
        if (ReservedRoles.isWildcard(roleName)) {
            return authz.isMaster(actor, tenancyId) || authz.isWildcard(actor, tenancyId);
        }
        return authz.isMaster(actor, tenancyId) || authz.isWildcard(actor, tenancyId)
                || assignedRoleNames(actor, tenancyId).contains(roleName);
    }

    /** Whether {@code actor} may grant/revoke {@code permission} in the scope (grantable + own authority). */
    public boolean mayGrantPermission(RunwayUserDetails actor, Permission permission, String tenancyId) {
        if (!catalog.isGrantable(permission)) {
            return false; // master/wildcard-only is never grantable
        }
        return authz.isMaster(actor, tenancyId) || authz.isWildcard(actor, tenancyId)
                || authz.effectivePermissionKeys(actor, tenancyId).contains(permission.key());
    }

    private void requireManageRoles(RunwayUserDetails actor, String tenancyId) {
        if (!authz.isMaster(actor, tenancyId) && !authz.isWildcard(actor, tenancyId)) {
            throw new AccessDeniedException("only administrators may manage roles");
        }
    }

    private void requireNotMasterTarget(RunwayUserDetails actor, String targetId) {
        if (actor.realm() == Realm.APPLICATION) {
            String pin = security.getAppMasterId();
            if (pin != null && pin.equals(targetId)) {
                throw new AccessDeniedException("the application superadmin's authorization cannot be reassigned");
            }
        }
        // Tenant: the master short-circuits its own assignments, so editing them is inert; the master
        // *identity* is protected by the step-5 user-store guards.
    }

    private static void requireRealm(RunwayUserDetails actor, Realm realm) {
        if (actor.realm() != realm) {
            throw new IllegalArgumentException("realm mismatch: actor is " + actor.realm() + ", target is " + realm);
        }
    }

    private Set<String> assignedRoleNames(RunwayUserDetails actor, String tenancyId) {
        Set<String> names = new HashSet<>(actor.roles());
        names.addAll(grantsFor(actor.realm(), tenancyId).rolesOf(actor.principalId()));
        return names;
    }

    private List<Role> roleList(Realm realm, String tenancyId) {
        return realm == Realm.APPLICATION ? roles.appRoleList() : roles.siteRoleList(tenancyId);
    }

    private RoleGraph graph(Realm realm, String tenancyId) {
        return realm == Realm.APPLICATION ? roles.appRoles() : roles.siteRoles(tenancyId);
    }

    private GrantFile grantsFor(Realm realm, String tenancyId) {
        return realm == Realm.APPLICATION ? grants.appGrants() : grants.siteGrants(tenancyId);
    }

    private void saveRoles(Realm realm, String tenancyId, List<Role> list) {
        if (realm == Realm.APPLICATION) {
            roles.saveAppRoles(list);
        } else {
            roles.saveSiteRoles(tenancyId, list);
        }
    }

    /**
     * Applies {@code change} to the scope's latest grants in one atomic update. The base is the file as it is now, not
     * what was read for the checks; the checks stay valid because the caller holds the scope's lock.
     */
    private void updateGrants(Realm realm, String tenancyId, UnaryOperator<GrantFile> change) {
        if (realm == Realm.APPLICATION) {
            grants.updateAppGrants(change);
        } else {
            grants.updateSiteGrants(tenancyId, change);
        }
    }

    /** Runs {@code work} under the lock of the scope ({@code null} = the application realm); see the class notes. */
    private void inScope(String tenancyId, Runnable work) {
        synchronized (grants.lock(GrantStore.scopeOf(tenancyId))) {
            work.run();
        }
    }
}
