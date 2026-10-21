package mx.quest.runway.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.util.HtmlUtils;

/**
 * Turns a CSRF failure into a <b>friendly, actionable</b> page instead of the generic {@code 403 "permission
 * denied"}. A {@link CsrfException} (missing/invalid token) on a request from an <em>authenticated</em> user
 * almost always means the page's security token went stale — the form sat open while the token rotated (a
 * re-login elsewhere), or the server restarted. That is a confusing thing to meet as "permission denied," so
 * this renders a self-contained "your session ended — please sign in again" card with a login link.
 *
 * <p>Only CSRF denials are special-cased; every <b>other</b> {@link AccessDeniedException} (a genuine
 * lack-of-permission) is passed through to the default handler, which {@code sendError(403)}s into the normal
 * error page. The page is dependency-free inline HTML (no {@code /__assets}, no template) so it renders even
 * when the console chrome is unavailable — the same self-containment {@code RunwayErrorController} uses.
 */
public final class CsrfExpiryAccessDeniedHandler implements AccessDeniedHandler {

    private final String loginUrl;
    private final AccessDeniedHandler passthrough = new AccessDeniedHandlerImpl();

    /**
     * @param loginUrl the realm's login URL to link to ({@code /app/login} for the application realm,
     *                 {@code /login} for the tenant realm)
     */
    public CsrfExpiryAccessDeniedHandler(String loginUrl) {
        this.loginUrl = loginUrl;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {
        if (!(accessDeniedException instanceof CsrfException) || response.isCommitted()) {
            passthrough.handle(request, response, accessDeniedException); // a real 403 → the normal error page
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(page(loginUrl));
    }

    private static String page(String loginUrl) {
        String href = HtmlUtils.htmlEscape(loginUrl, StandardCharsets.UTF_8.name());
        return "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex\"><title>Session expired</title>"
                + "<style>"
                + "*{box-sizing:border-box}html,body{height:100%;margin:0}"
                + "body{display:flex;align-items:center;justify-content:center;min-height:100%;padding:1.5rem;"
                + "font-family:system-ui,-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;"
                + "background:#f6f8fb;color:#1d273b}"
                + ".card{max-width:32rem;width:100%;background:#fff;border:1px solid #e6e9f0;border-radius:.75rem;"
                + "box-shadow:0 1px 3px rgba(29,39,59,.08);padding:2.5rem;text-align:center}"
                + ".icon{font-size:2.5rem;line-height:1}"
                + ".title{margin:.5rem 0 0;font-size:1.35rem;font-weight:700}"
                + ".msg{margin:.75rem 0 1.5rem;color:#667082}"
                + ".signin{display:inline-block;padding:.5rem 1.1rem;border-radius:.5rem;background:#4263eb;"
                + "color:#fff;text-decoration:none;font-weight:600}.signin:hover{background:#364fc7}"
                + "</style></head><body><div class=\"card\">"
                + "<div class=\"icon\">🔒</div>"
                + "<p class=\"title\">Your session has expired</p>"
                + "<p class=\"msg\">This page's security token is no longer valid — the page may have sat open "
                + "too long, or the server was restarted. Please sign in again to continue; your work was not "
                + "changed.</p>"
                + "<a class=\"signin\" href=\"" + href + "\">Sign in again</a>"
                + "</div></body></html>";
    }
}
