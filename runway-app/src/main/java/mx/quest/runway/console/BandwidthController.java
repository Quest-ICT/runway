package mx.quest.runway.console;

import mx.quest.runway.bandwidth.BandwidthService;
import mx.quest.runway.bandwidth.SiteBandwidthQuotas;
import mx.quest.runway.site.SiteResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * The tenant console's bandwidth screen ({@code /__admin/bandwidth}): a 30-day chart of the site's own data
 * transfer plus 24h/7d/30d totals. Gated to the site's administrators via {@link TenantAuthority} — a tenant
 * sees only its own numbers, and its monthly cap read-only (the operator sets it on the Sites screen). (Aggregate metering across all sites is an operator view at {@code /__app/bandwidth}.)
 */
@Controller
public class BandwidthController {

    private static final int DAYS = 30;

    private final SiteResolver siteResolver;
    private final BandwidthService bandwidth;
    private final TenantAuthority tenantAuthority;
    private final SiteBandwidthQuotas quotas;

    public BandwidthController(SiteResolver siteResolver, BandwidthService bandwidth, TenantAuthority tenantAuthority,
                               SiteBandwidthQuotas quotas) {
        this.quotas = quotas;
        this.siteResolver = siteResolver;
        this.bandwidth = bandwidth;
        this.tenantAuthority = tenantAuthority;
    }

    @GetMapping("/__admin/bandwidth")
    public String view(Authentication authentication, Model model) {
        String siteId = siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"))
                .id();
        tenantAuthority.require(authentication, siteId);
        model.addAttribute("report", bandwidth.report(siteId, DAYS));
        model.addAttribute("quota", quotas.of(siteId));
        model.addAttribute("overQuota", bandwidth.isOverQuota(siteId));
        model.addAttribute("contentTemplate", "operator/admin/bandwidth");
        model.addAttribute("pageTitleKey", "op.bandwidth.title");
        return "operator/op-layout";
    }
}
