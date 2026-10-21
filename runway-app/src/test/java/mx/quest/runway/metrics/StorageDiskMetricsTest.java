package mx.quest.runway.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Disk-space exposition must never embed the release version in a label.
 *
 * <p>Micrometer's stock binder defaults to path {@code "."} and the launcher cds to the VERSIONED release dir, so
 * its label read {@code path="/opt/runway-<version>/."}: every upgrade minted a new series and abandoned the old
 * one, and any query pinning {@code path=} silently stopped matching — an empty result, never an error. A
 * monitored deployment accumulated ten such series in four weeks. This pins the replacement:
 * the stock binder off, and label-free gauges that read the external data directory instead.
 */
@SpringBootTest
class StorageDiskMetricsTest {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-storage-metrics");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Storage-Metrics-9");
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"),
                new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private MeterRegistry registry;

    @Test
    void noMeterCarriesAPathLabelThatCouldEmbedTheReleaseVersion() {
        assertThat(registry.getMeters())
                .as("a path-tagged disk meter re-mints its series on every upgrade")
                .noneMatch(m -> m.getId().getName().startsWith("disk.")
                        || m.getId().getTag("path") != null);
    }

    @Test
    void theStorageGaugesAreExposedLabelFreeAndReadTheDataDirectory() {
        var free = registry.find("runway.storage.free.bytes").gauge();
        var total = registry.find("runway.storage.total.bytes").gauge();
        assertThat(free).as("runway.storage.free.bytes is exposed").isNotNull();
        assertThat(total).as("runway.storage.total.bytes is exposed").isNotNull();
        assertThat(free.getId().getTags()).as("no labels at all — nothing that can drift").isEmpty();
        assertThat(free.value()).isGreaterThan(0);
        assertThat(total.value()).isGreaterThanOrEqualTo(free.value());
    }
}
