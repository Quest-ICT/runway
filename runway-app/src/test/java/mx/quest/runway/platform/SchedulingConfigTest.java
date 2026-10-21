package mx.quest.runway.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import mx.quest.runway.backup.ScheduledBackupRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * Scheduling is a platform service, on with the default configuration — automatic backups <b>off</b>: a
 * {@code @Scheduled} bean (standing in for a plugin's, e.g. an IndexNow flush) runs, on the one named
 * scheduler pool, and nothing else in the kernel is scheduled. Before {@link SchedulingConfig}, the only
 * {@code @EnableScheduling} came with automatic backups, and this probe never ran.
 */
@SpringBootTest
@Import(SchedulingConfigTest.Probe.class)
class SchedulingConfigTest {

    /** A scheduled bean that is not the backup runner. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Probe {
        static final CountDownLatch TICKS = new CountDownLatch(3);
        static final AtomicReference<String> THREAD = new AtomicReference<>();

        @Bean
        Probe.Ticker schedulingProbeTicker() {
            return new Ticker();
        }

        static class Ticker {
            @Scheduled(fixedDelay = 20)
            void tick() {
                THREAD.set(Thread.currentThread().getName());
                TICKS.countDown();
            }
        }
    }

    @Autowired
    private ApplicationContext context;

    /** Every task the context's scheduling post-processors registered, as {@code fully.qualified.Class.method}. */
    static Set<String> scheduledMethods(ApplicationContext context) {
        Set<String> out = new TreeSet<>();
        context.getBeansOfType(ScheduledTaskHolder.class).values().forEach(holder ->
                holder.getScheduledTasks().forEach(task -> out.add(task.getTask().toString())));
        return out;
    }

    @Test
    void aScheduledBeanRunsWithAutomaticBackupsOff() throws InterruptedException {
        assertTrue(context.getBeansOfType(ScheduledBackupRunner.class).isEmpty(), "automatic backups are off");
        assertTrue(Probe.TICKS.await(10, TimeUnit.SECONDS), "the @Scheduled probe never ran");
        assertTrue(Probe.THREAD.get().startsWith(SchedulingConfig.THREAD_NAME_PREFIX),
                "it runs on the named platform scheduler, not " + Probe.THREAD.get());
    }

    @Test
    void thereIsOneSchedulerPoolOfFourThreads() {
        ThreadPoolTaskScheduler scheduler = assertInstanceOf(ThreadPoolTaskScheduler.class,
                context.getBean("taskScheduler"));
        assertEquals(4, scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
        assertEquals(SchedulingConfig.THREAD_NAME_PREFIX, scheduler.getThreadNamePrefix());
        assertEquals(1, context.getBeansOfType(org.springframework.scheduling.TaskScheduler.class).size());
    }

    @Test
    void boot_sApplicationTaskExecutorIsStillThere() {
        // A scheduler bean of our own would also be an Executor and make Boot drop this one; we use Boot's.
        assertTrue(context.containsBean("applicationTaskExecutor"));
    }

    @Test
    void nothingElseInTheKernelIsScheduledByDefault() {
        assertEquals(Set.of(Probe.Ticker.class.getName() + ".tick"), scheduledMethods(context),
                "turning scheduling on globally must not start any other kernel work");
    }
}
