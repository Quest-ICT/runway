package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletRequest;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.security.AppUserStore;
import mx.quest.runway.security.LoginAttemptGate;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.User;
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
 * The application operator's own <b>Account</b> ({@code /__app/account}): your display name and recovery email, and
 * your password. No admin
 * gate — every authenticated app user may change their <em>own</em> credential — but the current password must
 * verify first, and it only ever touches the signed-in principal's own record ({@link AppUserStore}). Wrong current
 * passwords are limited and journaled ({@link AccountSupport}). Shares the {@code operator/account} form template
 * with the tenant account.
 */
@Controller
public class AppAccountController {

    private final AppUserStore users;
    private final PasswordEncoder passwordEncoder;
    private final AppActivityLog activity;
    private final LoginAttemptGate loginGate;
    private final MessageSource messages;
    private final WrongPasswordCounter wrongPasswords;

    public AppAccountController(AppUserStore users, PasswordEncoder passwordEncoder, AppActivityLog activity,
                                LoginAttemptGate loginGate,
                                @Qualifier("operatorAwareMessageSource") MessageSource messages,
                                WrongPasswordCounter wrongPasswords) {
        this.wrongPasswords = wrongPasswords;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.activity = activity;
        this.loginGate = loginGate;
        this.messages = messages;
    }

    @GetMapping("/__app/account")
    public String form(Authentication authentication, Model model) {
        model.addAttribute("self", self(authentication));
        model.addAttribute("accountAction", "/__app/account");
        model.addAttribute("contentTemplate", "operator/account");
        model.addAttribute("pageTitleKey", "op.account.title");
        return "operator/op-layout";
    }

    @PostMapping("/__app/account")
    public String change(@RequestParam String currentPassword, @RequestParam String newPassword,
                         @RequestParam String confirmPassword, Authentication authentication,
                         RedirectAttributes redirect, HttpServletRequest request) {
        User self = self(authentication);
        // The save consumer fires only on a successful change, so recording inside it audits success only.
        return AccountSupport.changePassword(self, currentPassword, newPassword, confirmPassword, checks(self),
                updated -> {
                    users.save(updated);
                    activity.record(self.username(), "account.password-changed", "");
                }, redirect, request);
    }

    /** Saves your own display name and recovery email ({@link AccountSupport#changeProfile}). */
    @PostMapping("/__app/account/profile")
    public String profile(@RequestParam(required = false) String displayName,
                          @RequestParam(required = false) String recoveryEmail,
                          @RequestParam(required = false) String currentPassword, Authentication authentication,
                          RedirectAttributes redirect, HttpServletRequest request) {
        User self = self(authentication);
        return AccountSupport.changeProfile(self, displayName, recoveryEmail, currentPassword, checks(self),
                updated -> {
                    users.save(updated);
                    activity.record(self.username(), "account.profile-changed", "");
                }, redirect, request);
    }

    private AccountSupport.Checks checks(User self) {
        return new AccountSupport.Checks(passwordEncoder, loginGate, messages, "/__app/account", "/app/login",
                wrongPasswords, ip -> activity.record(self.username(), "account.password-check-failed", "ip=" + ip));
    }

    private User self(Authentication authentication) {
        RunwayUserDetails principal = AccountSupport.principal(authentication);
        return users.findByPrincipalId(principal.principalId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "no account"));
    }
}
