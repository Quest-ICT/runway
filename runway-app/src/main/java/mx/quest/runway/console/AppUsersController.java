package mx.quest.runway.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import mx.quest.runway.authz.AuthorizationAdminService;
import mx.quest.runway.authz.Role;
import mx.quest.runway.authz.RoleStore;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.security.AppUserStore;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.RunwaySecurityProperties;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.User;
import mx.quest.runway.spi.model.Realm;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
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
 * The application-realm <b>App users</b> screen ({@code /__app/users}): the platform operators. List, create
 * (as {@code SYSTEMADMIN} or {@code SYSTEMOPERATOR} — never a second superadmin, which the store's fail-closed
 * validation forbids), edit (display name, recovery email, access tier), suspend / resume, reset a password, and
 * delete. Thin wiring of {@link AppUserStore} and {@link ConsoleUserService}; a platform operation gated to the
 * master/wildcard admin. The <b>pinned application superadmin</b> can never be deleted, suspended or have its tier
 * changed here (the undeletable-last-master rule) — its identity is guarded structurally; only its profile
 * (display name, email) is editable.
 */
@Controller
@RequestMapping("/__app/users")
public class AppUsersController {

    private final AppUserStore users;
    private final PasswordEncoder passwordEncoder;
    private final RunwaySecurityProperties security;
    private final PlatformAuthority platformAuthority;
    private final AppActivityLog activity;
    private final RoleStore roleStore;
    private final AuthorizationAdminService admin;
    private final ConsoleUserService userService;
    private final MessageSource messages;

    public AppUsersController(AppUserStore users, PasswordEncoder passwordEncoder,
                             RunwaySecurityProperties security, PlatformAuthority platformAuthority,
                             AppActivityLog activity, RoleStore roleStore, AuthorizationAdminService admin,
                             ConsoleUserService userService,
                             @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.security = security;
        this.platformAuthority = platformAuthority;
        this.activity = activity;
        this.roleStore = roleStore;
        this.admin = admin;
        this.userService = userService;
        this.messages = messages;
    }

    @GetMapping
    public String list(Model model, Authentication authentication) {
        model.addAttribute("users", users.list());
        model.addAttribute("masterId", security.getAppMasterId());
        // The signed-in operator's own row offers no Suspend and no tier change (the service refuses both).
        model.addAttribute("selfId", authentication != null
                && authentication.getPrincipal() instanceof RunwayUserDetails self ? self.principalId() : "");
        model.addAttribute("customRoles", roleStore.appRoleList().stream().map(Role::name).toList());
        model.addAttribute("contentTemplate", "operator/app/users");
        model.addAttribute("pageTitleKey", "op.users.title");
        return "operator/op-layout";
    }

