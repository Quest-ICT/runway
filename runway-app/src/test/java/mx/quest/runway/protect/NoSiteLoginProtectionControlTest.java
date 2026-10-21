package mx.quest.runway.protect;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * The kernel's no-op {@link NoSiteLoginProtectionControl} floor (no protection plugin installed): a site never has
 * a login CAPTCHA to report, and disabling one is a silent no-op — so the app Sites screen works either way.
 */
class NoSiteLoginProtectionControlTest {

    private final NoSiteLoginProtectionControl control = new NoSiteLoginProtectionControl();

    @Test
    void captchaIsNeverEnabledWithoutTheProtectionPlugin() {
        assertFalse(control.captchaEnabled("any-site"));
    }

    @Test
    void disableCaptchaIsASilentNoOp() {
        assertDoesNotThrow(() -> control.disableCaptcha("any-site"));
    }
}
