package mx.quest.runway.log;

import jakarta.servlet.http.HttpServletRequest;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.web.RequestPaths;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Records tenant sign-in outcomes and sign-outs to a site's <b>audit</b> log. It listens to Spring Security's
 * authentication and logout events (published on the request thread, while the site is still bound), so it needs
 * no change to the security chains. Only <b>tenant</b> events are audited here — an event with no bound site is
 * skipped, and so is one raised by the application realm's own login/logout paths ({@code /app/login},
 * {@code /app/logout}) even when the app console was reached on a host that also resolves to a site: those belong
 * to the application activity log ({@link AppAuditEventListener}), and an operator's name must not leak into a
 * tenant's journal.
 */
@Component
public class AuditEventListener {

    private final SiteResolver siteResolver;
    private final SiteLogService siteLog;

    public AuditEventListener(SiteResolver siteResolver, SiteLogService siteLog) {
        this.siteResolver = siteResolver;
        this.siteLog = siteLog;
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        record("login.success", event.getAuthentication().getName());
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        record("login.failure", event.getAuthentication().getName());
    }

    @EventListener
    public void onLogout(LogoutSuccessEvent event) {
        record("logout", event.getAuthentication().getName());
    }

    private void record(String action, String actor) {
        if (isAppRealmRequest()) {
            return;
        }
        siteResolver.current().ifPresent(site -> siteLog.audit(site.id(), actor, action, ""));
    }

    private static boolean isAppRealmRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return false;
        }
        HttpServletRequest request = attrs.getRequest();
        String path = RequestPaths.normalized(request);
        return AppAuditEventListener.APP_LOGIN_PATH.equals(path) || AppAuditEventListener.APP_LOGOUT_PATH.equals(path);
    }
}