    @PostMapping("/create")
    public String create(@RequestParam String username, @RequestParam String password,
                         @RequestParam(required = false) String displayName, @RequestParam(required = false) String email,
                         @RequestParam String tier, @RequestParam(name = "roles", required = false) List<String> roles,
                         Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        try {
            if (!Roles.SYSTEM_ADMIN.equals(tier) && !Roles.SYSTEM_OPERATOR.equals(tier)) {
                throw new IllegalArgumentException("invalid access tier '" + tier + "'");
            }
            Set<String> customNames = roleStore.appRoleList().stream().map(Role::name)
                    .collect(java.util.stream.Collectors.toSet());
            List<String> requested = new ArrayList<>();
            if (roles != null) {
                for (String r : roles) {
                    if (r != null && !r.isBlank() && !requested.contains(r)) {
                        if (!customNames.contains(r)) {
                            throw new IllegalArgumentException("no such custom role '" + r + "'");
                        }
                        requested.add(r);
                    }
                }
            }
            String newId = UUID.randomUUID().toString();
            users.save(new User(newId, username.trim(), passwordEncoder.encode(password),
                    orBlank(displayName, username.trim()), orBlank(email, ""), true, false, Realm.APPLICATION,
                    Set.of(tier), java.util.List.of()));
            RunwayUserDetails actor = actor(authentication);
            List<String> assigned = new ArrayList<>();
            for (String r : requested) {
                try {
                    admin.assignRole(actor, null, newId, r); // app-realm grant file; bounded-grant enforced
                    assigned.add(r);
                } catch (RuntimeException ex) {
                    // Roll back COMPLETELY: strip grant-file roles written so far, then delete the user.
                    for (String done : assigned) {
                        try {
                            admin.revokeRole(actor, null, newId, done);
                        } catch (RuntimeException ignored) {
                            // best-effort cleanup; the user delete below removes the principal regardless
                        }
                    }
                    users.delete(newId);
                    throw new IllegalArgumentException("could not assign role '" + r + "': " + ex.getMessage());
                }
            }
            activity.record(authentication.getName(), "appuser.created", username.trim() + " (" + tier + ")");
            redirect.addFlashAttribute("flash", "Created application user '" + username.trim() + "'"
                    + (requested.isEmpty() ? "." : " with role(s): " + String.join(", ", requested) + "."));
        } catch (mx.quest.runway.security.UsernameTakenException e) {
            // Held by another application user or the pinned superadmin (the store checks, under its lock).
            // Before this check a duplicate was written and the NEXT start refused the app realm.
            redirect.addFlashAttribute("error", message("op.users.error.usernameTaken", e.username()));
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/__app/users";
    }

    private RunwayUserDetails actor(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof RunwayUserDetails principal) {
            return principal;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not authenticated in this realm");
    }

    /**
     * Edits an application user's display name, recovery email and — except for the pinned superadmin and the
     * operator's own account — access tier. Guards live in {@link ConsoleUserService}; a refusal is a flash error.
     */
    @PostMapping("/{principalId}/edit")
    public String edit(@PathVariable String principalId, @RequestParam(required = false) String displayName,
                       @RequestParam(required = false) String email, @RequestParam(required = false) String tier,
                       Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        try {
            User updated = userService.updateAppUser(actor(authentication), principalId, displayName, email, tier);
            activity.record(authentication.getName(), "appuser.updated",
                    updated.username() + " (" + String.join(",", new java.util.TreeSet<>(updated.roles())) + ")");
            redirect.addFlashAttribute("flash", message("op.users.edit.saved", updated.username()));
        } catch (ConsoleUserService.UserEditException e) {
            redirect.addFlashAttribute("error", error(e));
        } catch (IllegalStateException | java.io.UncheckedIOException e) {
            redirect.addFlashAttribute("error", e.getMessage()); // a store guard or a failed write: never a 500
        }
        return "redirect:/__app/users";
    }

    /** Suspends an application user: sign-in refused, and an open session is dropped on its next request. */
    @PostMapping("/{principalId}/suspend")
    public String suspend(@PathVariable String principalId, Authentication authentication,
                          RedirectAttributes redirect) {
        return setEnabled(principalId, false, authentication, redirect);
    }

    /** Resumes a suspended application user (sign-in allowed again; the password is unchanged). */
    @PostMapping("/{principalId}/resume")
    public String resume(@PathVariable String principalId, Authentication authentication,
                         RedirectAttributes redirect) {
        return setEnabled(principalId, true, authentication, redirect);
    }

    private String setEnabled(String principalId, boolean enabled, Authentication authentication,
                              RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        try {
            User updated = userService.setAppUserEnabled(actor(authentication), principalId, enabled);
            activity.record(authentication.getName(), enabled ? "appuser.resumed" : "appuser.suspended",
                    updated.username());
            redirect.addFlashAttribute("flash",
                    message(enabled ? "op.users.resumed" : "op.users.suspended", updated.username()));
        } catch (ConsoleUserService.UserEditException e) {
            redirect.addFlashAttribute("error", error(e));
        } catch (IllegalStateException | java.io.UncheckedIOException e) {
            redirect.addFlashAttribute("error", e.getMessage()); // a store guard or a failed write: never a 500
        }
        return "redirect:/__app/users";
    }

    private String message(String key, Object arg) {
        return messages.getMessage(key, new Object[] {arg}, key, LocaleContextHolder.getLocale());
    }

    private String error(ConsoleUserService.UserEditException e) {
        return messages.getMessage("op.users.error." + e.code(), null, e.getMessage(),
                LocaleContextHolder.getLocale());
    }

    /**
     * Sets another user's password. Every open session of that user ends on its next request
     * ({@code AccountStateFilter} sees the hash change); an administrator resetting their OWN password here keeps
     * this session, like the Account screen.
     */
    @PostMapping("/reset-password")
    public String resetPassword(@RequestParam String principalId, @RequestParam String password,
                                Authentication authentication, RedirectAttributes redirect,
                                jakarta.servlet.http.HttpServletRequest request) {
        platformAuthority.require(authentication);
        try {
            User user = users.findByPrincipalId(principalId)
                    .orElseThrow(() -> new IllegalArgumentException("no such user"));
            User updated = user.withPasswordHash(passwordEncoder.encode(password));
            users.save(updated);
            mx.quest.runway.security.AccountStateFilter.rebindIfSelf(request, updated);
            activity.record(authentication.getName(), "appuser.password-reset", user.username());
            redirect.addFlashAttribute("flash", "Reset the password for '" + user.username() + "'.");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/__app/users";
    }

    @PostMapping("/{principalId}/delete")
    public String delete(@PathVariable String principalId,
                         Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        if (principalId.equals(security.getAppMasterId())) {
            redirect.addFlashAttribute("error", "The application superadmin cannot be deleted.");
            return "redirect:/__app/users";
        }
        try {
            // Read the record first: once deleted, only the opaque principal id would be left to journal, and the
            // journal names people by username like every other entry. The id follows in brackets, so a later
            // user re-created under the same name stays distinguishable from this one.
            String username = users.findByPrincipalId(principalId).map(User::username).orElse(principalId);
            boolean deleted = users.delete(principalId);
            if (deleted) {
                activity.record(authentication.getName(), "appuser.deleted", username + " (id " + principalId + ")");
            }
            redirect.addFlashAttribute(deleted ? "flash" : "error", deleted ? "Deleted the user." : "No such user.");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/__app/users";
    }

    private static String orBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
