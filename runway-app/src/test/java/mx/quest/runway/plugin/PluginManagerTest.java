package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.spi.model.PluginInfo;
import mx.quest.runway.spi.service.PluginLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * The manager calls the SPI lifecycle hooks plugin by plugin in <b>load order</b> (not bean order), isolates a failing
 * {@code started()} under the containment policy, and calls {@code stopping()} in reverse — only for hooks that
 * started. (The
 * host-internal {@code RunwayPlugin} hook this test used to exercise was removed at SPI 1.0.0.)
 */
class PluginManagerTest {

    /** Distinct classes, so the (mocked) ownership can attribute each hook to its plugin by class, as the jar does. */
    private abstract static class Recording implements PluginLifecycle {
        private final List<String> log;
        private final boolean failStart;

        Recording(List<String> log, boolean failStart) {
            this.log = log;
            this.failStart = failStart;
        }

        abstract String id();

        @Override
        public void started(PluginInfo self) {
            if (failStart) {
                throw new IllegalStateException("boom");
            }
            log.add("start:" + id() + "@" + self.version());
        }

        @Override
        public void stopping() {
            log.add("stop:" + id());
        }
    }

    private static final class HookA extends Recording {
        HookA(List<String> log) {
            super(log, false);
        }

        @Override
        String id() {
            return "a";
        }
    }

    private static final class HookBoom extends Recording {
        HookBoom(List<String> log) {
            super(log, true);
        }

        @Override
        String id() {
            return "boom";
        }
    }

    private static final class HookB extends Recording {
        HookB(List<String> log) {
            super(log, false);
        }

        @Override
        String id() {
            return "b";
        }
    }

    private static LoadedPlugin lp(String id) {
        PluginDescriptor d = PluginDescriptor.read(new ByteArrayInputStream(
                ("id: " + id + "\nversion: 2.0.0\n").getBytes(StandardCharsets.UTF_8)));
        return new LoadedPlugin(d, Path.of("/tmp/plugins", id));
    }

    @Test
    @SuppressWarnings("unchecked")
    void startsInLoadOrderIsolatesFailuresAndStopsInReverse() {
        List<String> log = new ArrayList<>();
        // Load order a, boom, b — registered as beans in the OPPOSITE order, so bean order cannot pass for load order.
        PluginRuntime runtime = new PluginRuntime(
                List.of(lp("a"), lp("boom"), lp("b")), getClass().getClassLoader(), Map.of(), false);
        PluginOwnership ownership = mock(PluginOwnership.class);
        when(ownership.pluginIdOf(any())).thenAnswer(inv -> {
            Class<?> type = inv.getArgument(0);
            if (type == HookA.class) {
                return Optional.of("a");
            }
            if (type == HookBoom.class) {
                return Optional.of("boom");
            }
            return type == HookB.class ? Optional.of("b") : Optional.empty();
        });
        ContributionGuard guard = new ContributionGuard(ownership, mock(ObjectProvider.class));

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("hookB", PluginLifecycle.class, () -> new HookB(log));
            ctx.registerBean("hookBoom", PluginLifecycle.class, () -> new HookBoom(log));
            ctx.registerBean("hookA", PluginLifecycle.class, () -> new HookA(log));
            ctx.registerBean(ContributionGuard.class, () -> guard);
            ctx.getDefaultListableBeanFactory().registerSingleton(PluginRuntime.BEAN_NAME, runtime);
            ctx.register(EssentialFloor.class); // the manager sets the fail-closed flag post-refresh
            ctx.register(PluginManager.class);
            ctx.refresh();

            assertEquals(List.of("start:a@2.0.0", "start:b@2.0.0"), log,
                    "boom's started() failure is isolated; a and b start, in load order, with their own PluginInfo");
            assertEquals(3, ctx.getBean(PluginManager.class).installed().size());
            assertEquals(1, guard.health("boom").failures(), "the failure is counted against its plugin");
            assertTrue(guard.health("boom").lastError().contains("boom"), guard.health("boom").toString());
        } // close() -> destroy() -> stopping() in reverse start order

        assertEquals(List.of("start:a@2.0.0", "start:b@2.0.0", "stop:b", "stop:a"), log,
                "stopping runs in reverse start order, and never for the hook that did not start");
    }
}
