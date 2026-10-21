package mx.quest.runway.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Checks the external folder's {@linkplain DataLayout data-layout marker} at the very start of every boot —
 * registered in {@code META-INF/spring.factories} and ordered {@link Ordered#HIGHEST_PRECEDENCE}, so it runs
 * after the {@code Environment} is prepared but <b>before</b> the plugin loader reads {@code external/} and before
 * any bean exists. That placement is the point: the downgrade guard must stop an older build before a single
 * store, seeder or plugin writes into a newer layout, and bean initialisation order gives no such guarantee.
 *
 * <p>The external folder is resolved exactly as the {@link StorageLayout} bean resolves it (by binding
 * {@code runway.storage.*} to {@link StorageProperties}), so the marker always sits where the stores write.
 *
 * <p><b>WIZARD mode</b> (no {@code runway.security.app-master-id} yet) may run against a folder that does not
 * exist: then nothing is written — there is no data to protect, and the first ACTIVE start writes the marker.
 * When the folder already exists, the check runs and the marker is written in either mode. A newer or malformed
 * marker refuses startup in either mode. Failing to <b>write</b> an absent marker is logged and tolerated (it is
 * retried on the next start); it never blocks a boot that worked before the marker existed.
 */
public class DataLayoutInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DataLayoutInitializer.class);

    static final String APP_MASTER_PROPERTY = "runway.security.app-master-id";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment env = context.getEnvironment();
        Path external = externalFolder(env);
        String pin = env.getProperty(APP_MASTER_PROPERTY);
        boolean wizard = pin == null || pin.isBlank();
        boolean mayWrite = !wizard || Files.isDirectory(external);

        DataLayout.Result result = DataLayout.verify(external, mayWrite); // throws DataLayoutException → no start
        switch (result.outcome()) {
            case WRITTEN -> log.info("Data layout: wrote {} (version {}).", result.marker(), result.version());
            case SUPPORTED -> log.debug("Data layout: {} is version {} (this build supports up to {}).",
                    result.marker(), result.version(), DataLayout.SUPPORTED_VERSION);
            case ABSENT_NOT_WRITTEN -> log.debug("Data layout: {} absent and the folder is new (WIZARD) — "
                    + "the first ACTIVE start writes it.", result.marker());
            case ABSENT_WRITE_FAILED -> log.warn("Data layout: could not write {} ({}); continuing — it is retried "
                    + "on the next start.", result.marker(), String.valueOf(result.failure()));
        }
    }

    /** The external folder, resolved through the same binding the {@link StorageLayout} bean uses. */
    static Path externalFolder(ConfigurableEnvironment env) {
        StorageProperties props = Binder.get(env).bind("runway.storage", StorageProperties.class)
                .orElseGet(StorageProperties::new);
        return StorageLayout.from(props).external();
    }
}
