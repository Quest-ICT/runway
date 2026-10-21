package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * A plugin's {@code @Scheduled} task under the containment policy: a throwing run is contained, counted against the plugin
 * on the {@code scheduled} axis, and the next run still happens; a kernel task is not touched (its failure still
 * reaches the scheduler's error handler); an {@link Error} other than a {@link LinkageError} propagates.
 */
class ContainedSchedulingTest {

    /** Stands in for a plugin's scheduled bean (attributed by class through the mocked ownership). */
    static final class PluginTicker {
    }

    /** Stands in for a kernel scheduled bean. */
    static final class KernelTicker {
    }

    private record Fixture(ContributionGuard guard, SimpleMeterRegistry meters, DefaultListableBeanFactory factory) {
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        PluginOwnership ownership = mock(PluginOwnership.class);
        when(ownership.pluginIdOf(any())).thenAnswer(inv ->
                inv.getArgument(0) == PluginTicker.class ? Optional.of("ticker") : Optional.empty());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meters);
        ContributionGuard guard = new ContributionGuard(ownership, provider);
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("contributionGuard", guard);
        return new Fixture(guard, meters, factory);
    }

    private static Runnable task(Object target, Runnable body, DefaultListableBeanFactory factory) {
        return new ContainedSchedulingPostProcessor.ContainedTask(body, target, target.getClass().getSimpleName()
                + ".tick", factory);
    }

    @Test
    void aThrowingPluginTaskIsContainedCountedAndRunsAgain() {
        Fixture f = fixture();
        AtomicInteger runs = new AtomicInteger();
        Runnable task = task(new PluginTicker(), () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("tick failed");
        }, f.factory());

        task.run();
        task.run();
        task.run();

        assertEquals(3, runs.get(), "every scheduled run happens");
        assertEquals(3, f.guard().health("ticker").failures(), "each failure counts against the plugin");
        assertTrue(f.guard().health("ticker").lastError().contains("tick failed"));
        assertEquals(3.0, f.meters().get(ContributionGuard.METRIC).tag("plugin", "ticker")
                .tag("axis", "scheduled").tag("key", "PluginTicker.tick").counter().count());
    }

    @Test
    void aLinkageErrorFromAPluginTaskIsContainedToo() {
        Fixture f = fixture();
        task(new PluginTicker(), () -> {
            throw new NoSuchMethodError("built against another kernel");
        }, f.factory()).run();
        assertEquals(1, f.guard().health("ticker").failures());
    }

    @Test
    void anyOtherErrorPropagates() {
        Fixture f = fixture();
        Runnable task = task(new PluginTicker(), () -> {
            throw new OutOfMemoryError("simulated");
        }, f.factory());
        assertThrows(OutOfMemoryError.class, task::run);
    }

    @Test
    void aKernelTaskRunsBareItsFailureStillReachesTheScheduler() {
        Fixture f = fixture();
        Runnable task = task(new KernelTicker(), () -> {
            throw new IllegalStateException("kernel defect");
        }, f.factory());
        assertThrows(IllegalStateException.class, task::run, "kernel work keeps its loud failure mode");
        assertEquals(0, f.guard().health(ContributionGuard.KERNEL).failures(), "and is not counted as contained");
    }

    @Test
    void withNoGuardAvailableTheTaskRunsBare() {
        Runnable task = task(new PluginTicker(), () -> {
            throw new IllegalStateException("x");
        }, new DefaultListableBeanFactory());
        assertThrows(IllegalStateException.class, task::run);
    }

    @Test
    void theTaskDescribesItselfAsTheWrappedMethod() {
        Runnable inner = new Runnable() {
            @Override
            public void run() {
            }

            @Override
            public String toString() {
                return "fixtures.Ticker.tick";
            }
        };
        assertEquals("fixtures.Ticker.tick", task(new PluginTicker(), inner, fixture().factory()).toString(),
                "the scheduled-task listing reads as before");
    }
}
