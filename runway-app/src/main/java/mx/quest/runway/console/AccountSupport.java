package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Objects;
import java.util.function.Consumer;
import mx.quest.runway.security.AccountStateFilter;
import mx.quest.runway.security.LoginAttemptGate;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.User;
import mx.quest.runway.web.ClientIp;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Shared self-service logic for the app and tenant Account screens: a user changes only their <b>own</b> password,
 * display name and recovery email. Kept realm-agnostic (the caller supplies the loaded self, the store's save and
 * the realm's {@link Checks}) so the two realms share one set of rules.
 *
 * <h2>The current-password check is limited</h2>
 * Changing the password or the recovery email needs the current password. Without a limit, a borrowed or stolen
 * console session could guess it as fast as the server answers, and a hit gives away the credential or the place a
 * reset link goes. So every wrong guess:
 * <ul>
 *   <li>counts against the signed-in account <b>in this session</b>; after {@link #MAX_WRONG_PASSWORDS} in a row the
 *       session's sign-in for this realm is dropped and the person is sent to the sign-in page, so a borrowed session
 *       is spent after a few guesses. This is the kernel's own floor and needs no plugin. A right password resets
 *       the count. The count is reserved before the password is hashed ({@link WrongPasswordCounter}), so guesses
 *       sent at the same moment cannot all slip under it: at most {@link #MAX_WRONG_PASSWORDS} are ever checked;</li>
 *   <li>is reported to the {@link LoginAttemptGate} for the client's IP, as a failed sign-in is, so the protection
 *       plugin's per-IP lockout covers these forms too; while that IP is locked the forms refuse before any
 *       password is hashed;</li>
 *   <li>is recorded in the realm's journal with the client's IP ({@link Checks#onWrongPassword}).</li>
 * </ul>
 * A blank current password is not a guess: it is refused with a prompt and not counted.
 */
final class AccountSupport {

    /** Wrong current passwords in a row, in one session, before its sign-in is dropped (the login default). */
    static final int MAX_WRONG_PASSWORDS = 5;

    /** The counter key prefix, followed by the session id, the realm and the principal id. */
    static final String WRONG_PASSWORDS_KEY = "runway.account.wrong-passwords|";

    private AccountSupport() {
    }

    /**
     * What a realm's account screen gives the shared rules.
     *
     * @param encoder         the password encoder
     * @param gate            the per-IP sign-in attempt gate (the kernel's permissive one, or the protection plugin's)
     * @param messages        the operator message source, for the screen's messages in the console's language
     * @param screen          the account screen's path, where every outcome but a sign-out returns
     * @param loginPath       the realm's sign-in page, where a spent session is sent
     * @param counter         the node's count of wrong current passwords per session and account
     * @param onWrongPassword records one wrong current password in the realm's journal; receives the client's IP
     */
    record Checks(PasswordEncoder encoder, LoginAttemptGate gate, MessageSource messages, String screen,
                  String loginPath, WrongPasswordCounter counter, Consumer<String> onWrongPassword) {
        Checks {
            Objects.requireNonNull(encoder, "encoder");
            Objects.requireNonNull(gate, "gate");
            Objects.requireNonNull(counter, "counter");
            Objects.requireNonNull(messages, "messages");
            Objects.requireNonNull(screen, "screen");
            Objects.requireNonNull(loginPath, "loginPath");
            Objects.requireNonNull(onWrongPassword, "onWrongPassword");
        }
    }

    /** The signed-in principal as a {@link RunwayUserDetails}, or a 403 if there is none. */
    static RunwayUserDetails principal(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof RunwayUserDetails details) {
            return details;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not authenticated");
    }

    /**
     * Verifies {@code current} (limited, see the class comment), checks the new password is non-blank and matches
     * its confirmation, then saves {@code self} with the new hash — surfacing every failure as a flash error (never a
     * 500). Only the caller's own record is passed in, so this can never change another user.
     *
     * <p>After a successful save the <b>requesting</b> session is rebound to the new record
     * ({@link AccountStateFilter#rebindIfSelf}), so it stays signed in, while every other session of the account
     * ends on its next request ({@link AccountStateFilter} sees the hash change).
     *
     * @return the view to answer with: a redirect to the account screen, or to the sign-in page once the session's
     *         sign-in was dropped for too many wrong passwords
     */
    static String changePassword(User self, String current, String newPassword, String confirm, Checks checks,
                                 Consumer<User> save, RedirectAttributes redirect, HttpServletRequest request) {
        String refused = verifyCurrent(self, current, "op.account.error.currentNeeded",
                "Enter your current password.", checks, redirect, request);
        if (refused != null) {
            return refused;
        }
        if (newPassword == null || newPassword.isBlank()) {
            redirect.addFlashAttribute("error", message(checks, "op.account.error.newBlank",
                    "The new password must not be blank."));
            return back(checks);
        }
        if (!newPassword.equals(confirm)) {
            redirect.addFlashAttribute("error", message(checks, "op.account.error.mismatch",
                    "The new password and its confirmation do not match."));
            return back(checks);
        }
        User updated = self.withPasswordHash(checks.encoder().encode(newPassword));
        save.accept(updated);
        AccountStateFilter.rebindIfSelf(request, updated);
        redirect.addFlashAttribute("flash", message(checks, "op.account.passwordChanged",
                "Your password has been changed. Any other session signed in as you has been signed out."));
        return back(checks);
    }

    /**
     * Saves {@code self}'s own display name and recovery email, with the same rules as an administrator's edit
     * ({@link ConsoleUserService#withProfile}: a blank name falls back to the username, a non-blank email must be an
     * address). Changing the <b>recovery email</b> also needs the current password (limited, see the class
     * comment): it is where a password-reset link goes, so a borrowed or stolen session must not be able to point
     * it elsewhere. The name alone needs none. Every failure is a flash error; nothing is saved then.
     *
     * <p>After the save the requesting session is rebound to the new record, so the name in the top bar updates.
     *
     * @return the view to answer with, as for {@link #changePassword}
     */
    static String changeProfile(User self, String displayName, String email, String current, Checks checks,
                                Consumer<User> save, RedirectAttributes redirect, HttpServletRequest request) {
        User updated;
        try {
            updated = ConsoleUserService.withProfile(self, displayName, email);
        } catch (ConsoleUserService.UserEditException invalid) {
            redirect.addFlashAttribute("error", message(checks, "op.users.error." + invalid.code(),
                    invalid.getMessage()));
            return back(checks);
        }
        if (!self.recoveryEmail().equals(updated.recoveryEmail())) {
            String refused = verifyCurrent(self, current, "op.account.profile.needsPassword",
                    "Enter your current password to change your recovery email.", checks, redirect, request);
            if (refused != null) {
                return refused;
            }
        }
        save.accept(updated);
        AccountStateFilter.rebindIfSelf(request, updated);
        redirect.addFlashAttribute("flash", message(checks, "op.account.profile.saved",
                "Your profile has been saved."));
        return back(checks);
    }

    /**
     * The limited current-password check. A blank {@code current} is answered with {@code blankKey}'s message and
     * not counted; a wrong one with "The current password is incorrect."
     *
     * @return {@code null} when {@code current} is right; otherwise the view to answer with, the flash already set
     */
    private static String verifyCurrent(User self, String current, String blankKey, String blankFallback,
                                        Checks checks, RedirectAttributes redirect, HttpServletRequest request) {
        if (current == null || current.isEmpty()) {
            redirect.addFlashAttribute("error", message(checks, blankKey, blankFallback));
            return back(checks);
        }
        String ip = ClientIp.of(request);
        if (checks.gate().isLocked(ip)) {
            redirect.addFlashAttribute("error", message(checks, "op.login.locked",
                    "Too many failed sign-in attempts from your network. Wait a few minutes, then try again."));
            return back(checks);
        }
        // The guess's number is reserved before the password is hashed: guesses sent at the same moment each get
        // their own, so the ones past the limit are refused unhashed instead of all reading the same count.
        HttpSession session = request.getSession(false);
        String key = WRONG_PASSWORDS_KEY + (session == null ? "-" : session.getId()) + "|" + self.realm() + "|"
                + self.principalId();
        int attempt = checks.counter().reserve(key);
        if (attempt > MAX_WRONG_PASSWORDS) {
            AccountStateFilter.signOutIfSelf(request, self); // spent: this one is not checked at all
            return "redirect:" + checks.loginPath() + "?reverify";
        }
        if (checks.encoder().matches(current, self.passwordHash())) {
            checks.counter().reset(key);
            return null;
        }
        checks.gate().recordFailure(ip);
        checks.onWrongPassword().accept(ip);
        if (attempt == MAX_WRONG_PASSWORDS) {
            // No reset here: a guess still in flight must find the count spent, not start again at 1. The key carries
            // the session id, and the sign-out ends that session, so the entry is never used again and expires.
            AccountStateFilter.signOutIfSelf(request, self);
            return "redirect:" + checks.loginPath() + "?reverify";
        }
        redirect.addFlashAttribute("error", message(checks, "op.account.error.currentIncorrect",
                "The current password is incorrect."));
        return back(checks);
    }

    private static String back(Checks checks) {
        return "redirect:" + checks.screen();
    }

    private static String message(Checks checks, String key, String fallback) {
        return checks.messages().getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }
}
