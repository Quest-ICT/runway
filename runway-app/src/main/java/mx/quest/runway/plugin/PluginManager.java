package mx.quest.runway.plugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.protect.EdgeGuard;
import mx.quest.runway.protect.PermissiveEdgeGuard;
import mx.quest.runway.security.LoginAttemptGate;
import mx.quest.runway.security.LoginFormGuard;
import mx.quest.runway.security.PermissiveLoginAttemptGate;
import mx.quest.runway.security.PermissiveLoginFormGuard;
import mx.quest.runway.spi.model.PluginInfo;
import mx.quest.runway.spi.service.PluginLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The post-refresh half of the loader. As a {@link SmartInitializingSingleton} it runs once every singleton is
 * live: it reads the {@link PluginRuntime} the initializer published and, plugin by plugin in load order, calls
 * the lifecycle hooks — the SPI's {@link PluginLifecycle#started(PluginInfo)} (each hook matched to its plugin by
 * the jar its class came from) — <b>each isolated</b> under the containment policy ({@link ContributionGuard}), so
 * one plugin's
 * failure never stops the others. On shutdown it calls {@code stopping()} in <b>reverse</b> order (dependents before
 * their providers), only for hooks that started. It is also the read model behind the operator
 * plugins-management UI ({@link #installed()}).
 *
 * <p>A plugin that contributes only beans / SPI impls (no {@link PluginLifecycle}) is wired by type at refresh and
 * simply has no lifecycle callback here — which is correct. (The host-internal {@code RunwayPlugin}/
 * {@code PluginContext} hook, which handed a plugin the host {@code ApplicationContext}, was removed at SPI 1.0.0: no
 * plugin can implement it any more, because linking a host class blocks the plugin.)
 */
@Component
public class PluginManager implements SmartInitializingSingleton, DisposableBean, RunningPlugins {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final ApplicationContext context;
    private final ObjectProvider<PluginRuntime> runtimeProvider;
    private final EssentialFloor essentialFloor;
    private final ObjectProvider<EdgeGuard> edgeGuardProvider;
    private final ObjectProvider<LoginAttemptGate> loginGateProvider;
    private final ObjectProvider<LoginFormGuard> loginFormGuardProvider;
    private final ObjectProvider<PluginLifecycle> lifecycleProvider;
    private final ObjectProvider<ContributionGuard> guardProvider;
    /** Every hook whose start returned normally, most recent first — so stopping pops in reverse load order. */
    private final Deque<Runnable> stopHooks = new ArrayDeque<>();

    private volatile PluginRuntime runtime;

    public PluginManager(ApplicationContext context, ObjectProvider<PluginRuntime> runtimeProvider,
                         EssentialFloor essentialFloor,
                         ObjectProvider<EdgeGuard> edgeGuardProvider,
                         ObjectProvider<LoginAttemptGate> loginGateProvider,
                         ObjectProvider<LoginFormGuard> loginFormGuardProvider,
                         ObjectProvider<PluginLifecycle> lifecycleProvider,
                         ObjectProvider<ContributionGuard> guardProvider) {
        this.context = context;
        this.runtimeProvider = runtimeProvider;
        this.essentialFloor = essentialFloor;
        this.edgeGuardProvider = edgeGuardProvider;
        this.loginGateProvider = loginGateProvider;
        this.loginFormGuardProvider = loginFormGuardProvider;
        this.lifecycleProvider = lifecycleProvider;
        this.guardProvider = guardProvider;
    }

    @Override
    public void afterSingletonsInstantiated() {
        runtime = runtimeProvider.getIfAvailable(() -> PluginRuntime.empty(context.getClassLoader()));
        ContributionGuard guard = guardProvider.getIfAvailable(ContributionGuard::standalone);
        Map<String, List<PluginLifecycle>> hooksByPlugin = lifecyclesByPlugin(guard);
        for (LoadedPlugin loaded : runtime.loaded()) {
            PluginInfo self = PluginScopedServices.infoOf(loaded);
            for (PluginLifecycle hook : hooksByPlugin.getOrDefault(loaded.id(), List.of())) {
                String key = loaded.id() + ":" + hook.getClass().getName();
                if (guard.run(hook, ContributionGuard.Axis.LIFECYCLE, key + ".started", () -> hook.started(self))) {
                    stopHooks.push(() -> guard.run(hook, ContributionGuard.Axis.LIFECYCLE, key + ".stopping",
                            hook::stopping));
                }
            }
        }
        if (!stopHooks.isEmpty()) {
            log.info("started {} plugin lifecycle hook(s)", stopHooks.size());
        }
        essentialFloor.set(runtime.essentialFailed() || essentialPluginLoadedButFloorNotArmed());
    }

    /**
     * Every {@link PluginLifecycle} bean grouped by the plugin whose own jar its class came from. A hook with no
     * plugin owner (kernel code, a {@code lib/} jar) is ignored with a warning: it could not be called "in plugin
     * load order", and a lifecycle hook that silently never ran would be worse than a visible refusal.
     */
    private Map<String, List<PluginLifecycle>> lifecyclesByPlugin(ContributionGuard guard) {
        Map<String, List<PluginLifecycle>> byPlugin = new LinkedHashMap<>();
        lifecycleProvider.orderedStream().forEach(hook -> {
            String owner = guard.pluginOf(hook);
            if (ContributionGuard.KERNEL.equals(owner)) {
                log.warn("ignoring PluginLifecycle {}: it is not loaded from a plugin's own jar",
                        hook.getClass().getName());
                return;
            }
            byPlugin.computeIfAbsent(owner, id -> new java.util.ArrayList<>()).add(hook);
        });
        return byPlugin;
    }

    /**
     * The dangerous silent-open case: an allowlisted-essential plugin loaded, but one of its
     * {@code @Primary} overrides did not take effect — the resolved {@code EdgeGuard}, {@code LoginAttemptGate}, or
     * {@code LoginFormGuard} is still the kernel's permissive default — so the floor is unarmed and we must fail
     * closed. All three seams must be armed: the rate limit, the per-IP lockout, AND the honeypot/CAPTCHA form
     * guard.
     */
    private boolean essentialPluginLoadedButFloorNotArmed() {
        boolean essentialLoaded = runtime.loaded().stream().anyMatch(p -> EssentialAllowlist.contains(p.id()));
        if (!essentialLoaded) {
            return false;
        }
        EdgeGuard edgeGuard = edgeGuardProvider.getIfAvailable();
        LoginAttemptGate loginGate = loginGateProvider.getIfAvailable();
        LoginFormGuard formGuard = loginFormGuardProvider.getIfAvailable();
        boolean armed = edgeGuard != null && !(edgeGuard instanceof PermissiveEdgeGuard)
                && loginGate != null && !(loginGate instanceof PermissiveLoginAttemptGate)
                && formGuard != null && !(formGuard instanceof PermissiveLoginFormGuard);
        if (!armed) {
            log.error("essential plugin loaded but its @Primary EdgeGuard/LoginAttemptGate/LoginFormGuard override "
                    + "did not arm the security floor — entering fail-closed degraded mode");
        }
        return !armed;
    }

    @Override
    public void destroy() {
        while (!stopHooks.isEmpty()) {
            stopHooks.pop().run(); // reverse of start order; each isolated by the guard
        }
    }

    /** The plugins that loaded this boot (the {@link RunningPlugins} seam the admin read model joins against). */
    @Override
    public List<LoadedPlugin> installed() {
        PluginRuntime current = runtime;
        return current == null ? List.of() : current.loaded();
    }
}
