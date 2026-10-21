package mx.quest.runway.console;

import mx.quest.runway.bandwidth.BandwidthService;
import mx.quest.runway.bandwidth.BandwidthService.SiteBandwidth;
import mx.quest.runway.bandwidth.Bytes;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The application console's bandwidth screen ({@code /__app/bandwidth}): a 30-day aggregate across <b>all</b>
 * sites, busiest first, plus platform totals. This is the one deliberate exception to tenant→app isolation —
 * but it exposes only <b>aggregate byte counts</b> (never log entries or content), which is why it is allowed
 * to cross realms. Gated by the grantable {@code VIEW_BANDWIDTH} application permission.
 */
@Controller
public class AppBandwidthController {

    private final BandwidthService bandwidth;

    public AppBandwidthController(BandwidthService bandwidth) {
        this.bandwidth = bandwidth;
    }

    @GetMapping("/__app/bandwidth")
    @PreAuthorize("@authz.may('VIEW_BANDWIDTH')")
    public String view(Model model) {
        List<SiteBandwidth> sites = bandwidth.aggregate();
        model.addAttribute("sites", sites);
        model.addAttribute("totalIn", Bytes.human(sites.stream().mapToLong(SiteBandwidth::bytesIn).sum()));
        model.addAttribute("totalOut", Bytes.human(sites.stream().mapToLong(SiteBandwidth::bytesOut).sum()));
        model.addAttribute("contentTemplate", "operator/app/bandwidth");
        model.addAttribute("pageTitleKey", "op.appbandwidth.title");
        return "operator/op-layout";
    }
}
