package mx.quest.runway.wizard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The WIZARD-mode {@link Restarter}: closes the application context gracefully and exits with
 * {@link #RESTART_EXIT_CODE} so the supervisor relaunches the process and the fresh JVM re-reads the
 * external {@code application.yml} the wizard just wrote, coming up ACTIVE. It runs on a short-delayed
 * non-daemon thread so the wizard's HTTP response flushes to the operator first.
 *
 * <p>Present <b>only</b> in WIZARD mode (there is nothing to transition once ACTIVE); its ACTIVE-mode peer is
 * {@link ActiveRestarter}. The two conditions are mutually exclusive, so exactly one {@link Restarter} bean
 * is ever live — injection is never ambiguous.
 */
@Component
@Conditional(WizardModeCondition.class)
class SupervisedRestarter implements Restarter {

    private static final Logger log = LoggerFactory.getLogger(SupervisedRestarter.class);

    private final ConfigurableApplicationContext context;

    SupervisedRestarter(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void restart() {
        log.info("First-run setup complete — shutting down for supervised relaunch (exit {}).", RESTART_EXIT_CODE);
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(800); // let the wizard's response flush before the JVM goes
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            int code = SpringApplication.exit(context, () -> RESTART_EXIT_CODE);
            System.exit(code);
        }, "runway-wizard-restart");
        thread.setDaemon(false);
        thread.start();
    }
}
