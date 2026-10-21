package mx.quest.runway.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.session.SessionRepository;
import org.springframework.web.filter.DelegatingFilterProxy;

/**
 * Proves the load-bearing default: with clustering off (the shipped default — {@code runway.cluster.enabled}
 * unset), <b>none</b> of the cluster layer is wired. No {@link HazelcastInstance}, no {@link SessionRepository},
 * and — because {@code @EnableSpringHttpSession} lives on the (absent) {@code ClusterConfig} — no
 * {@code springSessionRepositoryFilter} either. The application is byte-for-byte today's single-node footprint,
 * using the container's own {@code HttpSession}. This is the guarantee that opt-in clustering costs a
 * non-clustered deployment nothing.
 *
 * <p>Boots in WIZARD mode (no {@code app-master-id}); that is irrelevant here since the master switch alone
 * keeps everything absent — {@link ClusterActiveModeGatingTest} covers the ACTIVE-mode gate separately.
 */
@SpringBootTest
class ClusterDisabledByDefaultTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void noHazelcastInstanceBeanExists() {
        assertTrue(context.getBeanNamesForType(HazelcastInstance.class).length == 0,
                "a HazelcastInstance must not be wired when clustering is off");
    }

    @Test
    void noSpringSessionRepositoryBeanExists() {
        assertTrue(context.getBeanNamesForType(SessionRepository.class).length == 0,
                "a SessionRepository must not be wired when clustering is off — the container HttpSession is used");
    }

    @Test
    void theClusterConfigurationItselfIsAbsent() {
        assertEquals(0, context.getBeanNamesForType(ClusterConfig.class).length,
                "ClusterConfig is class-level @ConditionalOnProperty-gated and must not be registered");
    }

    @Test
    void theClusterEventRelayIsAbsent() {
        // The relay is gated the same way as ClusterConfig; with clustering off it must not exist, so a local
        // PageChangedEvent (and its siblings) drive purely local eviction and nothing is ever broadcast.
        assertEquals(0, context.getBeanNamesForType(ClusterEventRelay.class).length,
                "ClusterEventRelay must not be wired when clustering is off — events stay local");
    }

    @Test
    void noSpringSessionRepositoryFilterIsRegistered() {
        // @EnableSpringHttpSession registers a bean literally named "springSessionRepositoryFilter"; its absence
        // proves the container's default session management is still in force.
        assertFalse(context.containsBean("springSessionRepositoryFilter"),
                "the Spring Session filter must be absent so the container HttpSession is used");
        // And no Spring-Session DelegatingFilterProxy sneaked in under another name.
        boolean anySessionFilter = context.getBeansOfType(DelegatingFilterProxy.class).keySet().stream()
                .anyMatch(name -> name.toLowerCase().contains("session"));
        assertFalse(anySessionFilter, "no Spring Session delegating filter proxy should be registered");
    }
}
