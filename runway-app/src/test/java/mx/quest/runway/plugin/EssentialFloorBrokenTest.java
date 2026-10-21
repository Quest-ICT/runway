package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * The critical fail-closed branch (review #4): an allowlisted-essential plugin that is <b>physically present on
 * disk</b> but did not load (here: disabled — the same path as malformed/blocked) must drive degraded mode. It
 * is invisible to the filtered discovery list, so the kernel checks the raw plugin folders. No compiler needed.
 */
@SpringBootTest
class EssentialFloorBrokenTest {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-8h-broken");
        PluginFixture.writeDisabledProtection(external.resolve("plugins"));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Sup3r-Secret-9");
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"),
                new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private EssentialFloor floor;

    @Test
    void aPresentButUnloadableEssentialPluginFailsClosed() {
        assertTrue(floor.failed(),
                "an installed-but-not-loaded essential plugin must fail closed, even though discovery dropped it");
    }
}
