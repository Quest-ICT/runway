package mx.quest.runway.console;

import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.spi.model.Realm;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts {@code platformAdmin} in every console model: whether the signed-in operator may change platform settings,
 * so a screen shows its Settings button (Backups, Bandwidth, Logs, Plugins → the kernel settings card of that
 * screen) only to someone it would not answer with 403.
 */
@ControllerAdvice(basePackages = "mx.quest.runway.console")
public class PlatformAdminModelAdvice {

    private final PlatformAuthority platformAuthority;

    public PlatformAdminModelAdvice(PlatformAuthority platformAuthority) {
        this.platformAuthority = platformAuthority;
    }

    /** Only an application-realm operator can be one; a site user never is (and is never asked about). */
    @ModelAttribute("platformAdmin")
    public boolean platformAdmin(Authentication authentication) {
        return authentication != null
                && authentication.getPrincipal() instanceof RunwayUserDetails principal
                && principal.realm() == Realm.APPLICATION
                && platformAuthority.isPlatformAdmin(authentication);
    }
}
