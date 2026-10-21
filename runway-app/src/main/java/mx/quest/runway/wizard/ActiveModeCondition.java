package mx.quest.runway.wizard;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when Runway is in <b>ACTIVE</b> mode — the exact complement of {@link WizardModeCondition}: an
 * application superadmin <b>has</b> been pinned ({@code runway.security.app-master-id} is present and
 * non-blank). Like its sibling, the mode is derived from this one fact, never a separate {@code mode} knob.
 *
 * <p>The two conditions partition every boot: WIZARD before first-run setup, ACTIVE after. Beans gated on
 * one are absent under the other, so a pair of mutually-exclusive implementations (e.g. the two
 * {@link Restarter}s) never both exist — injection stays unambiguous with no {@code @Primary} arbitration.
 */
public class ActiveModeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String pin = context.getEnvironment().getProperty("runway.security.app-master-id");
        return pin != null && !pin.isBlank();
    }
}
