package mx.quest.runway.health;

import java.nio.file.Files;
import java.nio.file.Path;
import mx.quest.runway.storage.StorageLayout;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Contributes a {@code storage} entry to {@code /actuator/health} that reports whether the external data
 * directory — the kernel's only persistence, since Runway is DB-less — is present and writable. This turns
 * the health probe from pure liveness ("the JVM answers") into a real readiness signal: a deployment whose
 * data directory has vanished, gone read-only, or lost its mount flips the composite health to {@code DOWN},
 * which a supervisor/monitor can act on before writes start failing.
 *
 * <p>The bean name ({@code storageHealthIndicator}) names the health key ({@code storage}). Details (the
 * resolved path) surface only when health details are authorized, so the public probe never leaks the path.
 */
@Component
public class StorageHealthIndicator implements HealthIndicator {

    private final StorageLayout layout;

    public StorageHealthIndicator(StorageLayout layout) {
        this.layout = layout;
    }

    @Override
    public Health health() {
        Path external = layout.external();
        boolean present = Files.isDirectory(external);
        boolean writable = present && Files.isWritable(external);
        if (present && writable) {
            return Health.up()
                    .withDetail("external", external.toString())
                    .withDetail("writable", true)
                    .build();
        }
        return Health.down()
                .withDetail("external", external.toString())
                .withDetail("present", present)
                .withDetail("writable", writable)
                .build();
    }
}
