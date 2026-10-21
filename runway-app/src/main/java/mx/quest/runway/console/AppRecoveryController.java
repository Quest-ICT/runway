package mx.quest.runway.console;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Function;
import mx.quest.runway.recovery.PasswordRecoveryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The <b>application-realm</b> password-recovery flow — {@code /app/forgot-password} and {@code /app/reset},
 * the self-service counterpart to {@code /app/login}. A user requests a link by email; the platform mails a
 * time-limited, single-use link back; opening it sets a new password. All logic lives in
 * {@link PasswordRecoveryService}; this controller only renders the two standalone pages and threads the
 * realm-specific chrome (brand, accent, URLs).
 *
 * <p>The request path is deliberately <b>non-enumerating</b>: a POST always shows the same neutral
 * confirmation, whether or not any account matched.
 */
@Controller
public class AppRecoveryController {

    private static final String BRAND = "Runway";
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final PasswordRecoveryService recovery;

    public AppRecoveryController(PasswordRecoveryService recovery) {
        this.recovery = recovery;
    }

    @GetMapping("/app/forgot-password")
    public String forgot(@RequestParam(required = false) String sent, Model model) {
        chrome(model, "/app/forgot-password");
        model.addAttribute("sent", sent != null);
        return "operator/recovery-forgot";
    }

    @PostMapping("/app/forgot-password")
    public String requestLink(@RequestParam(required = false) String email, Locale locale) {
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        Function<String, String> linkFor =
                token -> base + "/app/reset?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        recovery.requestApp(email, BRAND, linkFor, locale);
        // Post-redirect-get to the neutral confirmation (no account enumeration, no form re-submit).
        return "redirect:/app/forgot-password?sent";
    }

    @GetMapping("/app/reset")
    public String resetForm(@RequestParam(required = false) String token, Model model) {
        chrome(model, "/app/reset");
        recovery.validateApp(token).ifPresentOrElse(
                grant -> {
                    model.addAttribute("valid", true);
                    model.addAttribute("token", token);
                    model.addAttribute("username", grant.username());
                },
                () -> model.addAttribute("valid", false));
        return "operator/recovery-reset";
    }

    @PostMapping("/app/reset")
    public String doReset(@RequestParam(required = false) String token,
                          @RequestParam(required = false) String password,
                          @RequestParam(required = false) String confirm, Model model) {
        String error = passwordError(password, confirm);
        if (error != null) {
            chrome(model, "/app/reset");
            model.addAttribute("valid", true);
            model.addAttribute("token", token);
            model.addAttribute("error", error);
            return "operator/recovery-reset";
        }
        if (recovery.resetApp(token, password) == PasswordRecoveryService.ResetOutcome.OK) {
            return "redirect:/app/login?reset";
        }
        chrome(model, "/app/reset");
        model.addAttribute("valid", false); // unknown/expired/already-used link, or the account is gone
        return "operator/recovery-reset";
    }

    /** The realm-specific chrome the shared recovery templates bind (brand, accent, form + back-link URLs). */
    private void chrome(Model model, String postUrl) {
        model.addAttribute("brand", BRAND);
        model.addAttribute("accentBorder", "border-primary");
        model.addAttribute("accentBtn", "btn-primary");
        model.addAttribute("loginUrl", "/app/login");
        model.addAttribute("postUrl", postUrl);
    }

    /** Returns a message-key suffix for an invalid new password, or {@code null} when it is acceptable. */
    private static String passwordError(String password, String confirm) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return "weak";
        }
        if (!password.equals(confirm)) {
            return "mismatch";
        }
        return null;
    }
}
