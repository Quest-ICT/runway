package mx.quest.runway.authz;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import mx.quest.runway.security.RunwaySecurityProperties;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.spi.model.Realm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The single authorization check — every guarded surface routes through {@link #mayAccess}. The
 * precedence is fixed and <b>fails closed</b>:
 *
 * <ol>
 *   <li>the <b>master</b> (the pinned application principal, or a tenant-master assigned to the resolved
 *       tenancy) passes everything;</li>
 *   <li>a <b>wildcard admin</b> (holding the realm's reserved wildcard key) passes everything — the
 *       master-<em>only</em> operations are a separate target-based guard, not a permission;</li>
 *   <li>else the concrete permission must be in the principal's <b>effective set</b> (the union of every
 *       assigned role's transitive downward closure, plus direct grants);</li>
 *   <li>else <b>deny</b>. An undeclared permission, or one of the wrong realm, denies.</li>
 * </ol>
 *
 * <p>It re-reads the authoritative role/grant stores <b>per call</b> (never a cached session claim), and the
 * master/wildcard short-circuit passes <b>only for the resolved tenancy</b> — so a tenant-master assigned to
 * several tenancies gets no short-circuit on one it does not hold. The grant reason is logged for audit.
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private final RoleStore roles;
    private final GrantStore grants;
    private final PermissionCatalog catalog;
    private final RunwaySecurityProperties security;

    public AuthorizationService(RoleStore roles, GrantStore grants, PermissionCatalog catalog,
                                RunwaySecurityProperties security) {
        this.roles = Objects.requireNonNull(roles, "roles");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.security = Objects.requireNonNull(security, "security");
    }

    /**
     * Whether {@code principal} may exercise {@code permission} in {@code tenancyId} (null/ignored for the
     * application realm). Fail-closed: an undeclared permission, a realm mismatch, a tenant permission with no
     * resolved tenancy, or no resolved grant denies.
     */
    public boolean mayAccess(RunwayUserDetails principal, Permission permission, String tenancyId) {
        if (principal == null || permission == null) {
            return false;
        }
        if (principal.realm() != permission.realm()) {
            return false; // realms never cross
        }
        if (permission.realm() == Realm.TENANT && tenancyId == null) {
            return false; // a tenant permission requires a resolved tenancy — no tenancy, no access
        }
        if (!catalog.isDeclared(permission.key())) {
            return false; // an undeclared permission can never pass
        }
        if (isMaster(principal, tenancyId)) {
            return granted(principal, permission, "master");
        }
        if (isWildcard(principal, tenancyId)) {
            return granted(principal, permission, "wildcard");
        }
        if (effectivePermissionKeys(principal, tenancyId).contains(permission.key())) {
            return granted(principal, permission, "permission");
        }
        return false;
    }

    /**
     * Whether {@code principal} is the master for the scope — the pinned application principal, or a
     * tenant-master assigned to the resolved tenancy (the tenancy-scoped short-circuit).
     */
    public boolean isMaster(RunwayUserDetails principal, String tenancyId) {
        if (principal.realm() == Realm.APPLICATION) {
            String pin = security.getAppMasterId();
            return pin != null && !pin.isBlank() && pin.equals(principal.principalId());
        }
        return principal.master() && tenancyId != null && principal.assignedTo(tenancyId);
    }

    /** Whether {@code principal} holds the realm's wildcard role in the resolved scope. */
    public boolean isWildcard(RunwayUserDetails principal, String tenancyId) {
        return assignedRoleNames(principal, tenancyId).contains(ReservedRoles.wildcardKey(principal.realm()));
    }

    /**
     * The principal's effective permission keys: direct grants, plus — over the closure of every assigned role
     * (the role and what it inherits), per role — {@code (stored ∪ declared defaults ∪ roleGrants) − roleRevokes}.
     * The role deltas realize the locked
     * "stored as DELTAS from the declared defaults" model: a plugin changing its {@code defaultRoles}
     * propagates wherever a scope has not explicitly overridden it.
     */
    public Set<String> effectivePermissionKeys(RunwayUserDetails principal, String tenancyId) {
        RoleGraph graph = graphFor(principal.realm(), tenancyId);
        GrantFile grantFile = grantsFor(principal.realm(), tenancyId);

        Set<String> keys = new HashSet<>(grantFile.permissionsOf(principal.principalId()));
        Set<String> roleClosure = new HashSet<>();
        for (String roleName : assignedRoleNames(principal, tenancyId)) {
            roleClosure.addAll(graph.effectiveRoleNames(roleName)); // role names in the closure (incl. predefined)
        }
        for (String roleName : roleClosure) {
            keys.addAll(rolePermissions(graph, grantFile, roleName));
        }
        return keys;
    }

    /**
     * One role's own contribution: {@code (stored ∪ declared defaults ∪ role grants) − role revokes}.
     *
     * <p>Revoke is applied LAST so a deny always wins over a grant of the same key on the same role (fail-closed),
     * even in a hand-edited grant file that names a key in both delta sets. It covers the permissions stored on
     * the role in the roles file too: until 1.0.0 those were added outside the revoke, so a key typed into the
     * file showed as granted and could not be revoked from the console.
     */
    private Set<String> rolePermissions(RoleGraph graph, GrantFile grantFile, String roleName) {
        Set<String> rolePermissions = new HashSet<>(graph.ownPermissionKeys(roleName));
        rolePermissions.addAll(catalog.defaultsForRole(roleName));
        rolePermissions.addAll(grantFile.roleGrantsOf(roleName));
        rolePermissions.removeAll(grantFile.roleRevokesOf(roleName));
        return rolePermissions;
    }

    /**
     * A single role's effective grantable permission keys in {@code scope} — the same fold {@link #mayAccess}
     * applies for a user holding <em>only</em> this role: per role in the closure,
     * {@code (stored ∪ declared defaults ∪ role grants) − role revokes}. The <b>wildcard</b> tier
     * ({@code ADMIN}/{@code SYSTEMADMIN}) and the <b>master</b> hold <em>every</em> grantable permission of the
     * realm — they pass by short-circuit, not by an explicit key set — so this reports the full grantable set
     * for them. The permissions matrix uses this to show each role's <b>true</b> reach (so the all-access
     * wildcard no longer renders as an empty row, and a role's granted core permissions appear).
     *
     * @param realm     the realm the role belongs to (never {@code null})
     * @param tenancyId the bound tenancy for a tenant-realm role ({@code null} for the application realm)
     * @param roleName  the role whose effective grantable keys to compute (never {@code null})
     * @return the grantable permission keys the role effectively holds (never {@code null})
     */
    public Set<String> effectiveRolePermissionKeys(Realm realm, String tenancyId, String roleName) {
        if (ReservedRoles.isMaster(roleName) || ReservedRoles.isWildcard(roleName)) {
            Set<String> all = new HashSet<>();
            for (Permission permission : catalog.grantable(realm)) {
                all.add(permission.key());
            }
            return all;
        }
        RoleGraph graph = graphFor(realm, tenancyId);
        GrantFile grantFile = grantsFor(realm, tenancyId);
        Set<String> keys = new HashSet<>();
        for (String roleInClosure : graph.effectiveRoleNames(roleName)) {
            keys.addAll(rolePermissions(graph, grantFile, roleInClosure));
        }
        return keys;
    }

    private Set<String> assignedRoleNames(RunwayUserDetails principal, String tenancyId) {
        Set<String> names = new HashSet<>(principal.roles());
        names.addAll(grantsFor(principal.realm(), tenancyId).rolesOf(principal.principalId()));
        return names;
    }

    private RoleGraph graphFor(Realm realm, String tenancyId) {
        return realm == Realm.APPLICATION ? roles.appRoles() : roles.siteRoles(tenancyId);
    }

    private GrantFile grantsFor(Realm realm, String tenancyId) {
        return realm == Realm.APPLICATION ? grants.appGrants() : grants.siteGrants(tenancyId);
    }

    private static boolean granted(RunwayUserDetails principal, Permission permission, String reason) {
        log.debug("mayAccess granted: {} for '{}' on {} via {}",
                permission.key(), principal.getUsername(), permission.realm(), reason);
        return true;
    }
}
