package mx.quest.runway.console;

import java.util.List;
import mx.quest.runway.authz.AuthorizationService;
import mx.quest.runway.authz.Permissions;
import mx.quest.runway.plugin.RunningPlugins;
import mx.quest.runway.security.AppUserStore;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The application-realm operator home ({@code /__app}) — a dashboard rendered through the {@code op-layout}
 * shell: at-a-glance counts (sites, application users, loaded plugins) over the already-tested stores, plus the
 * plugin dashboard-card host. {@link OperatorModelAdvice} supplies the chrome (app topbar, plugin menu).
 *
 * <p><b>Gate.</b> The dashboard content — the counts <em>and</em> the plugin cards — needs
 * {@link Permissions#VIEW_APP_DASHBOARD}. The route itself does not: it is where every application sign-in lands,
 * so an operator without the permission gets a minimal landing (a heading and a pointer to the menu) instead of a
 * {@code 403} after a successful login. The application superadmin and {@code SYSTEMADMIN} pass as always; a
 * {@code SYSTEMOPERATOR} sees the dashboard once the permission is granted to them or one of their roles. A plugin
 * card that declares its own permission still needs it as well.
 */
@Controller
public class AppHomeController {

    private final SiteStore sites;
    private final AppUserStore appUsers;
    private final RunningPlugins runningPlugins;
    private final DashboardCardRegistry cards;
    private final AuthorizationService authorization;

    public AppHomeController(SiteStore sites, AppUserStore appUsers, RunningPlugins runningPlugins,
                            DashboardCardRegistry cards, AuthorizationService authorization) {
        this.sites = sites;
        this.appUsers = appUsers;
        this.runningPlugins = runningPlugins;
        this.cards = cards;
        this.authorization = authorization;
    }

    @GetMapping({"/__app", "/__app/"})
    public String home(Authentication authentication, Model model) {
        RunwayUserDetails principal = authentication != null
                && authentication.getPrincipal() instanceof RunwayUserDetails details ? details : null;
        boolean dashboard = principal != null
                && authorization.mayAccess(principal, Permissions.VIEW_APP_DASHBOARD, null);
        model.addAttribute("canViewDashboard", dashboard);
        if (dashboard) {
            model.addAttribute("countSites", sites.findAll().size());
            model.addAttribute("countUsers", appUsers.list().size());
            model.addAttribute("countPlugins", runningPlugins.installed().size());
        }
        model.addAttribute("dashboardCards",
                dashboard ? cards.cardsFor(Realm.APPLICATION, principal, null) : List.of());
        model.addAttribute("contentTemplate", "operator/app/home");
        model.addAttribute("pageTitleKey", "op.app.home.title");
        return "operator/op-layout";
    }
}
