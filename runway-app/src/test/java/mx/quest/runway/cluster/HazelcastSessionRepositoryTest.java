package mx.quest.runway.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.User;
import mx.quest.runway.spi.model.Realm;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;

/**
 * Drives the hand-rolled {@link HazelcastSessionRepository} against a <b>real, two-member</b> embedded
 * Hazelcast cluster to prove the whole clustered-session promise end to end:
 *
 * <ul>
 *   <li>a session (with a serialized Spring-Security principal attribute) <b>saved through node A is visible on
 *       node B</b> — the point of clustering;</li>
 *   <li>the repository's {@code createSession} / {@code save} / {@code findById} / {@code deleteById} contract,
 *       its defensive copies, its session-id rotation on save, and its expiry handling all behave.</li>
 * </ul>
 *
 * <p>Two in-JVM members joined over loopback tcp-ip on an isolated (nanotime-suffixed) cluster name; the map's
 * entries round-trip through Hazelcast's default Java serializer, exercising the same path a real deployment
 * uses. No network beyond loopback, no external broker — safe offline.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HazelcastSessionRepositoryTest {

    private static final int PORT_A = 15801;
    private static final int PORT_B = 15802;
    private static final String MAP = "runway.sessions";
    private static final Duration TTL = Duration.ofMinutes(30);

    private HazelcastInstance nodeA;
    private HazelcastInstance nodeB;
    private SessionRepository<MapSession> repoA;
    private SessionRepository<MapSession> repoB;

    @BeforeAll
    void startTwoMemberCluster() {
        String cluster = "rw-session-test-" + System.nanoTime();
        nodeA = member(cluster, PORT_A);
        nodeB = member(cluster, PORT_B);
        // Wait until both members actually see each other, so a save on one is guaranteed visible on the other.
        Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .until(() -> nodeA.getCluster().getMembers().size() == 2
                        && nodeB.getCluster().getMembers().size() == 2);
        IMap<String, MapSession> mapA = nodeA.getMap(MAP);
        IMap<String, MapSession> mapB = nodeB.getMap(MAP);
        repoA = new HazelcastSessionRepository(mapA, TTL);
        repoB = new HazelcastSessionRepository(mapB, TTL);
    }

    private static HazelcastInstance member(String clusterName, int port) {
        Config config = new Config().setClusterName(clusterName);
        // Keep the test quiet/offline: no phone-home, no auto-detection, explicit loopback members only.
        config.setProperty("hazelcast.phone.home.enabled", "false");
        JoinConfig join = config.getNetworkConfig()
                .setPort(port).setPortAutoIncrement(false)
                .getJoin();
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true)
                .setMembers(List.of("127.0.0.1:" + PORT_A, "127.0.0.1:" + PORT_B));
        return Hazelcast.newHazelcastInstance(config);
    }

    @AfterAll
    void stopCluster() {
        if (nodeA != null) {
            nodeA.shutdown();
        }
        if (nodeB != null) {
            nodeB.shutdown();
        }
    }

    @Test
    void aSessionSavedOnOneNodeIsReadableWithItsPrincipalOnAnother() {
        MapSession session = repoA.createSession();
        assertEquals(TTL, session.getMaxInactiveInterval(), "createSession stamps the configured TTL");

        // Store the exact attribute a logged-in request would: a SecurityContext with a RunwayUserDetails
        // principal. This is what forces the whole identity graph across the wire.
        User user = new User("app-master", "root", "hash", "Root", "", true, true, Realm.APPLICATION,
                java.util.Set.of(Roles.SYSTEM_SUPERADMIN), List.of("*"));
        RunwayUserDetails principal = new RunwayUserDetails(user);
        SecurityContextImpl securityContext = new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(principal, "hash", principal.getAuthorities()));
        session.setAttribute("SPRING_SECURITY_CONTEXT::acme", securityContext);
        String id = session.getId();
        repoA.save(session);

        // Read it back through the OTHER node's repository — the cross-node visibility that is the whole point.
        MapSession fromB = repoB.findById(id);
        assertNotNull(fromB, "a session saved on node A must be found on node B");
        SecurityContextImpl restored = fromB.getAttribute("SPRING_SECURITY_CONTEXT::acme");
        assertNotNull(restored);
        RunwayUserDetails restoredPrincipal = (RunwayUserDetails) restored.getAuthentication().getPrincipal();
        assertEquals("app-master", restoredPrincipal.principalId());
        assertEquals(Realm.APPLICATION, restoredPrincipal.realm());
        assertTrue(restoredPrincipal.master());
    }

    @Test
    void findReturnsADefensiveCopyNotTheStoredObject() {
        MapSession session = repoA.createSession();
        session.setAttribute("k", "v1");
        repoA.save(session);

        MapSession first = repoA.findById(session.getId());
        assertNotSame(session, first, "findById returns a fresh copy, never the caller's object");
        // Mutating the returned copy must not change the stored entry until an explicit save.
        first.setAttribute("k", "MUTATED");
        MapSession second = repoA.findById(session.getId());
        assertEquals("v1", second.getAttribute("k"), "an unsaved mutation must not leak into the store");
    }

    @Test
    void saveUnderARotatedIdRemovesTheOldKey() {
        MapSession session = repoA.createSession();
        String original = session.getId();
        repoA.save(session);

        String rotated = session.changeSessionId(); // Spring Security does this on login (fixation defense)
        repoA.save(session);

        assertNull(repoB.findById(original), "the pre-rotation id must be gone cluster-wide");
        assertNotNull(repoB.findById(rotated), "the rotated id must be the live one");
    }

    @Test
    void deleteRemovesTheSessionClusterWide() {
        MapSession session = repoA.createSession();
        repoA.save(session);
        assertNotNull(repoB.findById(session.getId()));

        repoB.deleteById(session.getId());
        assertNull(repoA.findById(session.getId()), "a delete on one node removes it everywhere");
    }

    @Test
    void findTreatsAnExpiredSessionAsAbsentAndEvictsIt() {
        MapSession session = repoA.createSession();
        session.setMaxInactiveInterval(Duration.ZERO); // already idle-expired
        session.setLastAccessedTime(java.time.Instant.now().minusSeconds(60));
        repoA.save(session);

        assertNull(repoA.findById(session.getId()), "an expired session reads as null");
        // And it was actively evicted, not merely hidden: the raw map no longer holds it.
        assertNull(nodeA.getMap(MAP).get(session.getId()), "the expired entry was deleted on read");
    }
}
