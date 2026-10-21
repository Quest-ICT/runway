package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Supplies the model attributes the {@code op-layout} shell needs on <b>every</b> kernel operator page, so each
 * controller only sets its {@code contentTemplate} + {@code pageTitleKey}. Scoped to the {@code console}
 * package, so it never touches the tenant {@code PageController} — and, deliberately, so it does <b>not</b> fire
 * for a plugin's operator controller (which lives outside {@code mx.quest.runway.console}). A plugin that renders
 * a full operator screen annotates its controller with the SPI's {@code @ConsoleScreen}, which
 * {@link ConsoleScreenAdvice} serves from the same {@link OperatorShellModel} bean.
 *
 * <p>All population logic lives in {@link OperatorShellModel}; this advice is only the kernel-scoped trigger that
 * delegates to it.
 */
@ControllerAdvice(basePackages = "mx.quest.runway.console")
public class OperatorModelAdvice {

    private final OperatorShellModel shellModel;

    public OperatorModelAdvice(OperatorShellModel shellModel) {
        this.shellModel = shellModel;
    }

    @ModelAttribute
    public void shellModel(Model model, HttpServletRequest request, Authentication authentication) {
        shellModel.populate(model, request, authentication);
    }
}
