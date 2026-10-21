package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.protect.EdgeGuard;
import mx.quest.runway.protect.PermissiveEdgeGuard;
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
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The positive end-to-end (complement to {@code EssentialFloorArmingTest}): an allowlisted-essential plugin
 * whose {@code @Primary} {@code EdgeGuard}/{@code LoginAttemptGate} override <b>does</b> take effect arms the
 * floor — the resolved {@code EdgeGuard} is no longer the kernel default and {@link EssentialFloor#failed()} is
 * false. This is the path the real {@code runway-plugin-protection} module takes.
 */
@SpringBootTest
class EssentialFloorArmedTest {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-8i-armed");
        if (PluginFixture.compilerAvailable()) {
            PluginFixture.buildArmingProtection(external.resolve("plugins"));
        }
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Sup3r-Secret-9");
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"),
                new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private EssentialFloor floor;

    @Autowired
    private ApplicationContext context;

    @Test
    void anEssentialPluginThatArmsTheFloorIsNotFailClosed() {
        assumeTrue(PluginFixture.compilerAvailable(), "no in-JVM compiler (JRE-only) — skipping");
        assertFalse(context.getBean(EdgeGuard.class) instanceof PermissiveEdgeGuard,
                "the plugin's @Primary EdgeGuard won over the kernel default");
        assertFalse(floor.failed(), "an essential plugin that armed the floor must NOT be fail-closed");
    }

    @Test
    void theFloorFilterPassesWhenHealthy() {
        assumeTrue(PluginFixture.compilerAvailable(), "no in-JVM compiler (JRE-only) — skipping");
        assertTrue(!floor.failed());
    }
}
