package mx.quest.runway.security;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.RunwayApplication;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Fail-closed: a tampered application store must make the whole context refuse to start, not boot open. */
class FailClosedStartupTest {

    @Test
    void aSecondSmuggledMasterAbortsStartup() throws IOException {
        Path external = Files.createTempDirectory("runway-failclosed");
        UserStore users = new UserStore(new YamlStore());
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("x");
        Path file = external.resolve("app-users.yml");
        users.save(file, new User("app-master", "root", hash, "", "", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        // Smuggle a SECOND master (a different principalId, so the per-record mutation guard never fires).
        users.save(file, new User("ghost", "ghost", hash, "", "", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));

        // The application context must fail to refresh (RealmActivation's startup validate() throws),
        // rather than booting and serving 'ghost' as a live platform superadmin.
        assertThrows(Exception.class, () -> {
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(RunwayApplication.class)
                    .properties("runway.storage.external=" + external,
                            "runway.security.app-master-id=app-master",
                            "server.port=0")
                    .run()) {
                // unreachable on a tampered store
            }
        });
    }
}
