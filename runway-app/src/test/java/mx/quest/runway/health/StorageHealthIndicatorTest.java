package mx.quest.runway.health;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** The storage health indicator: UP when the external dir is writable, DOWN when it is absent. */
class StorageHealthIndicatorTest {

    private static StorageHealthIndicator indicatorFor(Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return new StorageHealthIndicator(StorageLayout.from(props));
    }

    @Test
    void aWritableExternalDirIsUp(@TempDir Path external) {
        Health health = indicatorFor(external).health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals(true, health.getDetails().get("writable"));
    }

    @Test
    void anAbsentExternalDirIsDown(@TempDir Path tmp) {
        // Point at a child path that was never created — not a directory, so the probe reports DOWN.
        Health health = indicatorFor(tmp.resolve("gone")).health();
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(false, health.getDetails().get("present"));
    }
}
