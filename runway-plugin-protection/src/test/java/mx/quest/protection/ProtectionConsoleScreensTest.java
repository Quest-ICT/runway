package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import mx.quest.runway.spi.service.ConsoleScreen;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * The protection screens render in the console shell through the SPI's {@code @ConsoleScreen} (the kernel's
 * advice fills the shell), not through a plugin {@code @ControllerAdvice} that links the host's
 * {@code OperatorShellModel}.
 */
class ProtectionConsoleScreensTest {

    @Test
    void theScreensThatRenderTheShellAreConsoleScreens() {
        assertTrue(SiteProtectionController.class.isAnnotationPresent(ConsoleScreen.class));
        assertTrue(DosController.class.isAnnotationPresent(ConsoleScreen.class));
    }

    @Test
    void thePluginRegistersNoControllerAdviceAndNoShellModel() {
        for (Method bean : ProtectionConfig.class.getDeclaredMethods()) {
            assertFalse(bean.getReturnType().isAnnotationPresent(ControllerAdvice.class),
                    bean.getName() + " returns a @ControllerAdvice");
            assertFalse(Arrays.stream(bean.getParameterTypes())
                    .anyMatch(type -> type.getName().equals("mx.quest.runway.console.OperatorShellModel")),
                    bean.getName() + " injects the host's OperatorShellModel");
        }
    }

    @Test
    void theTenantScreenReadsItsSiteThroughTheSpi() {
        assertTrue(Arrays.stream(SiteProtectionController.class.getConstructors()[0].getParameterTypes())
                .anyMatch(type -> type == mx.quest.runway.spi.service.RequestContext.class));
        assertFalse(Arrays.stream(SiteProtectionController.class.getDeclaredFields())
                .anyMatch(field -> field.getType().getName().equals("mx.quest.runway.site.SiteResolver")));
    }
}
