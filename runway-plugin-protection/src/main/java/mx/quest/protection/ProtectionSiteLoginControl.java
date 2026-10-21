package mx.quest.protection;

import java.io.IOException;
import java.io.UncheckedIOException;
import mx.quest.runway.spi.SiteLoginProtectionControl;

/**
 * The essential protection plugin's implementation of the app-realm {@link SiteLoginProtectionControl} rescue seam:
 * it reports and disables a site's login CAPTCHA over the {@link ProtectionConfigStore} it already owns. Registered
 * {@code @Primary} in {@link ProtectionConfig}, so it wins by-type injection over the kernel's no-op default — the
 * app Sites screen can rescue a tenant locked out by a broken CAPTCHA without knowing anything about this plugin.
 */
public class ProtectionSiteLoginControl implements SiteLoginProtectionControl {

    private final ProtectionConfigStore store;

    public ProtectionSiteLoginControl(ProtectionConfigStore store) {
        this.store = store;
    }

    @Override
    public boolean captchaEnabled(String siteId) {
        return store.siteCaptchaEnabled(siteId);
    }

    @Override
    public void disableCaptcha(String siteId) {
        try {
            store.disableSiteCaptcha(siteId);
        } catch (IOException e) {
            // Surface as unchecked so the app controller's catch renders an error flash rather than a 500.
            throw new UncheckedIOException("could not disable login CAPTCHA for site '" + siteId + "'", e);
        }
    }
}
