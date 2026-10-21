package mx.quest.runway.console;

import mx.quest.runway.mail.PlatformMailer;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the application-realm sign-in page ({@code GET /app/login}) on the operator chrome — a standalone,
 * menu-less Thymeleaf page (the login layout deliberately omits the topbar/menu but keeps the chrome and the
 * messages area). The {@code POST /app/login} is handled by Spring Security's form-login filter
 * ({@code AppSecurityConfig}), not a controller; the {@code ?error} / {@code ?logout} flags drive the page's
 * alerts. Replaces the interim {@code @ResponseBody} login that {@code AppConsoleController} used to serve.
 *
 * <p>The {@code recoveryEnabled} flag drives the "Forgot your password?" link: it is shown only when the
 * platform mail account is configured (there is no way to email a reset link otherwise).
 */
@Controller
public class AppLoginController {

    private final PlatformMailer mailer;

    public AppLoginController(PlatformMailer mailer) {
        this.mailer = mailer;
    }

    @GetMapping("/app/login")
    public String login(Model model) {
        model.addAttribute("recoveryEnabled", mailer.isConfigured());
        return "operator/login-app";
    }
}
