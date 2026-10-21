package mx.quest.runway.plugin;

import java.lang.reflect.Method;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * Spring's {@code @Scheduled} processor with plugin work brought under the containment policy. Registered by {@code
 * platform.SchedulingConfig} under Spring's own processor bean name, in place of
 * {@code @EnableScheduling}'s, so every {@code @Scheduled} method in the context — kernel and plugins alike — goes
 * through it.
 *
 * <h2>Why</h2>
 * A plugin's periodic task that keeps throwing used to reach the scheduler's default error handler, which logs a
 * full stack trace on <b>every</b> run — a task every few seconds fills the log with identical traces and hides
 * everything else, while the plugins screen showed nothing. Each task now runs through {@link ContributionGuard} on
 * the {@code scheduled} axis, like any other call into plugin code:
 * <ul>
 *   <li>a {@link RuntimeException} or {@link LinkageError} is caught; the next run happens on schedule as before (the
 *       scheduler's own handler suppressed it too, so the timing is unchanged);</li>
 *   <li>the first failure per task logs a WARN with the stack trace, then at most one WARN per minute with the count
 *       of suppressed failures;</li>
 *   <li>the counter {@code runway.plugin.contribution.failures{plugin,axis="scheduled",key}} and the plugin's
 *       failure count and last error on the plugins screen.</li>
 * </ul>
 * Any other {@link Error} propagates, as on every axis. The key is {@code <DeclaringClass>.<method>}.
 *
 * <h2>Kernel tasks are untouched</h2>
 * A task whose bean does not come from a plugin's own jar runs exactly as before (its failure reaches the
 * scheduler's error handler): the kernel's own scheduled work — the backup cron — keeps its loud failure mode, since
 * a kernel defect must never be rate-limited out of sight. Ownership is decided once, at the first run (the guard is
 * a bean that does not exist yet when this processor wraps tasks during bean creation), and remembered.
 *
 * <p>Reactive and Kotlin-coroutine {@code @Scheduled} methods do not go through {@link #createRunnable} in Spring and
 * are not contained; no plugin uses them.
 */
public class ContainedSchedulingPostProcessor extends ScheduledAnnotationBeanPostProcessor {

    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
        super.setBeanFactory(beanFactory);
    }

    @Override
    protected Runnable createRunnable(Object target, Method method, String qualifier) {
        Runnable task = super.createRunnable(target, method, qualifier);
        return new ContainedTask(task, target, method.getDeclaringClass().getSimpleName() + "." + method.getName(),
                beanFactory);
    }

    /**
     * One scheduled method's runnable: plugin-owned work runs under the guard, kernel work runs bare. Its
     * {@link #toString()} is the wrapped task's ({@code fully.qualified.Class.method}), so the scheduled-task listing
     * reads as before.
     */
    static final class ContainedTask implements Runnable {

        private final Runnable task;
        private final Object target;
        private final String key;
        private final BeanFactory beanFactory;
        /** The guard for a plugin's task; {@link #KERNEL_TASK} for kernel work; {@code null} until decided. */
        private volatile Object decision;

        private static final Object KERNEL_TASK = new Object();

        ContainedTask(Runnable task, Object target, String key, BeanFactory beanFactory) {
            this.task = task;
            this.target = target;
            this.key = key;
            this.beanFactory = beanFactory;
        }

        @Override
        public void run() {
            Object current = decision;
            if (current == null) {
                current = decide();
                decision = current;
            }
            if (current instanceof ContributionGuard guard) {
                guard.run(target, ContributionGuard.Axis.SCHEDULED, key, task);
            } else {
                task.run();
            }
        }

        private Object decide() {
            ContributionGuard guard = beanFactory == null ? null
                    : beanFactory.getBeanProvider(ContributionGuard.class).getIfAvailable();
            if (guard == null || ContributionGuard.KERNEL.equals(guard.pluginOf(target))) {
                return KERNEL_TASK;
            }
            return guard;
        }

        /** The guard key ({@code <DeclaringClass>.<method>}); for tests. */
        String key() {
            return key;
        }

        @Override
        public String toString() {
            return task.toString();
        }
    }
}
