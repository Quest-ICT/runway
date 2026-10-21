package mx.quest.runway.protect;

import mx.quest.runway.spi.CaptchaService;
import mx.quest.runway.spi.model.CaptchaWidget;
import org.springframework.stereotype.Component;

/**
 * The kernel's permissive {@link CaptchaService} floor: no site ever has a configured challenge, so every widget
 * is {@link CaptchaWidget#inactive() inactive} and every verification passes. It lets a form (the contact form,
 * any plugin/page form) render + verify unconditionally and simply have no CAPTCHA when no provider is installed.
 *
 * <p>The essential <b>protection</b> plugin registers a {@code @Primary} implementation that reads each site's own
 * CAPTCHA settings and arms real challenges; being {@code @Primary}, it wins by-type injection over this default
 * wherever the plugin is present (the same floor/override pattern as the login {@code EdgeGuard} /
 * {@code LoginFormGuard} seams). With the plugin absent, this default keeps forms working — never blocking a
 * submission by a CAPTCHA a site did not ask for.
 */
@Component
public class NoCaptchaService implements CaptchaService {

    @Override
    public CaptchaWidget widgetForSite(String siteId) {
        return CaptchaWidget.inactive();
    }

    @Override
    public boolean verify(String siteId, String token, String clientIp) {
        return true;
    }
}
