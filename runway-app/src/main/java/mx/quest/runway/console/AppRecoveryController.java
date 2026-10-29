package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import mx.quest.runway.recovery.PasswordRecoveryService;
import mx.quest.runway.recovery.RecoveryLinks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The <b>application-realm</b> password-recovery flow — {@code /app/forgot-password} and {@code /app/reset},
 * the self-service counterpart to {@code /app/login}. A user requests a link by email; the platform mails a
 * time-limited, single-use link back; opening it sets a new password. All logic lives in
 * {@link PasswordRecoveryService}; this controller only renders the two standalone pages and threads the
 * realm-specific chrome (brand, accent, URLs).
 *
 * <p>The request path is deliberately <b>non-enumerating</b>: a POST always shows the same neutral
 * confirmation, whether or not any account matched.
 *
 * <p>The emailed link's address comes from {@link RecoveryLinks}, never from the request's host: a request with a
 * made-up {@code Host} would otherwise have the platform mail a real user a link to the sender's site.
 */
@Controller
public class AppRecoveryController {

    private static final Logger log = LoggerFactory.getLogger(AppRecoveryController.class);
    private static final String BRAND = "Runway";
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final PasswordRecoveryService recovery;
    private final RecoveryLinks links;

    public AppRecoveryController(PasswordRecoveryService recovery, RecoveryLinks links) {
        this.recovery = recovery;
        this.links = links;
    }

    @GetMapping("/app/forgot-password")
    public String forgot(@RequestParam(required = false) String sent, Model model) {
        chrome(model, "/app/forgot-password");
        model.addAttribute("sent", sent != null);
        return "operator/recovery-forgot";
    }

    @PostMapping("/app/forgot-password")
    public String requestLink(@RequestParam(required = false) String email, Locale locale,
                              HttpServletRequest request) {
        Optional<String> base = links.appBase(request);
        if (base.isPresent()) {
            Function<String, String> linkFor =
                    token -> base.get() + "/app/reset?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
            log.info("password recovery requested on the console; a link, if mailed, points at {}", base.get());
            recovery.requestApp(email, BRAND, linkFor, locale);
        } else {
            log.warn("password recovery requested on a host that is not the console's known address; no link sent "
                    + "(set runway.console.hosts, or give the default site a public host name or a base URL)");
        }
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
