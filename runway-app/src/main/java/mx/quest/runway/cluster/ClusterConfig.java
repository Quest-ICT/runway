package mx.quest.runway.cluster;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.time.Duration;
import mx.quest.runway.wizard.ActiveModeCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;

/**
 * Wires the Hazelcast layer and a Spring Session repository — but <b>only</b> when clustering is switched on and
 * the platform is running normally. Two gates, both class-level, so a single-node deployment carries none of
 * this:
 *
 * <ul>
 *   <li>{@code @ConditionalOnProperty(runway.cluster.enabled=true)} — the master switch. With it off (the
 *       default) this whole configuration is absent: no {@link HazelcastInstance}, no {@link SessionRepository},
 *       and — because {@link EnableSpringHttpSession} never applies — no {@code springSessionRepositoryFilter}.
 *       The container's own {@code HttpSession} is used exactly as today; there is provably zero clustering
 *       overhead when off (guarded by a test).</li>
 *   <li>{@link ActiveModeCondition} — clustering is meaningless during first-run setup, so this never activates
 *       in WIZARD mode. In practice {@code runway.cluster.enabled=true} only ever lives in the external,
 *       post-wizard {@code application.yml}; the mode gate makes that structural rather than a convention.</li>
 * </ul>
 *
 * <p>Hazelcast classes sit on the classpath unconditionally (a build-simplicity choice) — the
 * gating is done by the conditions above, not by keeping the jar off the path.
 *
 * <h2>Sessions</h2>
 * Backed by a Hazelcast {@code IMap<String, MapSession>} through the hand-rolled
 * {@link HazelcastSessionRepository} (the library {@code MapSessionRepository} is intentionally not used — see
 * that class). The map's {@code max-idle-seconds} is set from {@link RunwayClusterProperties#getSessionTtl()}
 * so Hazelcast evicts idle sessions itself.
 *
 * <p>The per-realm/per-site isolation is untouched: it lives as {@code HttpSession} attributes written by the
 * security layer's {@code HttpSession*} repositories, which a Hazelcast-backed session carries across the
 * cluster with no change to the security configuration.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "runway.cluster", name = "enabled", havingValue = "true")
@Conditional(ActiveModeCondition.class)
@EnableConfigurationProperties(RunwayClusterProperties.class)
@EnableSpringHttpSession
public class ClusterConfig {

    private static final Logger log = LoggerFactory.getLogger(ClusterConfig.class);

    /**
     * The Hazelcast instance for this node — an embedded member (default) or a client of an external cluster.
     * {@code destroyMethod="shutdown"} leaves the grid cleanly on context close.
     */
    @Bean(destroyMethod = "shutdown")
    public HazelcastInstance hazelcastInstance(RunwayClusterProperties props) {
        return props.getTopology() == RunwayClusterProperties.Topology.CLIENT_SERVER
                ? buildClient(props)
                : buildEmbedded(props);
    }

    private HazelcastInstance buildEmbedded(RunwayClusterProperties props) {
        RunwayClusterProperties.Embedded embedded = props.getEmbedded();
        Config config = new Config().setClusterName(props.getClusterName());

        NetworkConfig network = config.getNetworkConfig()
                .setPort(embedded.getPort())
                .setPortAutoIncrement(embedded.isPortAutoIncrement());
        JoinConfig join = network.getJoin();
        // Start from a clean slate — enable exactly the one requested mechanism.
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(false);
        switch (embedded.getDiscovery()) {
            case "tcp-ip" -> {
                join.getTcpIpConfig().setEnabled(true);
                embedded.getMembers().forEach(join.getTcpIpConfig()::addMember);
            }
            case "multicast" -> join.getMulticastConfig().setEnabled(true);
            default -> throw new IllegalArgumentException(
                    "Unknown runway.cluster.embedded.discovery '" + embedded.getDiscovery()
                            + "' (expected 'tcp-ip' or 'multicast')");
        }

        config.addMapConfig(sessionsMapConfig(props));
        log.info("Starting embedded Hazelcast member: cluster='{}', discovery={}, port={} (session TTL {})",
                props.getClusterName(), embedded.getDiscovery(), embedded.getPort(), props.getSessionTtl());
        return Hazelcast.newHazelcastInstance(config);
    }

    private HazelcastInstance buildClient(RunwayClusterProperties props) {
        RunwayClusterProperties.Client client = props.getClient();
        if (client.getAddresses().isEmpty()) {
            throw new IllegalStateException(
                    "runway.cluster.topology=CLIENT_SERVER requires runway.cluster.client.addresses to be non-empty");
        }
        ClientConfig config = new ClientConfig().setClusterName(props.getClusterName());
        config.getNetworkConfig().setSmartRouting(client.isSmartRouting());
        client.getAddresses().forEach(config.getNetworkConfig()::addAddress);
        log.info("Starting Hazelcast client: cluster='{}', addresses={} (session TTL {})",
                props.getClusterName(), client.getAddresses(), props.getSessionTtl());
        return HazelcastClient.newHazelcastClient(config);
    }

    /**
     * The sessions {@code IMap} config: {@code max-idle-seconds} = the configured session TTL (floored at 60s),
     * so Hazelcast evicts an idle session and the repository never sweeps. On a client this config is only a
     * hint unless the same map is declared server-side, so it is also applied to the embedded member above.
     */
    private MapConfig sessionsMapConfig(RunwayClusterProperties props) {
        long ttlSeconds = Math.max(60, props.getSessionTtl().toSeconds());
        return new MapConfig(props.getSessionsMapName())
                .setMaxIdleSeconds((int) Math.min(ttlSeconds, Integer.MAX_VALUE));
    }

    /**
     * The hand-rolled session repository over the distributed sessions map. {@link EnableSpringHttpSession}
     * wires the {@code springSessionRepositoryFilter} around it, at Spring Session's default order
     * ({@code Integer.MIN_VALUE + 50}) — inner of the kernel protection floor and outer of the security chain
     * (asserted by {@code FilterChainOrderTest}).
     */
    @Bean
    public SessionRepository<MapSession> sessionRepository(HazelcastInstance hazelcast,
                                                           RunwayClusterProperties props) {
        IMap<String, MapSession> sessions = hazelcast.getMap(props.getSessionsMapName());
        return new HazelcastSessionRepository(sessions, props.getSessionTtl());
    }
}
