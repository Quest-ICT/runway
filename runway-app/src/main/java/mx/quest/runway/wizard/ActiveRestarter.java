package mx.quest.runway.wizard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * The ACTIVE-mode {@link Restarter}: the operator-triggered restart that applies a plugin install /
 * upgrade / removal or an app-level settings change (all of which are picked up only at boot, since the DI
 * context, template resolvers, security chains and MVC mappings are built once — restart-to-install is the
 * locked decision). It closes the context gracefully and exits with {@link #RESTART_EXIT_CODE} so the
 * supervisor relaunches; the fresh JVM re-discovers plugins and re-reads {@code external/plugin-settings}.
 *
 * <p>Mechanically identical to {@link SupervisedRestarter} (short-delayed non-daemon thread so the HTTP
 * response flushes first, then {@link SpringApplication#exit} + {@link System#exit}), but gated to the
 * complementary mode: present <b>only</b> in ACTIVE. The two never coexist (see {@link ActiveModeCondition}),
 * so there is always exactly one {@link Restarter} bean.
 *
 * <p>This bean only performs the relaunch; <b>authority</b> to ask for one is enforced at the controller —
 * a restart is a platform operation reserved to the application master / wildcard admin, checked through the
 * authorization service, never exposed as a grantable permission.
 */
@Component
@Conditional(ActiveModeCondition.class)
class ActiveRestarter implements Restarter {

    private static final Logger log = LoggerFactory.getLogger(ActiveRestarter.class);

    private final ConfigurableApplicationContext context;

    ActiveRestarter(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void restart() {
        log.info("Operator-requested restart — shutting down for supervised relaunch (exit {}).", RESTART_EXIT_CODE);
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(800); // let the operator's response flush before the JVM goes
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            int code = SpringApplication.exit(context, () -> RESTART_EXIT_CODE);
            System.exit(code);
        }, "runway-active-restart");
        thread.setDaemon(false);
        thread.start();
    }
}
