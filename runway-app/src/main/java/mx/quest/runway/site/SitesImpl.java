package mx.quest.runway.site;

import java.util.Objects;
import java.util.Optional;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.Sites;
import mx.quest.runway.storage.PathSafety;
import org.springframework.stereotype.Component;

/**
 * The kernel's {@link Sites}: a site's frozen {@link SiteView} by id, from {@link SiteStore} (cached, evicted on
 * every site change) through {@link SiteViews}, the one place a view is built. Suspended sites are included, as the
 * contract says; an unsafe id is refused before the store is consulted.
 */
@Component
public class SitesImpl implements Sites {

    private final SiteStore sites;

    public SitesImpl(SiteStore sites) {
        this.sites = Objects.requireNonNull(sites, "sites");
    }

    @Override
    public Optional<SiteView> find(String siteId) {
        if (siteId == null || !PathSafety.isSafeId(siteId)) {
            throw new IllegalArgumentException("unsafe site id: " + siteId);
        }
        return sites.find(siteId).map(SiteViews::toView);
    }
}
