package mx.quest.runway.console;

import mx.quest.runway.log.SiteInsightsService;
import mx.quest.runway.site.SiteResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * The tenant console's <b>Site insights</b> screen ({@code /__admin/insights}): the site's top 404 paths and
 * most-visited pages over the last 7 or 30 days, aggregated by {@link SiteInsightsService} from the site's own
 * access log. Read-only.
 *
 * <p>Gated by the grantable {@code VIEW_SITE_INSIGHTS} permission (default-granted to no role, so out of the box
 * only the site's master and wildcard admins pass — the same reach as the logs screen — and a superadmin can
 * delegate it). The site is always the request's <b>bound</b> site; {@code days} is the only input, and it is
 * normalized to one of {@link SiteInsightsService#PERIODS} before use.
 */
@Controller
public class SiteInsightsController {

    private final SiteResolver siteResolver;
    private final SiteInsightsService insights;

    public SiteInsightsController(SiteResolver siteResolver, SiteInsightsService insights) {
        this.siteResolver = siteResolver;
        this.insights = insights;
    }

    @GetMapping("/__admin/insights")
    @PreAuthorize("@authz.may('VIEW_SITE_INSIGHTS')")
    public String view(@RequestParam(name = "days", required = false) String days, Model model) {
        String siteId = siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"))
                .id();
        int requested = SiteInsightsService.normalizePeriod(parse(days));
        model.addAttribute("report", insights.report(siteId, requested));
        model.addAttribute("periods", insights.periods(siteId));
        model.addAttribute("requestedDays", requested);
        model.addAttribute("retentionDays", insights.retentionDays(siteId));
        model.addAttribute("contentTemplate", "operator/admin/insights");
        model.addAttribute("pageTitleKey", "op.insights.title");
        return "operator/op-layout";
    }

    /** Lenient: a missing or non-numeric {@code days} is "no choice" (the default period), never a 400. */
    private static Integer parse(String days) {
        if (days == null || days.isBlank() || days.length() > 4) {
            return null;
        }
        try {
            return Integer.valueOf(days.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
