package mx.quest.runway.log;

import jakarta.servlet.http.HttpServletRequest;
import mx.quest.runway.web.RequestPaths;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Records <b>application-realm</b> sign-in outcomes and sign-outs to the {@link AppActivityLog}. It listens to
 * Spring Security's authentication events and the {@link LogoutSuccessEvent} the logout filter publishes (both on
 * the request thread) and records only events raised by the app login ({@code POST /app/login}) or app logout
 * ({@code /app/logout}) — tenant events are left to {@link AuditEventListener}, which records them to the bound
 * site's audit log. Keying on the request path (not on whether a site is bound) keeps the split unambiguous even
 * when the app console is reached on a host that also resolves to a site. The path is the decoded one Spring
 * Security matched ({@link RequestPaths#normalized}): a sign-in posted to {@code /app/%6Cogin} is the app login, and
 * once went unrecorded here while landing in the bound site's audit log instead.
 */
@Component
public class AppAuditEventListener {

    static final String APP_LOGIN_PATH = "/app/login";
    static final String APP_LOGOUT_PATH = "/app/logout";

    private final AppActivityLog activity;

    public AppAuditEventListener(AppActivityLog activity) {
        this.activity = activity;
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        record(APP_LOGIN_PATH, "login.success", event.getAuthentication().getName());
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        record(APP_LOGIN_PATH, "login.failure", event.getAuthentication().getName());
    }

    @EventListener
    public void onLogout(LogoutSuccessEvent event) {
        record(APP_LOGOUT_PATH, "logout", event.getAuthentication().getName());
    }

    private void record(String path, String action, String actor) {
        HttpServletRequest request = currentRequest();
        if (request == null || !path.equals(RequestPaths.normalized(request))) {
            return; // not an app-realm event — the tenant listener handles /login and /logout
        }
        activity.record(actor, action, "ip=" + clientIp(request));
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }

    /** The best-effort client IP (honors X-Forwarded-For only when the container was told to trust the proxy). */
    private static String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? "" : ip;
    }
}
