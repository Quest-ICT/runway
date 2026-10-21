package mx.quest.runway.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves the per-operation layer is actually live: {@code @EnableMethodSecurity} intercepts a
 * {@code @PreAuthorize("@authz.may(...)")} method and routes the decision through {@code mayAccess}. The
 * unit tests cover the helper's logic; this confirms the Spring wiring (the advisor, the {@code authz} bean).
 */
@SpringBootTest
class MethodSecurityIntegrationTest {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-6e");
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Sup3r-Secret-9");
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"),
                new User("app-master", "admin", hash, "Admin", "a@x", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master"); // ACTIVE mode
    }

    /** A guarded bean — plain class so method security proxies it; the kernel has no business ops yet. */
    @TestConfiguration
    static class Beans {
        @Bean
        Guarded guarded() {
            return new Guarded();
        }
    }

    static class Guarded {
        @PreAuthorize("@authz.may('VIEW_AUDIT')")
        public String audit() {
            return "ok";
        }
    }

    @Autowired
    private Guarded guarded;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(RunwayUserDetails principal) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private static RunwayUserDetails user(String id, boolean master, String... roles) {
        return new RunwayUserDetails(
                new User(id, id, "$h", "", "", true, master, Realm.APPLICATION, Set.of(roles), List.of()));
    }

    @Test
    void theGuardDeniesAPrincipalWithoutThePermission() {
        authenticate(user("op", false, Roles.SYSTEM_OPERATOR));
        assertThrows(AccessDeniedException.class, guarded::audit);
    }

    @Test
    void theGuardAllowsTheMaster() {
        authenticate(user("app-master", true, Roles.SYSTEM_SUPERADMIN));
        assertEquals("ok", guarded.audit());
    }
}
