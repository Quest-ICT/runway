package mx.quest.carousel;

import mx.quest.runway.spi.service.PluginSettings;

/**
 * Read-only status model for the console surfaces (dashboard card + admin fragment), read by template bean name,
 * e.g. {@code ${@carouselManagerAdminModel.slideCount(tenantSiteId)}}.
 */
public class CarouselAdminModel {

    private final PluginSettings settings;

    public CarouselAdminModel(PluginSettings settings) {
        this.settings = settings;
    }

    /** How many valid slides the site has configured (the same count the widget would render). */
    public int slideCount(String siteId) {
        return CarouselWidget.slides(settings.site(siteId)).size();
    }
}
