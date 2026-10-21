package mx.quest.runway.cluster;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Bound {@code runway.cluster.*} configuration — <b>opt-in horizontal clustering</b>. Default OFF, which is
 * today's single-node footprint exactly: with {@link #enabled} {@code false} no Hazelcast member starts, no
 * session repository is wired, and the container's own {@code HttpSession} is used — <b>zero overhead when
 * off</b> (see {@link ClusterConfig}, gated at the class level).
 *
 * <p>When enabled, sessions move into a Hazelcast {@code IMap<String, MapSession>} shared by every node, so a
 * request may be served by any node behind the load balancer and still see the same authenticated principal.
 * The per-realm / per-site isolation the kernel already implements needs <b>no</b> change: it lives as
 * {@code HttpSession} attributes written by Spring Security's {@code HttpSession*} repositories under custom
 * keys ({@code AppSecurityConfig} / {@code TenantSecurityConfig} / the {@code SiteScoped*} repositories), so a
 * Hazelcast-backed session carries those attributes across the cluster for free.
 *
 * <h2>Topology</h2>
 * <ul>
 *   <li>{@link Topology#EMBEDDED} (default) — each Runway JVM is itself a Hazelcast member, joining via
 *       {@link Embedded#getDiscovery()} (tcp-ip with an explicit member list is the recommended production
 *       shape). No external process to run.</li>
 *   <li>{@link Topology#CLIENT_SERVER} — Runway connects as a Hazelcast <em>client</em> to a separately-run
 *       Hazelcast cluster at {@link Client#getAddresses()}. Chosen when the data grid is operated apart from
 *       the app tier.</li>
 * </ul>
 *
 * <h2>Session TTL</h2>
 * {@link #getSessionTtl()} (default 30 minutes — the same as today's container default, so enabling clustering
 * changes no timeout behaviour) feeds the sessions {@code IMap}'s {@code max-idle-seconds}, so Hazelcast evicts
 * an idle session itself and the repository never has to sweep. It should match
 * {@code server.servlet.session.timeout} in {@code application.yml}, which is the container's own view of the
 * same value.
 */
@ConfigurationProperties("runway.cluster")
public class RunwayClusterProperties {

    /** Discovery / connection topology for the Hazelcast layer. */
    public enum Topology {
        /** Each Runway JVM is a Hazelcast member (default — no external grid to run). */
        EMBEDDED,
        /** Runway is a Hazelcast client of a separately-operated cluster. */
        CLIENT_SERVER
    }

    /**
     * Master switch. Default {@code false} — the single-node deployment: no Hazelcast, no Spring Session, the
     * container's {@code HttpSession} is used exactly as today. Only ever set {@code true} in the external,
     * post-wizard {@code application.yml} (clustering is meaningless in first-run WIZARD mode).
     */
    private boolean enabled = false;

    /** The Hazelcast cluster name — members/clients only join peers sharing it. Default {@code "runway"}. */
    private String clusterName = "runway";

    /** The topology: {@link Topology#EMBEDDED} (default) or {@link Topology#CLIENT_SERVER}. */
    private Topology topology = Topology.EMBEDDED;

    @NestedConfigurationProperty
    private final Embedded embedded = new Embedded();

    @NestedConfigurationProperty
    private final Client client = new Client();

    /** The name of the distributed map holding sessions. Default {@code "runway.sessions"}. */
    private String sessionsMapName = "runway.sessions";

    /**
     * The name of the distributed topic used to relay cross-node cache-invalidation events. The
     * {@code cluster.ClusterEventRelay} subscribes to it and bridges local Spring {@code ApplicationEvent}s
     * ({@code SiteChangedEvent}, {@code SitePluginConfigChangedEvent}, {@code PageChangedEvent}) across the
     * cluster, so an edit on one node evicts the matching cache on every node. Default {@code "runway.events"}.
     */
    private String eventsTopicName = "runway.events";

    /**
     * The session time-to-live. Sourced onto the sessions {@code IMap}'s {@code max-idle-seconds} so an idle
     * session is evicted by Hazelcast. Default 30 minutes — matches today's container default, so enabling
     * clustering does not change session lifetime. Keep it in step with {@code server.servlet.session.timeout}.
     */
    private Duration sessionTtl = Duration.ofMinutes(30);

    /** Embedded-topology settings — how this JVM's Hazelcast member discovers its peers. */
    public static class Embedded {

        /**
         * The member discovery mechanism. {@code "tcp-ip"} (default — an explicit {@link #members} list, the
         * recommended production shape) or {@code "multicast"} (convenient for a same-subnet dev cluster).
         */
        private String discovery = "tcp-ip";

        /** Explicit peer addresses for {@code tcp-ip} discovery, e.g. {@code ["10.0.0.1:5701","10.0.0.2:5701"]}. */
        private List<String> members = new ArrayList<>();

        /** The port this member binds. Default 5701 (Hazelcast's default). */
        private int port = 5701;

        /** Whether to try the next port when {@link #port} is taken (lets several members share one host). */
        private boolean portAutoIncrement = true;

        public String getDiscovery() {
            return discovery;
        }

        public void setDiscovery(String discovery) {
            this.discovery = discovery;
        }

        public List<String> getMembers() {
            return members;
        }

        public void setMembers(List<String> members) {
            this.members = members;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isPortAutoIncrement() {
            return portAutoIncrement;
        }

        public void setPortAutoIncrement(boolean portAutoIncrement) {
            this.portAutoIncrement = portAutoIncrement;
        }
    }

    /** Client-server-topology settings — how this JVM connects, as a client, to an external Hazelcast cluster. */
    public static class Client {

        /** The cluster member addresses to connect to, e.g. {@code ["hz1.internal:5701","hz2.internal:5701"]}. */
        private List<String> addresses = new ArrayList<>();

        /**
         * Whether the client routes each operation straight to the member owning the partition (smart routing,
         * the default and best throughput). Turn off only for a unisocket client behind a restrictive firewall.
         */
        private boolean smartRouting = true;

        public List<String> getAddresses() {
            return addresses;
        }

        public void setAddresses(List<String> addresses) {
            this.addresses = addresses;
        }

        public boolean isSmartRouting() {
            return smartRouting;
        }

        public void setSmartRouting(boolean smartRouting) {
            this.smartRouting = smartRouting;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public Topology getTopology() {
        return topology;
    }

    public void setTopology(Topology topology) {
        this.topology = topology;
    }

    public Embedded getEmbedded() {
        return embedded;
    }

    public Client getClient() {
        return client;
    }

    public String getSessionsMapName() {
        return sessionsMapName;
    }

    public void setSessionsMapName(String sessionsMapName) {
        this.sessionsMapName = sessionsMapName;
    }

    public String getEventsTopicName() {
        return eventsTopicName;
    }

    public void setEventsTopicName(String eventsTopicName) {
        this.eventsTopicName = eventsTopicName;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }
}
