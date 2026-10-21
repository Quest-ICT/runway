package mx.quest.runway.plugin;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The <b>after-refresh</b> half of the plugin surface rules ({@link PluginSurfaceCheck}): once every singleton exists,
 * {@link PluginSurfaceCheck#afterRefresh} looks at every bean definition a non-essential plugin's own jar declares,
 * by real type — which catches what the before-refresh check cannot see, such as a component-scanned servlet
 * {@code Filter} or an unprefixed component name.
 *
 * <h2>A violation quarantines the plugin (SPI 1.0.0)</h2>
 * By the time a bean exists it is wired into the shared context and cannot be unloaded from this JVM, so the only way
 * to refuse it is the wiring quarantine: for each violating plugin this writes
 * {@code external/plugin-quarantine/<id>.yml} with the reason, logs an ERROR, and then fails the startup with
 * {@link SurfaceViolationException}.
 * {@code RunwayApplication.main} sees the marker and exits with the supervisor's relaunch code (42); the next boot
 * blocks the plugin before wiring, with the reason on the plugins screen, and comes up without it.
 *
 * <p>If the check itself cannot run (a defect in the kernel, not in a plugin) the boot continues: the failure is
 * logged and kept as a compliance note for the plugins screen ({@link #warnings}), because failing every boot over a
 * kernel bug would take the platform down for something no operator can fix.
 */
@Component
public class PluginCompliance implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PluginCompliance.class);

    /** Fails the startup after the violating plugins were quarantined (it names no plugin bean to re-attribute). */
    public static final class SurfaceViolationException extends IllegalStateException {
        SurfaceViolationException(String message) {
            super(message);
        }
    }

    private final ObjectProvider<PluginRuntime> runtimeProvider;
    private final PluginOwnership ownership;
    private final ConfigurableListableBeanFactory beanFactory;
    private final Environment environment;
    private volatile Map<String, List<String>> warnings = Map.of();

    public PluginCompliance(ObjectProvider<PluginRuntime> runtimeProvider, PluginOwnership ownership,
                            ConfigurableListableBeanFactory beanFactory, Environment environment) {
        this.runtimeProvider = runtimeProvider;
        this.ownership = ownership;
        this.beanFactory = beanFactory;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        PluginRuntime runtime = runtimeProvider.getIfAvailable();
        if (runtime == null || runtime.loaded().isEmpty()) {
            return;
        }
        Map<String, List<String>> violations;
        try {
            violations = PluginSurfaceCheck.afterRefresh(beanFactory, ownership, runtime.classLoader());
        } catch (RuntimeException | LinkageError failure) {
            log.error("the after-refresh plugin surface check could not run; plugins were NOT checked for "
                    + "component-scanned beans this boot", failure);
            Map<String, List<String>> notes = new LinkedHashMap<>();
            runtime.loaded().stream().filter(p -> !p.isLibraryOnly() && !EssentialAllowlist.contains(p.id()))
                    .forEach(p -> notes.put(p.id(), List.of("the after-refresh rule check could not run: " + failure)));
            warnings = Map.copyOf(notes);
            return;
        }
        if (violations.isEmpty()) {
            return;
        }
        Path external = Path.of(environment.getProperty(PluginContextInitializer.EXTERNAL_PROPERTY, "external"));
        StringBuilder summary = new StringBuilder("plugins broke the Spring-context rules and were quarantined:");
        violations.forEach((id, problems) -> {
            String reason = PluginContextInitializer.surfaceReason(problems);
            runtime.loaded().stream().filter(p -> p.id().equals(id)).findFirst()
                    .ifPresent(plugin -> PluginQuarantine.quarantine(external, plugin, reason));
            summary.append(' ').append(id).append(" (").append(reason).append(')');
        });
        throw new SurfaceViolationException(summary.toString());
    }

    /** The compliance notes recorded against {@code pluginId} this boot (empty when there are none). */
    public List<String> warnings(String pluginId) {
        return warnings.getOrDefault(pluginId, List.of());
    }

    /** Every plugin with compliance notes. */
    public Map<String, List<String>> all() {
        return warnings;
    }
}
