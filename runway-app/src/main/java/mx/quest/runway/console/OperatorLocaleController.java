package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.LocaleResolver;

/**
 * The operator-console language switch endpoint ({@code GET /__ui-lang?lang=xx&to=/path}). It sets the operator
 * UI-language cookie (via the {@link OperatorLocaleResolver}) and redirects back to the page the operator was on.
 *
 * <p>GET is deliberate — switching the display language is not a sensitive state change, carries no CSRF token,
 * and must work from any console page. The {@code lang} is honored only if it is one of the offered
 * {@link OperatorProperties#getUiLanguages() UI languages} (an unknown value is ignored, not an error). The
 * {@code to} target is guarded to a same-origin local path, so the parameter can never drive an open redirect.
 */
@Controller
public class OperatorLocaleController {

    private final OperatorProperties operator;
    private final LocaleResolver localeResolver;

    public OperatorLocaleController(OperatorProperties operator, LocaleResolver localeResolver) {
        this.operator = operator;
        this.localeResolver = localeResolver;
    }

    @GetMapping("/__ui-lang")
    public String switchLanguage(@RequestParam String lang,
                                 @RequestParam(required = false) String to,
                                 HttpServletRequest request, HttpServletResponse response) {
        if (operator.getUiLanguages().contains(lang)) {
            localeResolver.setLocale(request, response, Locale.forLanguageTag(lang));
        }
        return "redirect:" + safeLocalPath(to);
    }

    /**
     * Reduces {@code to} to a safe same-origin path, defaulting to the app console. Only a value that begins with
     * a single {@code /} and contains no scheme, backslash, space or control character is honored — so a crafted
     * {@code ?to=} can never redirect off-site or inject a header.
     */
    private static String safeLocalPath(String to) {
        if (to == null || to.isBlank() || !to.startsWith("/") || to.startsWith("//") || to.contains("://")) {
            return "/__app";
        }
        for (int i = 0; i < to.length(); i++) {
            char c = to.charAt(i);
            if (c < 0x20 || c == '\\' || c == ' ') {
                return "/__app";
            }
        }
        return to;
    }
}
