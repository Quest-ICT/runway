package mx.quest.runway.console;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.security.TenantMasterStore;
import mx.quest.runway.security.TenantProvisioning;
import mx.quest.runway.security.User;
import mx.quest.runway.site.SiteStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The application-realm <b>Site masters</b> screen ({@code /__app/masters}): the tenant superadmins — a global
 * identity managed by application users, each assignable to one or more tenancies. List (with assignments),
 * create (provisioning one for an initial tenancy), reset a password, assign / unassign a tenancy, suspend /
 * resume, and delete. A password reset or a suspension ends the superadmin's open sessions on their next request
 * ({@code AccountStateFilter}).
 * Thin wiring of {@link TenantMasterStore} + {@link TenantProvisioning}; a platform operation gated to the
 * master/wildcard admin.
 */
@Controller
@RequestMapping("/__app/masters")
public class SiteMastersController {

    private final TenantMasterStore masters;
    private final TenantProvisioning provisioning;
    private final SiteStore sites;
    private final PasswordEncoder passwordEncoder;
    private final PlatformAuthority platformAuthority;
    private final AppActivityLog activity;
    private final ConsoleUserService userService;
    private final MessageSource messages;

    public SiteMastersController(TenantMasterStore masters, TenantProvisioning provisioning, SiteStore sites,
                                PasswordEncoder passwordEncoder, PlatformAuthority platformAuthority,
                                AppActivityLog activity, ConsoleUserService userService,
                                @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.masters = masters;
        this.provisioning = provisioning;
        this.sites = sites;
        this.passwordEncoder = passwordEncoder;
        this.platformAuthority = platformAuthority;
        this.activity = activity;
        this.userService = userService;
        this.messages = messages;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("masters", masters.list());
        model.addAttribute("contentTemplate", "operator/app/masters");
        model.addAttribute("pageTitleKey", "op.masters.title");
        return "operator/op-layout";
    }

