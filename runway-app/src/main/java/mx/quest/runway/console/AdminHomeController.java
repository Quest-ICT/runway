package mx.quest.runway.console;

import mx.quest.runway.page.PageStore;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.SiteUserStore;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.spi.model.Realm;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The tenant-realm operator home ({@code /__admin}) — a dashboard for the bound tenancy: counts (pages, users)
 * over the tenant stores, rendered through the same {@code op-layout} shell but with the admin topbar and
 * tenant i18n ({@link OperatorModelAdvice} selects them by path). Counts are zero when no tenancy is bound.
 */
@Controller
public class AdminHomeController {

    private final PageStore pages;
    private final SiteUserStore siteUsers;
    private final SiteResolver siteResolver;
    private final DashboardCardRegistry cards;

    public AdminHomeController(PageStore pages, SiteUserStore siteUsers, SiteResolver siteResolver,
                             DashboardCardRegistry cards) {
        this.pages = pages;
        this.siteUsers = siteUsers;
        this.siteResolver = siteResolver;
        this.cards = cards;
    }

    @GetMapping({"/__admin", "/__admin/"})
    public String home(Authentication authentication, Model model) {
        String tenancyId = siteResolver.current().map(site -> site.id()).orElse(null);
        RunwayUserDetails principal = authentication != null
                && authentication.getPrincipal() instanceof RunwayUserDetails details ? details : null;
        model.addAttribute("countPages", tenancyId == null ? 0 : pages.findAll(tenancyId).size());
        model.addAttribute("countUsers", tenancyId == null ? 0 : siteUsers.list(tenancyId).size());
        model.addAttribute("dashboardCards", cards.cardsFor(Realm.TENANT, principal, tenancyId));
        model.addAttribute("contentTemplate", "operator/admin/home");
        model.addAttribute("pageTitleKey", "op.admin.home.title");
        return "operator/op-layout";
    }
}
