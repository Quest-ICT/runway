package mx.quest.runway.plugin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.web.AppRealmPaths;
import mx.quest.runway.web.RequestPaths;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The fail-closed security floor: when an allowlisted-essential plugin is missing or broken
 * ({@link EssentialFloor#failed()}), tenant hosts get a {@code 503} rather than being served <b>without</b>
 * their security floor — "unavailable over insecure". It is surgical: it short-circuits only a
 * <b>bound tenant</b> request (public pages + {@code /__admin}), and <b>exempts the application realm</b>
 * ({@code /__app}, {@code /app/…}, {@code /actuator}) even on a tenant host, so an operator can still reach the
 * console and read why on the plugins screen. The fix is the copy of the plugin bundled with the running release
 * ({@link EssentialAllowlist#repairAdvice}), then a restart; disabling an essential plugin is not one — a disabled
 * essential plugin fails closed too. Runs in the TENANT band ({@code CORE_ESSENTIAL_FLOOR}),
 * after the site is bound and before the security chain.
 *
 * <p>(Accepted residual: while degraded, the app realm is reachable but its own edge rate-limit and login
 * lockout are the now-permissive kernel defaults; the compensating control is to bind the app realm to
 * localhost / behind the proxy's own rate limit — see the admin manual.)
 */
public class EssentialFloorFilter extends OncePerRequestFilter {

    private final SiteResolver resolver;
    private final EssentialFloor floor;

    public EssentialFloorFilter(SiteResolver resolver, EssentialFloor floor) {
        this.resolver = resolver;
        this.floor = floor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Site site = resolver.current().orElse(null);
        if (floor.failed() && site != null && !AppRealmPaths.isAppRealm(RequestPaths.normalized(request))) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("text/plain;charset=UTF-8");
            response.getOutputStream().write(
                    "This site is temporarily unavailable.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }
}
