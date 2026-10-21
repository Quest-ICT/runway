package mx.quest.runway.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mx.quest.runway.content.ContentChangedEvent;
import mx.quest.runway.page.PageChangedEvent;
import mx.quest.runway.plugin.SitePluginConfigChangedEvent;
import mx.quest.runway.site.SiteChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Guards the kernel listener-order rule: every kernel {@code @EventListener} on one of the four content-shaping
 * host events carries an explicit {@code @Order} whose value is one of the {@link KernelEventOrder} bands. A
 * listener left at the default order is tie-broken by bean registration order — the mechanism that hid the
 * catalog-vs-page-cache defect ({@code KernelListenerOrderIntegrationTest}) — so a new one fails the build here.
 */
class KernelEventOrderTest {

    private static final Set<Class<?>> HOST_EVENTS = Set.of(SiteChangedEvent.class,
            SitePluginConfigChangedEvent.class, PageChangedEvent.class, ContentChangedEvent.class);

    @Test
    void everyKernelListenerOnTheFourHostEventsHasAnOrderFromKernelEventOrder() throws Exception {
        Set<Integer> bands = bandValues();
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (Class<?> type : mainClasses()) {
            Method[] methods;
            try {
                methods = type.getDeclaredMethods();
            } catch (LinkageError optionalDependencyMissing) {
                continue;
            }
            for (Method method : methods) {
                EventListener listener = method.getAnnotation(EventListener.class);
                if (listener == null || !listensToHostEvent(method, listener)) {
                    continue;
                }
                checked++;
                Order order = method.getAnnotation(Order.class);
                if (order == null) {
                    violations.add(type.getName() + "#" + method.getName() + " has no @Order");
                } else if (!bands.contains(order.value())) {
                    violations.add(type.getName() + "#" + method.getName() + " has @Order(" + order.value()
                            + "), which is not a KernelEventOrder constant");
                }
            }
        }
        assertTrue(checked >= 25, "the scan must actually find the kernel listeners (found " + checked + ")");
        assertEquals(List.of(), violations);
    }

    @Test
    void theBandsRunInnermostFirstAndPluginDispatchIsLast() {
        assertTrue(KernelEventOrder.SEED < KernelEventOrder.CAPTURE);
        assertTrue(KernelEventOrder.CAPTURE < KernelEventOrder.STORES);
        assertTrue(KernelEventOrder.STORES < KernelEventOrder.RESOURCES);
        assertTrue(KernelEventOrder.RESOURCES < KernelEventOrder.CATALOG);
        assertTrue(KernelEventOrder.CATALOG < KernelEventOrder.DERIVED);
        assertTrue(KernelEventOrder.DERIVED < KernelEventOrder.RELAY);
        assertTrue(KernelEventOrder.RELAY < KernelEventOrder.KERNEL_LATE);
        assertTrue(KernelEventOrder.KERNEL_LATE < KernelEventOrder.PLUGIN_DISPATCH);
        assertEquals(org.springframework.core.Ordered.LOWEST_PRECEDENCE, KernelEventOrder.PLUGIN_DISPATCH);
    }

    private static boolean listensToHostEvent(Method method, EventListener listener) {
        for (Class<?> declared : listener.classes()) {
            if (HOST_EVENTS.contains(declared)) {
                return true;
            }
        }
        for (Class<?> declared : listener.value()) {
            if (HOST_EVENTS.contains(declared)) {
                return true;
            }
        }
        return method.getParameterCount() == 1 && HOST_EVENTS.contains(method.getParameterTypes()[0]);
    }

    private static Set<Integer> bandValues() throws IllegalAccessException {
        Set<Integer> values = new HashSet<>();
        for (Field field : KernelEventOrder.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                values.add(field.getInt(null));
            }
        }
        return values;
    }

    /** Every production class under {@code mx.quest.runway} (test classes excluded), loaded without initializing. */
    private static List<Class<?>> mainClasses() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mx/quest/runway/**/*.class");
        List<Class<?>> classes = new ArrayList<>();
        ClassLoader loader = KernelEventOrderTest.class.getClassLoader();
        for (Resource resource : resources) {
            String url = resource.getURL().toString();
            if (url.contains("/test-classes/") || url.contains("/mx/quest/runway/spi/")) {
                continue;
            }
            int at = url.lastIndexOf("mx/quest/runway/");
            String name = url.substring(at, url.length() - ".class".length()).replace('/', '.');
            try {
                classes.add(Class.forName(name, false, loader));
            } catch (ClassNotFoundException | LinkageError skip) {
                // a class whose optional dependency is absent from the test classpath cannot hold a listener we run
            }
        }
        return classes;
    }
}
