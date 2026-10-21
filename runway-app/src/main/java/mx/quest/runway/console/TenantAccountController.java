package mx.quest.runway.console;

import mx.quest.runway.log.SiteLogService;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Consumer;
import mx.quest.runway.security.LoginAttemptGate;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.SiteUserStore;
import mx.quest.runway.security.TenantMasterStore;
import mx.quest.runway.security.User;
import mx.quest.runway.site.SiteResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The tenant user's own <b>Account</b> ({@code /__admin/account}): your display name and recovery email, and your
 * password. The signed-in
 * tenant principal is either a tenant superadmin (stored globally in {@link TenantMasterStore}) or a sub-user of
 * the bound tenancy ({@link SiteUserStore}); the record is loaded from the right store by the principal's
 * {@code master()} flag, and only that record is ever touched. Wrong current passwords are limited and journaled
 * ({@link AccountSupport}). Shares the {@code operator/account} form.
 */
@Controller
public class TenantAccountController {

    private final TenantMasterStore masters;
    private final SiteUserStore siteUsers;
    private final SiteResolver siteResolver;
    private final PasswordEncoder passwordEncoder;
    private final SiteLogService siteLog;
    private final LoginAttemptGate loginGate;
    private final MessageSource messages;
    private final WrongPasswordCounter wrongPasswords;

    public TenantAccountController(TenantMasterStore masters, SiteUserStore siteUsers, SiteResolver siteResolver,
                                   PasswordEncoder passwordEncoder, SiteLogService siteLog,
                                   LoginAttemptGate loginGate,
                                   @Qualifier("operatorAwareMessageSource") MessageSource messages,
                                   WrongPasswordCounter wrongPasswords) {
        this.wrongPasswords = wrongPasswords;
        this.siteLog = siteLog;
        this.masters = masters;
        this.siteUsers = siteUsers;
        this.siteResolver = siteResolver;
        this.passwordEncoder = passwordEncoder;
        this.loginGate = loginGate;
        this.messages = messages;
    }

    @GetMapping("/__admin/account")
    public String form(Authentication authentication, Model model) {
        model.addAttribute("self", self(AccountSupport.principal(authentication)).user());
        model.addAttribute("accountAction", "/__admin/account");
        model.addAttribute("contentTemplate", "operator/account");
        model.addAttribute("pageTitleKey", "op.account.title");
        return "operator/op-layout";
    }

    @PostMapping("/__admin/account")
    public String change(@RequestParam String currentPassword, @RequestParam String newPassword,
                         @RequestParam String confirmPassword, Authentication authentication,
                         RedirectAttributes redirect, HttpServletRequest request) {
        Self self = self(AccountSupport.principal(authentication));
        return AccountSupport.changePassword(self.user(), currentPassword, newPassword, confirmPassword,
                checks(self), journaled(self, "account.password-changed"), redirect, request);
    }

    /** Saves your own display name and recovery email ({@link AccountSupport#changeProfile}). */
    @PostMapping("/__admin/account/profile")
    public String profile(@RequestParam(required = false) String displayName,
                          @RequestParam(required = false) String recoveryEmail,
                          @RequestParam(required = false) String currentPassword, Authentication authentication,
                          RedirectAttributes redirect, HttpServletRequest request) {
        Self self = self(AccountSupport.principal(authentication));
        return AccountSupport.changeProfile(self.user(), displayName, recoveryEmail, currentPassword, checks(self),
                journaled(self, "account.profile-changed"), redirect, request);
    }

    /** The shared rules, with each wrong current password journaled in the site the request was made on. */
    private AccountSupport.Checks checks(Self self) {
        String siteId = siteResolver.current().map(site -> site.id()).orElse(null);
        java.util.function.Consumer<String> onWrong = siteId == null ? ip -> { }
                : ip -> siteLog.audit(siteId, self.user().username(), "account.password-check-failed", "ip=" + ip);
        return new AccountSupport.Checks(passwordEncoder, loginGate, messages, "/__admin/account", "/login",
                wrongPasswords, onWrong);
    }

    /** The signed-in user's own record and how to save it. */
    private record Self(User user, Consumer<User> save) {
    }

    /**
     * Loads the principal's own record from the right store: a tenant superadmin from {@link TenantMasterStore}, a
     * site user from the bound site's {@link SiteUserStore}.
     */
    private Self self(RunwayUserDetails principal) {
        if (principal.master()) {
            return new Self(byId(masters.list(), principal.principalId()), masters::save);
        }
        String tenancyId = siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy")).id();
        return new Self(byId(siteUsers.list(tenancyId), principal.principalId()),
                user -> siteUsers.save(tenancyId, user));
    }

    /**
     * The save, journaled in the site the request was made on, as the app console does for its accounts. The
     * consumer runs only after the change was accepted, so only a successful change is recorded.
     */
    private Consumer<User> journaled(Self self, String action) {
        String siteId = siteResolver.current().map(site -> site.id()).orElse(null);
        return siteId == null ? self.save() : user -> {
            self.save().accept(user);
            siteLog.audit(siteId, self.user().username(), action, "");
        };
    }

    private static User byId(java.util.List<User> candidates, String principalId) {
        return candidates.stream().filter(user -> user.principalId().equals(principalId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "no account"));
    }
}
