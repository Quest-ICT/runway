package mx.quest.runway.console;

import mx.quest.runway.authz.AuthorizationAdminService;
import mx.quest.runway.authz.Permission;
import mx.quest.runway.authz.PermissionCatalog;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.spi.model.Realm;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The permissions-matrix surface, shared by both realms. The screen lists users with their roles + direct
 * grants and the assignable roles with their plugin-permission deltas; every write goes through
 * {@link AuthorizationAdminService} (bounded-grant), never the stores directly.
 *
 * <p><b>The matrix realm is fixed by the logged-in actor, not a path argument</b> (the load-bearing 9g
 * constraint). A subclass supplies its realm and scope: the tenant matrix ({@code /__admin/permissions}) is
 * driven by a TENANT principal over the bound tenancy; the application matrix ({@code /__app/permissions}) by an
 * APPLICATION principal with no tenancy. Because {@code AuthorizationAdminService} derives the store scope from
 * {@code actor.realm()}, and the two routes live on the two never-crossing security chains, an application
 * operator can <b>never</b> drive a tenant's matrix and vice versa — cross-realm editing is impossible by
 * construction.
 */
public abstract class AbstractPermissionsController {

    protected final PermissionsMatrixService matrix;
    protected final AuthorizationAdminService admin;
    protected final PermissionCatalog catalog;

    protected AbstractPermissionsController(PermissionsMatrixService matrix, AuthorizationAdminService admin,
                                            PermissionCatalog catalog) {
        this.matrix = matrix;
        this.admin = admin;
        this.catalog = catalog;
    }

    /** The realm this controller serves (the same realm as its principals). */
    protected abstract Realm realm();

    /** The bound tenancy id ({@code null} for the application realm). */
    protected abstract String tenancyId();

    /** The route prefix (for redirects after an action). */
    protected abstract String basePath();

    /** The content template name for the matrix screen. */
    protected abstract String contentTemplate();

    /** The i18n title key. */
    protected abstract String titleKey();

    /**
     * Fails closed unless the caller may administer this scope. The whole matrix (every user's roles + grants)
     * is sensitive, so the <b>read</b> view is gated too, not only the mutations — a read-mostly operator must
     * not enumerate the realm's authorization. The subclass supplies the realm-correct gate
     * ({@code PlatformAuthority} / {@code TenantAuthority}).
     */
    protected abstract void requireAdmin(Authentication authentication);

    @GetMapping
    public String view(Model model, Authentication authentication) {
        requireAdmin(authentication);
        model.addAttribute("matrix", matrix.build(realm(), tenancyId()));
        model.addAttribute("permBase", basePath()); // the realm-specific action prefix the forms post to
        model.addAttribute("contentTemplate", contentTemplate());
        model.addAttribute("pageTitleKey", titleKey());
        return "operator/op-layout";
    }

    @PostMapping("/assign-role")
    public String assignRole(@RequestParam String targetId, @RequestParam String roleName,
                             Authentication authentication, RedirectAttributes redirect) {
        return apply(redirect, "Assigned role '" + roleName + "'.",
                () -> admin.assignRole(actor(authentication), tenancyId(), targetId, roleName),
                authentication, "permissions.role-assigned", "user=" + targetId + " role=" + roleName);
    }

    @PostMapping("/revoke-role")
    public String revokeRole(@RequestParam String targetId, @RequestParam String roleName,
                             Authentication authentication, RedirectAttributes redirect) {
        return apply(redirect, "Revoked role '" + roleName + "'.",
                () -> admin.revokeRole(actor(authentication), tenancyId(), targetId, roleName),
                authentication, "permissions.role-revoked", "user=" + targetId + " role=" + roleName);
    }

    @PostMapping("/grant-permission")
    public String grantPermission(@RequestParam String targetId, @RequestParam String permission,
                                  Authentication authentication, RedirectAttributes redirect) {
        Permission resolved = permission(permission);
        return apply(redirect, "Granted permission '" + resolved.key() + "'.",
                () -> admin.grantPermission(actor(authentication), tenancyId(), targetId, resolved),
                authentication, "permissions.permission-granted",
                "user=" + targetId + " permission=" + resolved.key());
    }

    @PostMapping("/revoke-permission")
    public String revokePermission(@RequestParam String targetId, @RequestParam String permission,
                                   Authentication authentication, RedirectAttributes redirect) {
        Permission resolved = permission(permission);
        return apply(redirect, "Revoked permission '" + resolved.key() + "'.",
                () -> admin.revokePermission(actor(authentication), tenancyId(), targetId, resolved),
                authentication, "permissions.permission-revoked",
                "user=" + targetId + " permission=" + resolved.key());
    }

    @PostMapping("/role-grant")
    public String roleGrant(@RequestParam String roleName, @RequestParam String permission,
                            Authentication authentication, RedirectAttributes redirect) {
        Permission resolved = permission(permission);
        return apply(redirect, "Granted '" + resolved.key() + "' to role '" + roleName + "'.",
                () -> admin.grantToRole(actor(authentication), tenancyId(), roleName, resolved),
                authentication, "permissions.role-permission-granted",
                "role=" + roleName + " permission=" + resolved.key());
    }

