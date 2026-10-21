package mx.quest.runway.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import mx.quest.runway.authz.AuthorizationAdminService;
import mx.quest.runway.authz.Role;
import mx.quest.runway.authz.RoleStore;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.SiteUserStore;
import mx.quest.runway.security.User;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.spi.model.Realm;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The tenant console's <b>Users</b> screen ({@code /__admin/users}): the bound tenancy's sub-users. List /
 * create / edit (display name, recovery email, access tier) / suspend / resume / reset-password / delete over
 * {@link SiteUserStore} (edit and suspend through {@link ConsoleUserService}), always scoped to the resolved tenancy — a tenant
 * manager can never reach another tenancy's users. A created sub-user belongs to exactly this tenancy.
 *
 * <p><b>Gates.</b> The screen opens with {@code MANAGE_USERS} or {@code DELETE_USERS}; create / edit / suspend /
 * resume / reset-password need {@code MANAGE_USERS}, delete needs {@code DELETE_USERS} (method security, so the
 * site's superadmin and administrators pass as before and an operator needs the grant). On top of the verb, every
 * action on an existing user passes {@link SiteUserAuthority#requireMayActOn}: a holder who is not a site
 * administrator reaches only users who hold nothing beyond their own permissions — never an administrator. A
 * non-administrator creates {@code OPERATOR}-tier users only, without custom roles (role assignment stays with
 * the administrators). A new user's login name must be free on this site — not held, case-insensitively, by
 * another user of the site or by a tenant superadmin assigned to it ({@link SiteUserStore#save}); otherwise a
 * {@code MANAGE_USERS} holder could create a user named like the superadmin and sign in under that name.
 *
 * <p><b>A user's role model has two layers.</b> The <em>access tier</em> ({@code OPERATOR} or {@code ADMIN})
 * lives in the user's own record ({@code User.roles()}) — it is the coarse console-access gate (see
 * {@code TenantSiteAuthorizationManager}) and the Spring authority, so every console user must have exactly
 * one. Any additional <em>custom roles</em> are layered on through the per-tenancy grant file via
 * {@link AuthorizationAdminService#assignRole} — the same channel the permissions matrix uses, so create and
 * the matrix agree on where a custom-role assignment is stored (and a later revoke can find it).
 */
@Controller
@RequestMapping("/__admin/users")
public class TenantUsersController {

    private final SiteUserStore siteUsers;
    private final SiteResolver siteResolver;
    private final PasswordEncoder passwordEncoder;
    private final SiteUserAuthority userAuthority;
    private final RoleStore roleStore;
    private final AuthorizationAdminService admin;
    private final ConsoleUserService userService;
    private final SiteLogService siteLog;
    private final MessageSource messages;

    public TenantUsersController(SiteUserStore siteUsers, SiteResolver siteResolver, PasswordEncoder passwordEncoder,
                                 SiteUserAuthority userAuthority, RoleStore roleStore, AuthorizationAdminService admin,
                                 ConsoleUserService userService, SiteLogService siteLog,
                                 @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.siteUsers = siteUsers;
        this.siteResolver = siteResolver;
        this.passwordEncoder = passwordEncoder;
        this.userAuthority = userAuthority;
        this.roleStore = roleStore;
        this.admin = admin;
        this.userService = userService;
        this.siteLog = siteLog;
        this.messages = messages;
    }

    /**
     * The users list. Every affordance is shown only where the action behind it would be allowed (UX only — each
     * POST re-checks): {@code canManage}/{@code canDelete} for the verbs, {@code siteAdmin} for the tier choice
     * and custom roles, {@code manageable} for the rows this manager may act on.
     */
    @GetMapping
    @PreAuthorize("@authz.may('MANAGE_USERS') or @authz.may('DELETE_USERS')")
    public String list(Authentication authentication, Model model) {
        String tenancyId = tenancy();
        RunwayUserDetails actor = actor(authentication);
        List<User> users = siteUsers.list(tenancyId);
        model.addAttribute("users", users);
        // The signed-in manager's own row offers no Suspend and no tier change (the service refuses both).
        model.addAttribute("selfId", actor.principalId());
        boolean siteAdmin = userAuthority.isSiteAdmin(actor, tenancyId);
        model.addAttribute("siteAdmin", siteAdmin);
        model.addAttribute("canManage", userAuthority.mayManage(actor, tenancyId));
        model.addAttribute("canDelete", userAuthority.mayDelete(actor, tenancyId));
        model.addAttribute("manageable", users.stream().filter(u -> userAuthority.mayActOn(actor, tenancyId, u))
                .map(User::principalId).collect(java.util.stream.Collectors.toSet()));
        model.addAttribute("customRoles", siteAdmin
                ? roleStore.siteRoleList(tenancyId).stream().map(Role::name).toList() : List.of());
        model.addAttribute("contentTemplate", "operator/admin/users");
        model.addAttribute("pageTitleKey", "op.users.title");
        return "operator/op-layout";
    }

    /**
     * Creates a user of this site. A non-administrator ({@code MANAGE_USERS} granted) creates {@code OPERATOR}-tier
     * users only and cannot layer custom roles; the tier is checked with the same bounded-grant rule as a tier
     * change ({@link AuthorizationAdminService#mayGrantRole}).
     */
    @PostMapping("/create")
    @PreAuthorize("@authz.may('MANAGE_USERS')")
    public String create(@RequestParam String username, @RequestParam String password, @RequestParam String tier,
                         @RequestParam(name = "roles", required = false) List<String> roles,
                         @RequestParam(required = false) String displayName, @RequestParam(required = false) String email,
                         Authentication authentication, RedirectAttributes redirect) {
        String tenancyId = tenancy();
        RunwayUserDetails actor = actor(authentication);
        try {
            if (!Roles.OPERATOR.equals(tier) && !Roles.ADMIN.equals(tier)) {
                throw new IllegalArgumentException("invalid access tier '" + tier + "'");
            }
            if (!admin.mayGrantRole(actor, tier, tenancyId)) {
                throw new IllegalArgumentException(message("op.users.error.tierNotAllowed", null));
            }
            boolean siteAdmin = userAuthority.isSiteAdmin(actor, tenancyId);
            // Only real custom roles of this tenancy may be layered on — reject anything else (a tier here, or
            // a stale/foreign name) before touching the store, so the create never half-succeeds.
            Set<String> customNames = roleStore.siteRoleList(tenancyId).stream().map(Role::name)
                    .collect(java.util.stream.Collectors.toSet());
            List<String> requested = new ArrayList<>();
            if (roles != null) {
                for (String r : roles) {
                    if (r != null && !r.isBlank() && !requested.contains(r)) {
                        if (!siteAdmin) {
                            throw new IllegalArgumentException(message("op.users.error.rolesAdminOnly", null));
                        }
                        if (!customNames.contains(r)) {
                            throw new IllegalArgumentException("no such custom role '" + r + "'");
                        }
                        requested.add(r);
                    }
                }
            }
            String newId = UUID.randomUUID().toString();
            siteUsers.save(tenancyId, new User(newId, username.trim(),
                    passwordEncoder.encode(password), displayName == null || displayName.isBlank() ? username.trim()
                    : displayName.trim(), email == null ? "" : email.trim(), true, false, Realm.TENANT,
                    Set.of(tier), List.of()));
            List<String> assigned = new ArrayList<>();
            for (String r : requested) {
                try {
                    admin.assignRole(actor, tenancyId, newId, r); // grant file — bounded-grant enforced here
                    assigned.add(r);
                } catch (RuntimeException ex) {
                    // Roll back COMPLETELY: strip grant-file roles written so far, then delete the user — no
                    // orphaned principalId→role mapping left behind on a half-created user.
                    for (String done : assigned) {
                        try {
                            admin.revokeRole(actor, tenancyId, newId, done);
                        } catch (RuntimeException ignored) {
                            // best-effort cleanup; the user delete below removes the principal regardless
                        }
                    }
                    siteUsers.delete(tenancyId, newId);
                    throw new IllegalArgumentException("could not assign role '" + r + "': " + ex.getMessage());
                }
            }
            siteLog.audit(tenancyId, authentication.getName(), "user.created", username.trim() + " (" + tier + ")"
                    + (assigned.isEmpty() ? "" : " roles=" + String.join(",", assigned)));
            redirect.addFlashAttribute("flash", "Created user '" + username.trim() + "'"
                    + (requested.isEmpty() ? "." : " with role(s): " + String.join(", ", requested) + "."));
        } catch (mx.quest.runway.security.UsernameTakenException e) {
            // The name is held by another user of this site or by a superadmin assigned to it (the store checks
            // both): refused, so no one can sign in under a name that is already someone else's here.
            redirect.addFlashAttribute("error", message("op.users.error.usernameTaken", e.username()));
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/__admin/users";
    }

    private RunwayUserDetails actor(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof RunwayUserDetails principal) {
            return principal;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not authenticated in this realm");
    }

    /**
     * Edits one of this tenancy's users: display name, recovery email and — except for the manager's own account —
     * access tier. Guards (tenant scope, self, bounded grant) live in {@link ConsoleUserService}. Recorded in the
     * site's audit log.
     */
    @PostMapping("/{principalId}/edit")
    @PreAuthorize("@authz.may('MANAGE_USERS')")
    public String edit(@PathVariable String principalId, @RequestParam(required = false) String displayName,
                       @RequestParam(required = false) String email, @RequestParam(required = false) String tier,
                       Authentication authentication, RedirectAttributes redirect) {
        String tenancyId = tenancy();
        try {
            requireMayActOn(authentication, tenancyId, principalId);
            User updated = userService.updateSiteUser(actor(authentication), tenancyId, principalId, displayName,
                    email, tier);
            siteLog.audit(tenancyId, authentication.getName(), "user.updated",
                    updated.username() + " (" + String.join(",", new java.util.TreeSet<>(updated.roles())) + ")");
            redirect.addFlashAttribute("flash", message("op.users.edit.saved", updated.username()));
        } catch (ConsoleUserService.UserEditException e) {
            redirect.addFlashAttribute("error", error(e));
        } catch (IllegalStateException | java.io.UncheckedIOException e) {
            redirect.addFlashAttribute("error", e.getMessage()); // a store guard or a failed write: never a 500
        }
        return "redirect:/__admin/users";
    }

    /** Suspends one of this tenancy's users: sign-in refused, and an open session is dropped on its next request. */
    @PostMapping("/{principalId}/suspend")
    @PreAuthorize("@authz.may('MANAGE_USERS')")
    public String suspend(@PathVariable String principalId, Authentication authentication,
                          RedirectAttributes redirect) {
        return setEnabled(principalId, false, authentication, redirect);
    }

    /** Resumes a suspended user of this tenancy (sign-in allowed again; the password is unchanged). */
    @PostMapping("/{principalId}/resume")
    @PreAuthorize("@authz.may('MANAGE_USERS')")
    public String resume(@PathVariable String principalId, Authentication authentication,
                         RedirectAttributes redirect) {
        return setEnabled(principalId, true, authentication, redirect);
    }

    private String setEnabled(String principalId, boolean enabled, Authentication authentication,
                              RedirectAttributes redirect) {
        String tenancyId = tenancy();
        try {
            requireMayActOn(authentication, tenancyId, principalId);
            User updated = userService.setSiteUserEnabled(actor(authentication), tenancyId, principalId, enabled);
            siteLog.audit(tenancyId, authentication.getName(), enabled ? "user.resumed" : "user.suspended",
                    updated.username());
            redirect.addFlashAttribute("flash",
                    message(enabled ? "op.users.resumed" : "op.users.suspended", updated.username()));
        } catch (ConsoleUserService.UserEditException e) {
            redirect.addFlashAttribute("error", error(e));
        } catch (IllegalStateException | java.io.UncheckedIOException e) {
            redirect.addFlashAttribute("error", e.getMessage()); // a store guard or a failed write: never a 500
        }
        return "redirect:/__admin/users";
    }

    private String message(String key, Object arg) {
        return messages.getMessage(key, arg == null ? null : new Object[] {arg}, key, LocaleContextHolder.getLocale());
    }

    /**
     * The target bound ({@link SiteUserAuthority#requireMayActOn}) for an action on an existing user of this site.
     * An unknown id passes through, so the action reports "no such user" exactly as before.
     */
    private void requireMayActOn(Authentication authentication, String tenancyId, String principalId) {
        java.util.Optional<User> target = siteUsers.findByPrincipalId(tenancyId, principalId);
        if (target.isPresent()) {
            userAuthority.requireMayActOn(actor(authentication), tenancyId, target.get());
        }
    }

    private String error(ConsoleUserService.UserEditException e) {
        return messages.getMessage("op.users.error." + e.code(), null, e.getMessage(),
                LocaleContextHolder.getLocale());
    }

    /**
     * Sets one of this site's users' password. Every open session of that user on this site ends on its next
     * request ({@code AccountStateFilter} sees the hash change); an administrator resetting their OWN password
     * here keeps this session, like the Account screen.
     */
    @PostMapping("/reset-password")
    @PreAuthorize("@authz.may('MANAGE_USERS')")
    public String resetPassword(@RequestParam String principalId, @RequestParam String password,
                                Authentication authentication, RedirectAttributes redirect,
                                jakarta.servlet.http.HttpServletRequest request) {
        String tenancyId = tenancy();
        try {
            User user = siteUsers.list(tenancyId).stream().filter(u -> u.principalId().equals(principalId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no such user"));
            userAuthority.requireMayActOn(actor(authentication), tenancyId, user);
            User updated = user.withPasswordHash(passwordEncoder.encode(password));
            siteUsers.save(tenancyId, updated);
            mx.quest.runway.security.AccountStateFilter.rebindIfSelf(request, updated);
            siteLog.audit(tenancyId, authentication.getName(), "user.password-reset", user.username());
            redirect.addFlashAttribute("flash", "Reset the password for '" + user.username() + "'.");
        } catch (ConsoleUserService.UserEditException e) {
            redirect.addFlashAttribute("error", error(e));
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/__admin/users";
    }

    /** Deletes one of this site's users — {@code DELETE_USERS}, within the same target bound as the other actions. */
    @PostMapping("/{principalId}/delete")
    @PreAuthorize("@authz.may('DELETE_USERS')")
    public String delete(@PathVariable String principalId, Authentication authentication, RedirectAttributes redirect) {
        String tenancyId = tenancy();
        java.util.Optional<User> target = siteUsers.findByPrincipalId(tenancyId, principalId);
        if (target.isPresent() && !userAuthority.mayActOn(actor(authentication), tenancyId, target.get())) {
            redirect.addFlashAttribute("error", message("op.users.error.beyondAuthority", null));
            return "redirect:/__admin/users";
        }
        String username = target.map(User::username).orElse(principalId);
        boolean deleted = siteUsers.delete(tenancyId, principalId);
        if (deleted) {
            siteLog.audit(tenancyId, authentication.getName(), "user.deleted", username);
        }
        redirect.addFlashAttribute(deleted ? "flash" : "error", deleted ? "Deleted the user." : "No such user.");
        return "redirect:/__admin/users";
    }

    private String tenancy() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy")).id();
    }
}
