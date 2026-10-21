package mx.quest.runway.site;

import java.util.Objects;
import mx.quest.runway.spi.model.SiteView;

/**
 * Materializes the immutable {@link SiteView} (the SPI boundary type) from the stored {@link Site}.
 * This is the host doing the resolving the design requires: an SPI implementation never sees the
 * mutable {@link Site}, only the frozen view.
 */
public final class SiteViews {

    private SiteViews() {
    }

    /**
     * Builds the boundary view for {@code site}. The view's base URL is the site's explicit
     * {@code baseUrl} when set, otherwise derived from the primary host ({@code https://<host>}),
     * or {@code null} when the site declares no host.
     */
    public static SiteView toView(Site site) {
        Objects.requireNonNull(site, "site");
        String baseUrl = site.baseUrl() != null ? site.baseUrl()
                : site.hosts().isEmpty() ? null : "https://" + site.hosts().get(0);
        return new SiteView(site.id(), site.name(), site.defaultLocale(), site.locales(), baseUrl);
    }
}
