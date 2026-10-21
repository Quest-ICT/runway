package mx.quest.runway.platform;

import mx.quest.runway.plugin.ContainedSchedulingPostProcessor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

/**
 * Turns on Spring's {@code @Scheduled} processing for the whole platform — kernel and plugins alike —
 * <b>unconditionally</b> (the equivalent of {@link EnableScheduling}, see below).
 *
 * <h2>Why here, and why always</h2>
 * Until this existed, the only {@link EnableScheduling} sat on the automatic-backup configuration, which exists only
 * with {@code runway.backups.schedule.enabled=true}. Every other {@code @Scheduled} method — a plugin's periodic
 * flush, such as an IndexNow submitter — was silently never run unless the operator had happened to
 * switch automatic backups on. Scheduling is a platform service, so the kernel owns it; what a feature schedules
 * stays that feature's own decision (automatic backups are still opt-in: their runner bean exists only when enabled).
 *
 * <h2>The pool</h2>
 * The scheduler itself is Spring Boot's {@code taskScheduler} (a {@code ThreadPoolTaskScheduler}), configured in
 * the kernel's {@code application.yml}: {@code spring.task.scheduling.pool.size} (default <b>4</b>, so one long
 * nightly backup cannot hold up a plugin's short periodic work) and {@code spring.task.scheduling.thread-name-prefix}
 * ({@value #THREAD_NAME_PREFIX}, so the threads are recognisable in a thread dump). We let Boot build it rather than
 * declare our own bean on purpose: a scheduler bean is also an {@code Executor}, and declaring one would make Boot
 * drop its {@code applicationTaskExecutor}. The pool creates threads only as tasks are scheduled, so a platform with
 * nothing scheduled starts none. One pool serves everyone: a plugin must keep its {@code @Scheduled} methods short
 * and never block indefinitely, or it delays every other scheduled task.
 *
 * <h2>Plugin tasks are contained (SPI 1.0.0)</h2>
 * This class registers the {@code @Scheduled} processor itself — what {@link EnableScheduling} would register, under
 * the same bean name ({@link TaskManagementConfigUtils#SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME}, which is also what
 * Boot's scheduler auto-configuration keys on) — as a {@link ContainedSchedulingPostProcessor}: a plugin's scheduled
 * task that throws is logged once, then rate-limited, and counted against the plugin (the containment policy), instead of a
 * stack trace on every run. Kernel tasks run exactly as before.
 */
@Configuration(proxyBeanMethods = false)
public class SchedulingConfig {

    /** The scheduler threads' name prefix (mirrors {@code spring.task.scheduling.thread-name-prefix}). */
    public static final String THREAD_NAME_PREFIX = "runway-scheduler-";

    /**
     * The {@code @Scheduled} processor ({@code @EnableScheduling}'s, with plugin tasks contained). Static and an
     * infrastructure role, like Spring's own registration: a bean post-processor must not drag its configuration
     * class into early instantiation.
     */
    @Bean(name = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static ScheduledAnnotationBeanPostProcessor scheduledAnnotationProcessor() {
        return new ContainedSchedulingPostProcessor();
    }
}