    @PostMapping("/create")
    public String create(@RequestParam String username, @RequestParam String password,
                         @RequestParam(required = false) String displayName, @RequestParam(required = false) String email,
                         @RequestParam String siteId, Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        try {
            requireExistingTenancy(siteId.trim());
            // The store refuses a name held by another superadmin or by a user of the initial site.
            provisioning.provisionMaster(UUID.randomUUID().toString(), username.trim(),
                    passwordEncoder.encode(password), siteId.trim(),
                    displayName == null || displayName.isBlank() ? username.trim() : displayName.trim(),
                    ConsoleUserService.recoveryEmail(email));
            activity.record(authentication.getName(), "master.created", username.trim() + " @ " + siteId.trim());
            redirect.addFlashAttribute("flash", "Created tenant superadmin '" + username.trim() + "'.");
        } catch (mx.quest.runway.security.UsernameTakenException e) {
            redirect.addFlashAttribute("error", usernameTaken(e));
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/__app/masters";
    }

    @PostMapping("/reset-password")
    public String resetPassword(@RequestParam String principalId, @RequestParam String password,
                                Authentication authentication, RedirectAttributes redirect) {
        return mutate(authentication, redirect, principalId,
                master -> masters.save(master.withPasswordHash(passwordEncoder.encode(password))), "Password reset.",
                "master.password-reset", null);
    }

    /**
     * Changes a tenant superadmin's display name and recovery email. Without a recovery email the superadmin
     * cannot use "Forgot your password?", and until 1.0.0 no screen could add one after creation.
     */
    @PostMapping("/{principalId}/edit")
    public String edit(@PathVariable String principalId, @RequestParam(required = false) String displayName,
                       @RequestParam(required = false) String email, Authentication authentication,
                       RedirectAttributes redirect) {
        return mutate(authentication, redirect, principalId,
                master -> masters.save(ConsoleUserService.withProfile(master, displayName, email)),
                "Saved the superadmin's details.", "master.edited", null);
    }

    @PostMapping("/assign")
    public String assign(@RequestParam String principalId, @RequestParam String siteId,
                         Authentication authentication, RedirectAttributes redirect) {
        return mutate(authentication, redirect, principalId, master -> {
            requireExistingTenancy(siteId.trim());
            masters.save(withSites(master, addSite(master, siteId.trim())));
        }, "Assigned to '" + siteId.trim() + "'.", "master.assigned", siteId.trim());
    }

    @PostMapping("/unassign")
    public String unassign(@RequestParam String principalId, @RequestParam String siteId,
                           Authentication authentication, RedirectAttributes redirect) {
        return mutate(authentication, redirect, principalId, master -> {
            requireNotSoleSuperadmin(master, siteId.trim());
            masters.save(withSites(master, removeSite(master, siteId.trim())));
        }, "Unassigned from '" + siteId.trim() + "'.", "master.unassigned", siteId.trim());
    }

    /**
     * Suspends a tenant superadmin: sign-in to every assigned site is refused, and each open session ends on its
     * next request. Password and assignments are kept. Same guards as an application user's suspension
     * ({@link ConsoleUserService#setTenantMasterEnabled}); journaled as {@code master.suspended}.
     */
    @PostMapping("/{principalId}/suspend")
    public String suspend(@PathVariable String principalId, Authentication authentication,
                          RedirectAttributes redirect) {
        return setEnabled(principalId, false, authentication, redirect);
    }

    /** Resumes a suspended tenant superadmin (sign-in allowed again, same password); journaled as {@code master.resumed}. */
    @PostMapping("/{principalId}/resume")
    public String resume(@PathVariable String principalId, Authentication authentication,
                         RedirectAttributes redirect) {
        return setEnabled(principalId, true, authentication, redirect);
    }

    private String setEnabled(String principalId, boolean enabled, Authentication authentication,
                              RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        try {
            User updated = userService.setTenantMasterEnabled(AccountSupport.principal(authentication), principalId,
                    enabled);
            activity.record(authentication.getName(), enabled ? "master.resumed" : "master.suspended",
                    updated.username());
            redirect.addFlashAttribute("flash", messages.getMessage(
                    enabled ? "op.masters.resumed" : "op.masters.suspended", new Object[] {updated.username()},
                    LocaleContextHolder.getLocale()));
        } catch (ConsoleUserService.UserEditException e) {
            redirect.addFlashAttribute("error", messages.getMessage("op.users.error." + e.code(), null,
                    e.getMessage(), LocaleContextHolder.getLocale()));
        } catch (IllegalStateException | java.io.UncheckedIOException e) {
            redirect.addFlashAttribute("error", e.getMessage()); // a store guard or a failed write: never a 500
        }
        return "redirect:/__app/masters";
    }

    @PostMapping("/{principalId}/delete")
    public String delete(@PathVariable String principalId, Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        try {
            User master = masters.list().stream().filter(m -> m.principalId().equals(principalId)).findFirst()
                    .orElse(null);
            if (master == null) {
                redirect.addFlashAttribute("error", "No such user.");
                return "redirect:/__app/masters";
            }
            for (String siteId : master.sites()) {
                requireNotSoleSuperadmin(master, siteId); // never leave a tenancy with no superadmin
            }
            masters.delete(principalId);
            activity.record(authentication.getName(), "master.deleted", master.username());
            redirect.addFlashAttribute("flash", "Deleted the superadmin.");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/__app/masters";
    }

    /** Rejects removing {@code master} from {@code siteId} when it is that tenancy's only superadmin. */
    private void requireNotSoleSuperadmin(User master, String siteId) {
        boolean anotherCovers = masters.assignedTo(siteId).stream()
                .anyMatch(other -> !other.principalId().equals(master.principalId()));
        if (!anotherCovers) {
            throw new IllegalArgumentException(
                    "'" + master.username() + "' is the only superadmin of '" + siteId + "'; assign another first");
        }
    }

    /** Rejects assigning/creating against a tenancy that does not exist. */
    private void requireExistingTenancy(String siteId) {
        if (!sites.exists(siteId)) {
            throw new IllegalArgumentException("no such tenancy: '" + siteId + "'");
        }
    }

    private String mutate(Authentication authentication, RedirectAttributes redirect, String principalId,
                          java.util.function.Consumer<User> action, String success, String auditAction,
                          String auditSite) {
        platformAuthority.require(authentication);
        try {
            User master = masters.list().stream().filter(m -> m.principalId().equals(principalId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no such tenant superadmin"));
            action.accept(master);
            // A stable action key; the site (when there is one) travels in the detail, never in the key.
            activity.record(authentication.getName(), auditAction,
                    auditSite == null ? master.username() : master.username() + " @ " + auditSite);
            redirect.addFlashAttribute("flash", success);
        } catch (mx.quest.runway.security.UsernameTakenException e) {
            // An assignment to a site where a user already holds this superadmin's name: refused, so the site
            // user can neither shadow the superadmin nor be silently made unreachable.
            redirect.addFlashAttribute("error", usernameTaken(e));
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/__app/masters";
    }

    private String usernameTaken(mx.quest.runway.security.UsernameTakenException e) {
        return messages.getMessage("op.masters.error.usernameTaken", new Object[] {e.username()},
                e.getMessage(), LocaleContextHolder.getLocale());
    }

    private static List<String> addSite(User master, String siteId) {
        List<String> sites = new ArrayList<>(master.sites());
        if (!sites.contains(siteId)) {
            sites.add(siteId);
        }
        return sites;
    }

    private static List<String> removeSite(User master, String siteId) {
        List<String> sites = new ArrayList<>(master.sites());
        sites.remove(siteId);
        return sites;
    }

    private static User withSites(User master, List<String> sites) {
        return new User(master.principalId(), master.username(), master.passwordHash(), master.displayName(),
                master.recoveryEmail(), master.enabled(), master.master(), master.realm(), master.roles(), sites);
    }
}
