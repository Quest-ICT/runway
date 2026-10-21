package mx.quest.runway.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.session.SessionRepository;

/**
 * Proves the second gate on {@link ClusterConfig}: even with the master switch ON
 * ({@code runway.cluster.enabled=true}), the cluster layer stays absent in <b>WIZARD</b> mode (no
 * {@code app-master-id} pinned). Clustering is meaningless before first-run setup, so the
 * {@code @Conditional(ActiveModeCondition.class)} gate keeps Hazelcast and the session repository out until the
 * platform is ACTIVE. {@link ClusterEnabledActiveTest} is the complementary proof that they DO appear once
 * ACTIVE.
 */
@SpringBootTest(properties = {
        "runway.cluster.enabled=true",
        "runway.cluster.embedded.discovery=tcp-ip"
        // deliberately NO runway.security.app-master-id -> WIZARD mode
})
class ClusterActiveModeGatingTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void clusterEnabledButInWizardModeStillWiresNothing() {
        assertEquals(0, context.getBeanNamesForType(ClusterConfig.class).length,
                "ClusterConfig must stay out in WIZARD mode even with enabled=true");
        assertEquals(0, context.getBeanNamesForType(HazelcastInstance.class).length,
                "no HazelcastInstance before the platform is ACTIVE");
        assertEquals(0, context.getBeanNamesForType(SessionRepository.class).length,
                "no SessionRepository before the platform is ACTIVE");
        assertTrue(!context.containsBean("springSessionRepositoryFilter"),
                "no Spring Session filter before the platform is ACTIVE");
    }
}
