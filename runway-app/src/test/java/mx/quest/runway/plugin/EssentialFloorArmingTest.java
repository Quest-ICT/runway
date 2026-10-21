package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

/**
 * The silent-open case: an allowlisted-essential plugin that LOADS but does not arm the floor (its {@code @Primary}
 * override is silently absent) must drive fail-closed mode — not a silent open. Booting with the inert
 * {@code protection} fixture, {@link EssentialFloor#failed()} must be true.
 */
@SpringBootTest
class EssentialFloorArmingTest {

    @org.springframework.test.context.DynamicPropertySource
    static void config(org.springframework.test.context.DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-8h-arming");
        if (PluginFixture.compilerAvailable()) {
            PluginFixture.buildInertProtection(external.resolve("plugins"));
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
    private PluginManager pluginManager;

    @Test
    void anEssentialPluginThatDoesNotArmTheFloorFailsClosed() {
        assumeTrue(PluginFixture.compilerAvailable(), "no in-JVM compiler (JRE-only) — skipping");
        assertTrue(pluginManager.installed().stream().anyMatch(p -> "protection".equals(p.id())),
                "the protection fixture loaded");
        assertTrue(floor.failed(), "loaded-but-unarmed essential plugin must fail closed (no silent open)");
    }
}
