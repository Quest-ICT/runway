package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import mx.quest.runway.spi.HeadContributor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plugins must NOT load in WIZARD mode (no app-master pinned) — a pre-placed plugin firing pre-setup, before any
 * tenants or app users exist, would be incoherent. The {@code alpha} fixture is present but must be ignored.
 */
@SpringBootTest
class PluginWizardModeTest {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-8c-wizard");
        if (PluginFixture.compilerAvailable()) {
            PluginFixture.buildAlpha(external.resolve("plugins")); // present, but must be ignored in WIZARD mode
        }
        registry.add("runway.storage.external", () -> external.toString());
        // deliberately NO runway.security.app-master-id → WIZARD mode
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void aPresentPluginIsNotLoadedInWizardMode() {
        assumeTrue(PluginFixture.compilerAvailable(), "no in-JVM compiler (JRE-only) — skipping");
        boolean alphaWired = context.getBeansOfType(HeadContributor.class).values().stream()
                .anyMatch(h -> "alpha.head".equals(h.key()));
        assertFalse(alphaWired, "the plugin must not wire while the platform is in first-run WIZARD mode");
    }
}
