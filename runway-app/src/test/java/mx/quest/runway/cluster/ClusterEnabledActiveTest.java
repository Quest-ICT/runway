package mx.quest.runway.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hazelcast.core.HazelcastInstance;
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
import org.springframework.context.ApplicationContext;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The positive counterpart to the two gating tests: with clustering enabled AND the platform ACTIVE (an
 * {@code app-master-id} pinned), {@link ClusterConfig} activates and wires a real embedded {@link
 * HazelcastInstance}, the hand-rolled {@link SessionRepository}, and the {@code springSessionRepositoryFilter}
 * from {@code @EnableSpringHttpSession}. It boots a genuine single-member Hazelcast grid inside the test JVM (an
 * isolated cluster name; {@code port-auto-increment} so it never clashes) and confirms the sessions map is live
 * and its {@code max-idle-seconds} was sourced from the configured session TTL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ClusterEnabledActiveTest {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-cluster-active");
        YamlStore yaml = new YamlStore();
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Cluster-Active-7");
        new UserStore(yaml).save(external.resolve("app-users.yml"),
                new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", external::toString);
        registry.add("runway.security.app-master-id", () -> "app-master"); // -> ACTIVE mode

        // Clustering ON, but on an ISOLATED single-member grid so the test never joins a real cluster and
        // never fights for a fixed port.
        registry.add("runway.cluster.enabled", () -> "true");
        registry.add("runway.cluster.cluster-name", () -> "rw-active-test-" + System.nanoTime());
        registry.add("runway.cluster.topology", () -> "embedded");
        registry.add("runway.cluster.embedded.discovery", () -> "tcp-ip");
        registry.add("runway.cluster.embedded.port", () -> "16801");
        registry.add("runway.cluster.embedded.port-auto-increment", () -> "true");
        registry.add("runway.cluster.session-ttl", () -> "20m");
    }

    @Autowired
    private ApplicationContext context;
    @Autowired
    private HazelcastInstance hazelcast;
    @Autowired
    private SessionRepository<MapSession> sessionRepository;

    @Test
    void theClusterBeansAreWiredAndTheSessionMapIsLive() {
        assertEquals(1, context.getBeanNamesForType(ClusterConfig.class).length, "ClusterConfig should activate");
        assertEquals(1, context.getBeanNamesForType(ClusterEventRelay.class).length,
                "ClusterEventRelay should activate alongside ClusterConfig when enabled+active");
        assertNotNull(hazelcast, "a HazelcastInstance must be wired");
        assertNotNull(sessionRepository, "the hand-rolled session repository must be wired");
        assertTrue(context.containsBean("springSessionRepositoryFilter"),
                "@EnableSpringHttpSession must register the Spring Session filter when active");

        // The repository round-trips a session through the real embedded grid.
        MapSession created = sessionRepository.createSession();
        assertEquals(java.time.Duration.ofMinutes(20), created.getMaxInactiveInterval(),
                "createSession stamps the configured session-ttl");
        created.setAttribute("hello", "world");
        sessionRepository.save(created);
        MapSession found = sessionRepository.findById(created.getId());
        assertNotNull(found);
        assertEquals("world", found.getAttribute("hello"));

        // The sessions map's idle-eviction was sourced from the configured TTL (20m = 1200s).
        assertEquals(1200, hazelcast.getConfig().getMapConfig("runway.sessions").getMaxIdleSeconds(),
                "sessions map max-idle-seconds must equal the configured TTL in seconds");
    }
}
