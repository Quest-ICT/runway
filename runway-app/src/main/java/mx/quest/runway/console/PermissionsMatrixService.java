package mx.quest.runway.console;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import mx.quest.runway.authz.GrantFile;
import mx.quest.runway.authz.GrantStore;
import mx.quest.runway.authz.Permission;
import mx.quest.runway.authz.PermissionCatalog;
import mx.quest.runway.authz.ReservedRoles;
import mx.quest.runway.authz.Role;
import mx.quest.runway.authz.RoleStore;
import mx.quest.runway.security.AppUserStore;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.SiteUserStore;
import mx.quest.runway.security.User;
import mx.quest.runway.spi.model.Realm;
import org.springframework.stereotype.Service;

/**
 * Builds the read model for the permissions matrix — users with their assigned roles + direct grants, the
 * assignable roles, the grantable permissions, and each role's <b>true effective</b> permission set (via
 * {@link mx.quest.runway.authz.AuthorizationService#effectiveRolePermissionKeys} — the full grantable set for
 * the wildcard Administrator tier). The scope is given by the caller's realm + (for the tenant
 * realm) the bound tenancy; the controllers always pass the realm of the <b>logged-in actor</b>, so the matrix
 * a tenant manager sees and edits is their own tenancy's and never crosses into the application realm.
 *
 * <p>Pure read model; all <b>writes</b> go through {@code AuthorizationAdminService} (bounded-grant), never
 * this service or the stores directly.
 */
@Service
public class PermissionsMatrixService {

    private final AppUserStore appUsers;
    private final SiteUserStore siteUsers;
    private final RoleStore roles;
    private final GrantStore grants;
    private final PermissionCatalog catalog;
    private final mx.quest.runway.authz.AuthorizationService authz;

    public PermissionsMatrixService(AppUserStore appUsers, SiteUserStore siteUsers, RoleStore roles,
                                    GrantStore grants, PermissionCatalog catalog,
                                    mx.quest.runway.authz.AuthorizationService authz) {
        this.appUsers = appUsers;
        this.siteUsers = siteUsers;
        this.roles = roles;
        this.grants = grants;
        this.catalog = catalog;
        this.authz = authz;
    }

    /** A user row: identity plus the roles and direct permissions it holds in this scope. */
    public record UserRow(String principalId, String username, String displayName, boolean master,
                          List<String> roles, List<String> directPermissions) {
    }

    /**
     * A role row: the role, whether it is the all-access {@code wildcard} tier (Administrator), the grantable
     * permission keys it <b>effectively</b> holds (stored + granted + defaults − revokes over its closure; the
     * <em>full</em> grantable set for the wildcard), and — for a <b>custom</b> role — the names of the roles it
     * directly inherits (its outgoing DAG edges). Predefined roles never carry inherit edges ({@code inherits}
     * is empty), so the hierarchy UI only offers edges on custom rows.
     */
    public record RoleRow(String name, boolean custom, boolean wildcard, List<String> heldPermissions,
                          List<String> inherits) {
    }

    /**
     * The whole matrix for a scope. {@code customRoles} is the subset of role names that are editable custom
     * roles (the delete + hierarchy affordances apply only to these); it is the eligible set for both ends of an
     * inherit edge.
     */
    public record Matrix(Realm realm, String tenancyId, List<UserRow> users, List<String> assignableRoles,
                         List<Permission> grantablePermissions, List<RoleRow> roles, List<String> customRoles) {
    }

    /** Builds the matrix for {@code realm} (+ bound {@code tenancyId} for the tenant realm). */
    public Matrix build(Realm realm, String tenancyId) {
        GrantFile grantFile = realm == Realm.APPLICATION ? grants.appGrants() : grants.siteGrants(tenancyId);
        List<User> users = realm == Realm.APPLICATION ? appUsers.list() : siteUsers.list(tenancyId);

        List<UserRow> userRows = new ArrayList<>();
        for (User user : users) {
            Set<String> assigned = new TreeSet<>(user.roles());
            assigned.addAll(grantFile.rolesOf(user.principalId()));
            userRows.add(new UserRow(user.principalId(), user.username(), user.displayName(), user.master(),
                    List.copyOf(assigned), new ArrayList<>(new TreeSet<>(grantFile.permissionsOf(user.principalId())))));
        }

        List<String> assignableRoles = assignableRoles(realm, tenancyId);
        List<Permission> grantable = new ArrayList<>(catalog.grantable(realm));

        // Inherit edges by role name, read from the raw custom-role list (predefined roles have none).
        Map<String, List<String>> inheritsByRole = new HashMap<>();
        List<String> customRoles = new ArrayList<>();
        for (Role role : (realm == Realm.APPLICATION ? roles.appRoleList() : roles.siteRoleList(tenancyId))) {
            customRoles.add(role.name());
            inheritsByRole.put(role.name(), new ArrayList<>(new TreeSet<>(role.inherits())));
        }

        List<RoleRow> roleRows = new ArrayList<>();
        for (String roleName : assignableRoles) {
            // The role's TRUE effective reach (the same fold mayAccess applies): stored + granted + defaults −
            // revokes over its closure, and the FULL grantable set for the wildcard tier. This is why the
            // all-access Administrator no longer renders as an empty row and a role's granted core permissions
            // show — the old display listed only plugin-default control points, inverting apparent role power.
            Set<String> held = new TreeSet<>(authz.effectiveRolePermissionKeys(realm, tenancyId, roleName));
            boolean custom = !isPredefined(roleName, realm);
            boolean wildcard = ReservedRoles.isWildcard(roleName);
            roleRows.add(new RoleRow(roleName, custom, wildcard, List.copyOf(held),
                    inheritsByRole.getOrDefault(roleName, List.of())));
        }
        return new Matrix(realm, tenancyId, userRows, assignableRoles, grantable, roleRows, List.copyOf(customRoles));
    }

    /** Assignable roles: the realm's ordinary predefined roles (never the master) plus every custom role. */
    private List<String> assignableRoles(Realm realm, String tenancyId) {
        Set<String> names = new LinkedHashSet<>(predefinedAssignable(realm));
        for (Role role : (realm == Realm.APPLICATION ? roles.appRoleList() : roles.siteRoleList(tenancyId))) {
            names.add(role.name());
        }
        return List.copyOf(names);
    }

    private static List<String> predefinedAssignable(Realm realm) {
        // The master tier is never assignable in-app; the wildcard + operator tiers are.
        return realm == Realm.APPLICATION
                ? List.of(Roles.SYSTEM_ADMIN, Roles.SYSTEM_OPERATOR)
                : List.of(Roles.ADMIN, Roles.OPERATOR);
    }

    private static boolean isPredefined(String roleName, Realm realm) {
        return ReservedRoles.isReserved(roleName) || predefinedAssignable(realm).contains(roleName);
    }
}
