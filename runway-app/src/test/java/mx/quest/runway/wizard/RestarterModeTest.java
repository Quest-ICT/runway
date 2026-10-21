package mx.quest.runway.wizard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The two {@link Restarter}s partition every boot by mode: exactly one is ever wired, so injection is never
 * ambiguous and the supervised-relaunch contract has a single owner per mode. We assert wiring only — never
 * {@link Restarter#restart()}, which calls {@link System#exit} and would kill the test JVM.
 */
class RestarterModeTest {

    /** The runner registers both candidates; {@link ActiveModeCondition}/{@link WizardModeCondition} pick one. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SupervisedRestarter.class, ActiveRestarter.class);

    @Test
    void activeModePinnedWiresOnlyTheActiveRestarter() {
        runner.withPropertyValues("runway.security.app-master-id=app-master").run(context -> {
            assertThat(context).hasSingleBean(Restarter.class);
            assertThat(context).hasSingleBean(ActiveRestarter.class);
            assertThat(context).doesNotHaveBean(SupervisedRestarter.class);
        });
    }

    @Test
    void wizardModeNoPinWiresOnlyTheSupervisedRestarter() {
        runner.run(context -> { // no app-master-id property → WIZARD
            assertThat(context).hasSingleBean(Restarter.class);
            assertThat(context).hasSingleBean(SupervisedRestarter.class);
            assertThat(context).doesNotHaveBean(ActiveRestarter.class);
        });
    }

    @Test
    void aBlankPinIsStillWizardMode() {
        runner.withPropertyValues("runway.security.app-master-id=  ").run(context -> {
            assertThat(context).hasSingleBean(SupervisedRestarter.class);
            assertThat(context).doesNotHaveBean(ActiveRestarter.class);
        });
    }

    @Test
    void theRestartExitCodeIsTheSupervisorContract() {
        assertEquals(42, Restarter.RESTART_EXIT_CODE, "must match systemd RestartForceExitStatus / launcher loop");
    }
}
