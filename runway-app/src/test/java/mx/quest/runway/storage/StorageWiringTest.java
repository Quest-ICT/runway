package mx.quest.runway.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies the storage layer wires as a Spring config slice — fast, with no full application boot
 * and no servlet container (uses {@link ApplicationContextRunner}).
 */
class StorageWiringTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(StorageConfig.class);

    @Test
    void exposesStorageBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(StorageProperties.class);
            assertThat(context).hasSingleBean(StorageLayout.class);
            assertThat(context).hasSingleBean(YamlStore.class);
        });
    }

    @Test
    void bindsConfiguredPathsIntoTheLayout() {
        runner.withPropertyValues("runway.storage.external=/srv/runway/external")
                .run(context -> {
                    StorageLayout layout = context.getBean(StorageLayout.class);
                    assertThat(layout.external()).isEqualTo(Path.of("/srv/runway/external"));
                    assertThat(layout.sites()).isEqualTo(Path.of("/srv/runway/external/sites"));
                });
    }
}
