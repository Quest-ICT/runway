package mx.quest.runway.cluster;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.topic.ITopic;
import com.hazelcast.topic.Message;
import com.hazelcast.topic.MessageListener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import mx.quest.runway.content.ContentChangedEvent;
import mx.quest.runway.event.ClusterReplay;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.page.PageChangedEvent;
import mx.quest.runway.plugin.SitePluginConfigChangedEvent;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.wizard.ActiveModeCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The bidirectional bridge that turns the kernel's <b>local</b> cache-invalidation events into <b>cluster-wide</b>
 * ones. Each node runs one relay:
 *
 * <ul>
 *   <li>on a local {@link SiteChangedEvent} / {@link SitePluginConfigChangedEvent} / {@link PageChangedEvent} /
 *       {@link ContentChangedEvent}, it publishes a tagged {@link ClusterEventEnvelope} to the Hazelcast events
 *       topic;</li>
 *   <li>on a topic message from <b>another</b> node, it re-fires the equivalent local event through the Spring
 *       {@link ApplicationEventPublisher}, so every existing local {@code @EventListener} (per-site cache
 *       evictors, host-index, message source, the page-cache listener, …) evicts on this node too.</li>
 * </ul>
 *
 * <h2>Why a relay rather than a cluster-aware cache</h2>
 * The kernel's caches are deliberately plain per-JVM Caffeine/{@code StoreCache}s driven by Spring events; a
 * single-node deployment carries zero clustering machinery. Rather than replace those with distributed maps
 * (which would tax the common single-node case), clustering is added as an <em>edge</em>: this relay rebroadcasts
 * the same events across nodes and replays them locally. The eviction logic lives in exactly one place (the
 * local listeners) and is exercised identically whether the trigger was a local write or a peer's broadcast.
 *
 * <h2>Echo-loop guard (mandatory)</h2>
 * Hazelcast delivers a published message back to the publisher too. Two defences stop an infinite rebroadcast:
 * <ol>
 *   <li>every envelope carries {@link ClusterEventEnvelope#getOriginNodeId() originNodeId}; a message whose
 *       origin is this node is dropped on arrival (the primary guard);</li>
 *   <li>when a <em>received</em> message is replayed locally, {@link ClusterReplay} marks the thread as replaying
 *       a peer's event, so this relay's own {@code @EventListener}s see the replayed local event and do <b>not</b>
 *       rebroadcast it (belt-and-braces, and it also covers the theoretical case of two nodes sharing an id). The
 *       same mark is what plugin listeners receive as {@code local == false}.</li>
 * </ol>
 *
 * <h2>Gating</h2>
 * The bean exists only when clustering is on and the platform is ACTIVE — the same two gates as
 * {@link ClusterConfig} (it depends on that config's {@link HazelcastInstance}). With clustering off (the
 * default) there is no relay: {@link PageChangedEvent} and the others still drive purely local eviction (correct
 * single-node behaviour) and nothing is broadcast.
 */
@Component
@ConditionalOnProperty(prefix = "runway.cluster", name = "enabled", havingValue = "true")
@Conditional(ActiveModeCondition.class)
public class ClusterEventRelay {

    private static final Logger log = LoggerFactory.getLogger(ClusterEventRelay.class);

    private final HazelcastInstance hz;
    private final ApplicationEventPublisher events;
    private final String eventsTopicName;
    private final String localNodeId;

    private ITopic<ClusterEventEnvelope> topic;
    private UUID subscriptionId;

    public ClusterEventRelay(HazelcastInstance hz, ApplicationEventPublisher events,
                             RunwayClusterProperties props) {
        this.hz = hz;
        this.events = events;
        this.eventsTopicName = props.getEventsTopicName();
        this.localNodeId = hz.getLocalEndpoint().getUuid().toString();
    }

    @PostConstruct
    void subscribe() {
        this.topic = hz.getTopic(eventsTopicName);
        this.subscriptionId = topic.addMessageListener(new TopicListener());
        log.info("Cluster event relay active: node={}, topic={}", localNodeId, eventsTopicName);
    }

    @PreDestroy
    void unsubscribe() {
        if (topic != null && subscriptionId != null) {
            topic.removeMessageListener(subscriptionId);
        }
    }

    // --- local event → topic (skip when the event is itself a replay of a received message) --------------

    @EventListener
    @Order(KernelEventOrder.RELAY)
    public void onSiteChanged(SiteChangedEvent e) {
        if (ClusterReplay.active()) {
            return;
        }
        topic.publish(ClusterEventEnvelope.siteChanged(e.siteId(), e.kind().name(), e.templateId(), e.fromBackup(),
                localNodeId));
    }

    @EventListener
    @Order(KernelEventOrder.RELAY)
    public void onSitePluginConfigChanged(SitePluginConfigChangedEvent e) {
        if (ClusterReplay.active()) {
            return;
        }
        topic.publish(ClusterEventEnvelope.sitePluginConfigChanged(e.siteId(), e.pluginId(), localNodeId));
    }

    @EventListener
    @Order(KernelEventOrder.RELAY)
    public void onPageChanged(PageChangedEvent e) {
        if (ClusterReplay.active()) {
            return;
        }
        topic.publish(ClusterEventEnvelope.pageChanged(e.siteId(), e.slug(), localNodeId));
    }

    @EventListener
    @Order(KernelEventOrder.RELAY)
    public void onContentChanged(ContentChangedEvent e) {
        if (ClusterReplay.active()) {
            return;
        }
        topic.publish(ClusterEventEnvelope.contentChanged(e.siteId(), e.sourceKey(), e.id(), localNodeId));
    }

    // --- topic message → local event ---------------------------------------------------------------------

    private final class TopicListener implements MessageListener<ClusterEventEnvelope> {
        @Override
        public void onMessage(Message<ClusterEventEnvelope> message) {
            handleClusterMessage(message.getMessageObject());
        }
    }

    /**
     * Replays a received envelope as its local event so this node's listeners evict — unless it is our own echo.
     * Package-visible so a Mockito unit test can drive the receive path without a live Hazelcast grid.
     *
     * <p>The replay runs under {@link ClusterReplay} — set for the duration of the local re-publish and cleared in a
     * {@code finally} — so this relay's own {@code @EventListener}s (above) do not rebroadcast the replayed event, and
     * every plugin {@code SiteEventListener} receives it with {@code local == false}. A malformed envelope (e.g. an
     * unknown {@code SiteChangedEvent.Kind} name from a newer node) is logged and dropped rather than allowed to break
     * the topic listener. An envelope from a node that predates {@code fromBackup} carries {@code null} there, read
     * as {@code false}.
     */
    void handleClusterMessage(ClusterEventEnvelope env) {
        if (localNodeId.equals(env.getOriginNodeId())) {
            return; // self-echo — the local listeners already evicted when the event was first published here
        }
        try {
            ClusterReplay.run(() -> replay(env));
        } catch (RuntimeException ex) {
            log.warn("Dropping malformed cluster event {} from node {}: {}", env.getKind(),
                    env.getOriginNodeId(), ex.toString());
        }
    }

    private void replay(ClusterEventEnvelope env) {
        switch (env.getKind()) {
            case SITE_CHANGED -> events.publishEvent(new SiteChangedEvent(env.getSiteId(),
                    SiteChangedEvent.Kind.valueOf(env.getSiteChangedKind()), env.getTemplateId(),
                    Boolean.TRUE.equals(env.getFromBackup())));
            case SITE_PLUGIN_CONFIG_CHANGED ->
                    events.publishEvent(new SitePluginConfigChangedEvent(env.getSiteId(), env.getPluginId()));
            case PAGE_CHANGED -> events.publishEvent(new PageChangedEvent(env.getSiteId(), env.getSlug()));
            case CONTENT_CHANGED -> events.publishEvent(new ContentChangedEvent(env.getSiteId(),
                    env.getSourceKey(), env.getContentId()));
        }
    }

    /** Package-visible for unit tests. */
    String localNodeId() {
        return localNodeId;
    }
}
