package mx.quest.runway.plugin;

import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;

/**
 * Hooks the wiring quarantine into a failed startup (registered in {@code META-INF/spring.factories}). Spring Boot
 * calls {@link #failed} after a refresh failure and <b>before</b> closing the context, so the bean definitions that
 * failed are still there to attribute. When the failure belongs to a non-essential plugin,
 * {@link PluginQuarantine#quarantine} writes the marker; {@code RunwayApplication.main} then exits with the
 * supervisor's relaunch code (42) instead of 1, and the next boot loads without that plugin.
 *
 * <p>Nothing happens in WIZARD mode or before the plugin loader ran (no {@link PluginRuntime} published), or when
 * the failure cannot be pinned on one plugin. Never throws: a broken quarantine must not mask the original
 * failure.
 */
public class PluginQuarantineListener implements SpringApplicationRunListener, Ordered {

    /** The signature Spring Boot instantiates run listeners with. */
    public PluginQuarantineListener(SpringApplication application, String[] args) {
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void failed(ConfigurableApplicationContext context, Throwable exception) {
        try {
            onFailure(context, exception);
        } catch (RuntimeException | LinkageError ignored) {
            // the original startup failure is what the operator must see
        }
    }

    /** The decision, separated from the listener contract for tests. */
    static void onFailure(ConfigurableApplicationContext context, Throwable exception) {
        // Not the singleton: a failed refresh has already destroyed it (see PluginContextInitializer#runtimeOf).
        PluginRuntime runtime = PluginContextInitializer.runtimeOf(context).orElse(null);
        if (runtime == null || runtime.loaded().isEmpty()) {
            return;
        }
        PluginOwnership ownership = PluginOwnership.of(runtime);
        PluginQuarantine.attribute(exception, context.getBeanFactory(), ownership, runtime.classLoader())
                .ifPresent(plugin -> PluginQuarantine.quarantine(
                        Path.of(context.getEnvironment().getProperty(PluginContextInitializer.EXTERNAL_PROPERTY,
                                "external")),
                        plugin, rootMessage(exception)));
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
