package mx.quest.runway.wizard;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when Runway is in <b>WIZARD</b> mode — i.e. no application superadmin has been pinned yet
 * ({@code runway.security.app-master-id} is blank/absent). The mode is <b>derived from this one fact</b>,
 * never a separate {@code mode} knob, so configuration can't contradict reality: before first-run setup
 * the property is unset (WIZARD); the wizard writes it into the external {@code application.yml} and the
 * supervised relaunch comes up with it set (ACTIVE), where this condition no longer matches and the whole
 * wizard layer is absent.
 */
public class WizardModeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String pin = context.getEnvironment().getProperty("runway.security.app-master-id");
        return pin == null || pin.isBlank();
    }
}