    @PostMapping("/role-revoke")
    public String roleRevoke(@RequestParam String roleName, @RequestParam String permission,
                             Authentication authentication, RedirectAttributes redirect) {
        Permission resolved = permission(permission);
        return apply(redirect, "Revoked '" + resolved.key() + "' from role '" + roleName + "'.",
                () -> admin.revokeFromRole(actor(authentication), tenancyId(), roleName, resolved),
                authentication, "permissions.role-permission-revoked",
                "role=" + roleName + " permission=" + resolved.key());
    }

    // --- custom-role definition + DAG hierarchy (administrators only) ------------------------

    /**
     * Creates a custom role. {@code name} is required; {@code inherits} (repeatable) seeds optional initial
     * inherit edges to existing custom roles. Delegates to {@link AuthorizationAdminService#createRole}, which
     * validates the name (unique, well-formed, not reserved) and re-validates the graph (seal/refs/cycle) before
     * persisting — a malformed or clashing name comes back as a flash error, never a 500.
     */
    @PostMapping("/create-role")
    public String createRole(@RequestParam String name,
                             @RequestParam(name = "inherits", required = false) java.util.List<String> inherits,
                             Authentication authentication, RedirectAttributes redirect) {
        requireAdmin(authentication); // gate the mutation at the request level (403), like the read view
        java.util.Set<String> parents = inherits == null ? java.util.Set.of() : new java.util.LinkedHashSet<>(inherits);
        return apply(redirect, "Created role '" + name + "'.",
                () -> admin.createRole(actor(authentication), tenancyId(), name, java.util.Set.of(), parents),
                authentication, "permissions.role-created",
                "role=" + name + (parents.isEmpty() ? "" : " inherits=" + String.join(",", parents)));
    }

    /** Deletes a custom role (reserved roles and still-inherited roles are rejected by the service). */
    @PostMapping("/delete-role")
    public String deleteRole(@RequestParam String roleName,
                             Authentication authentication, RedirectAttributes redirect) {
        requireAdmin(authentication);
        return apply(redirect, "Deleted role '" + roleName + "'.",
                () -> admin.deleteRole(actor(authentication), tenancyId(), roleName),
                authentication, "permissions.role-deleted", "role=" + roleName);
    }

    /**
     * Adds an inherit edge {@code roleName → parentName} to the role DAG. The service re-validates through the
     * cycle-checked graph, so a would-be cycle is rejected with a clean flash message (never bypassed here).
     */
    @PostMapping("/add-inherit")
    public String addInherit(@RequestParam String roleName, @RequestParam String parentName,
                             Authentication authentication, RedirectAttributes redirect) {
        requireAdmin(authentication);
        return apply(redirect, "Added inherit '" + roleName + "' → '" + parentName + "'.",
                () -> admin.addRoleInherit(actor(authentication), tenancyId(), roleName, parentName),
                authentication, "permissions.role-inherit-added", "role=" + roleName + " inherits=" + parentName);
    }

    /** Removes an inherit edge {@code roleName → parentName} from the role DAG (idempotent). */
    @PostMapping("/remove-inherit")
    public String removeInherit(@RequestParam String roleName, @RequestParam String parentName,
                                Authentication authentication, RedirectAttributes redirect) {
        requireAdmin(authentication);
        return apply(redirect, "Removed inherit '" + roleName + "' → '" + parentName + "'.",
                () -> admin.removeRoleInherit(actor(authentication), tenancyId(), roleName, parentName),
                authentication, "permissions.role-inherit-removed",
                "role=" + roleName + " inherits=" + parentName);
    }

    /**
     * Records one <b>successful</b> matrix change to this realm's audit journal — the application activity log
     * for the application matrix, the bound site's audit log for a tenant matrix. Called after the change was
     * persisted, never for a rejected one (a refused grant changed nothing, and the actor already saw the error).
     *
     * @param actor  the signed-in principal's username
     * @param action the stable {@code permissions.*} action key
     * @param detail the target (user principal id / role) and the role or permission involved
     */
    protected abstract void audit(String actor, String action, String detail);

    /**
     * Runs an admin action, confirming success with a {@code flash} message and turning a bounded-grant/
     * validation failure into a flash {@code error} (never a 500) — the op-layout shell renders both regions
     * (keyed {@code flash}/{@code error}). Every matrix mutation routes through here, so all confirm uniformly
     * and every successful one is {@linkplain #audit audited}.
     */
    private String apply(RedirectAttributes redirect, String successMessage, Runnable action,
                         Authentication authentication, String auditAction, String auditDetail) {
        try {
            action.run();
            audit(authentication == null ? "" : authentication.getName(), auditAction, auditDetail);
            redirect.addFlashAttribute("flash", successMessage);
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + basePath();
    }

    private RunwayUserDetails actor(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof RunwayUserDetails principal) {
            return principal;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not authenticated in this realm");
    }

    private Permission permission(String key) {
        return catalog.find(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no such permission: " + key));
    }
}
