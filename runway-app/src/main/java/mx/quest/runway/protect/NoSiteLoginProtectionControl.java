package mx.quest.runway.protect;

import mx.quest.runway.spi.SiteLoginProtectionControl;
import org.springframework.stereotype.Component;

/**
 * The kernel's no-op {@link SiteLoginProtectionControl} floor: with no protection plugin installed a site has no
 * CAPTCHA to report or disable, so {@link #captchaEnabled(String)} is always {@code false} and
 * {@link #disableCaptcha(String)} does nothing. It lets the app-realm Sites screen compile and run whether or not
 * the protection plugin is present.
 *
 * <p>The essential <b>protection</b> plugin registers a {@code @Primary} implementation that reads and rewrites
 * each site's per-site protection config; being {@code @Primary}, it wins by-type injection over this default
 * wherever the plugin is installed (the same floor/override pattern as {@link mx.quest.runway.spi.CaptchaService} /
 * the login {@code EdgeGuard} seams).
 */
@Component
public class NoSiteLoginProtectionControl implements SiteLoginProtectionControl {

    @Override
    public boolean captchaEnabled(String siteId) {
        return false;
    }

    @Override
    public void disableCaptcha(String siteId) {
        // No protection plugin: a site never has a CAPTCHA to disable.
    }
}
