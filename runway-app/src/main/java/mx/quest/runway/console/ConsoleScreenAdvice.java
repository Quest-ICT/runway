package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletRequest;
import mx.quest.runway.spi.service.ConsoleScreen;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Fills the operator shell's model for every controller annotated {@link ConsoleScreen} — the SPI way for a plugin
 * screen to render inside {@code op-layout}. It delegates to the same {@link OperatorShellModel} the
 * kernel's own {@link OperatorModelAdvice} uses, so a plugin screen and a kernel screen get exactly the same chrome.
 *
 * <p>It replaced the per-plugin {@code @ControllerAdvice} that injected {@code OperatorShellModel} (a host class,
 * which a plugin may not link from SPI 1.0.0). {@code populate} is idempotent, so a controller that some other advice
 * also covers still gets the same attributes.
 */
@ControllerAdvice(annotations = ConsoleScreen.class)
public class ConsoleScreenAdvice {

    private final OperatorShellModel shellModel;

    public ConsoleScreenAdvice(OperatorShellModel shellModel) {
        this.shellModel = shellModel;
    }

    @ModelAttribute
    public void consoleScreenShell(Model model, HttpServletRequest request, Authentication authentication) {
        shellModel.populate(model, request, authentication);
    }
}
