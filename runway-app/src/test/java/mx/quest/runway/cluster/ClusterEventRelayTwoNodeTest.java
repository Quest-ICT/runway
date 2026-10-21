package mx.quest.runway.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import mx.quest.runway.page.PageChangedEvent;
import mx.quest.runway.site.SiteChangedEvent;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The end-to-end proof of the cluster cache relay: a cache-invalidation event raised on one node is delivered, over a
 * <b>real two-member embedded Hazelcast grid</b>, as the equivalent local event on the <b>other</b> node — so a
 * page edit or a site change on node A evicts the matching cache on node B. This is the cross-node counterpart
 * to {@code HazelcastSessionRepositoryTest}, and it exercises the whole relay path (envelope serialization →
 * ITopic → subscriber → local re-publish) that the Mockito {@link ClusterEventRelayTest} can only stub.
 *
 * <p>Each node gets its own {@link ClusterEventRelay} sharing the one topic. A recording
 * {@link ApplicationEventPublisher} on each node captures what the relay re-fires locally, standing in for the
 * real kernel listeners (which is where the actual eviction would run). The assertions:
 *
 * <ul>
 *   <li>a {@link SiteChangedEvent} raised on A appears as a reconstructed local {@link SiteChangedEvent} on B
 *       (same site + kind), and does NOT re-appear on A (the origin drops its own echo);</li>
 *   <li>a {@link PageChangedEvent} raised on A appears as a local {@link PageChangedEvent} on B — the fine-grained
 *       page invalidation that was once purely local.</li>
 * </ul>
 *
 * <p>Two in-JVM members over loopback tcp-ip on an isolated (nanotime-suffixed) cluster name; no network beyond
 * loopback, no external broker — safe offline.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClusterEventRelayTwoNodeTest {

    private static final int PORT_A = 15901;
    private static final int PORT_B = 15902;
    private static final String TOPIC = "runway.events";

    private HazelcastInstance nodeA;
    private HazelcastInstance nodeB;
    private RecordingPublisher publisherA;
    private RecordingPublisher publisherB;
    private ClusterEventRelay relayA;
    private ClusterEventRelay relayB;

    @BeforeAll
    void startTwoMemberClusterWithRelays() {
        String cluster = "rw-relay-test-" + System.nanoTime();
        nodeA = member(cluster, PORT_A);
        nodeB = member(cluster, PORT_B);
        Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .until(() -> nodeA.getCluster().getMembers().size() == 2
                        && nodeB.getCluster().getMembers().size() == 2);

        RunwayClusterProperties props = new RunwayClusterProperties(); // default topic "runway.events"
        publisherA = new RecordingPublisher();
        publisherB = new RecordingPublisher();
        relayA = new ClusterEventRelay(nodeA, publisherA, props);
        relayB = new ClusterEventRelay(nodeB, publisherB, props);
        relayA.subscribe();
        relayB.subscribe();
    }

    private static HazelcastInstance member(String clusterName, int port) {
        Config config = new Config().setClusterName(clusterName);
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
        if (relayA != null) {
            relayA.unsubscribe();
        }
        if (relayB != null) {
            relayB.unsubscribe();
        }
        if (nodeA != null) {
            nodeA.shutdown();
        }
        if (nodeB != null) {
            nodeB.shutdown();
        }
    }

    @Test
    void aSiteChangeOnNodeAIsReDeliveredAsALocalEventOnNodeBOnly() {
        publisherA.events.clear();
        publisherB.events.clear();

        // Node A raises a local site change — its relay broadcasts it to the topic.
        relayA.onSiteChanged(new SiteChangedEvent("demo", SiteChangedEvent.Kind.RESTORED, null));

        // Node B's relay receives it and re-fires an equivalent local SiteChangedEvent (which is where B's
        // caches would evict).
        Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() -> !publisherB.of(SiteChangedEvent.class).isEmpty());
        List<SiteChangedEvent> onB = publisherB.of(SiteChangedEvent.class);
        assertEquals(1, onB.size(), "node B re-fires exactly one local SiteChangedEvent");
        assertEquals("demo", onB.get(0).siteId());
        assertEquals(SiteChangedEvent.Kind.RESTORED, onB.get(0).kind());

        // Node A must NOT re-fire its own event locally (it drops the self-echo Hazelcast delivers back).
        assertTrue(publisherA.of(SiteChangedEvent.class).isEmpty(),
                "the origin node drops its own echo — no local re-fire on A");
    }

    @Test
    void aPageChangeOnNodeAReachesNodeBAsALocalPageEvent() {
        publisherA.events.clear();
        publisherB.events.clear();

        relayA.onPageChanged(new PageChangedEvent("demo", "about"));

        Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() -> !publisherB.of(PageChangedEvent.class).isEmpty());
        List<PageChangedEvent> onB = publisherB.of(PageChangedEvent.class);
        assertEquals(1, onB.size(), "node B re-fires exactly one local PageChangedEvent");
        assertEquals("demo", onB.get(0).siteId());
        assertEquals("about", onB.get(0).slug());
        assertTrue(publisherA.of(PageChangedEvent.class).isEmpty(), "no self-echo re-fire on the origin node");
    }

    /**
     * A peer's replay evicts its plugin-settings cache, carries {@code fromBackup}, and reaches the
     * peer's SPI listeners as {@code local == false} — while the listener already reads the new settings. Node B's
     * publisher forwards each replayed event to the real kernel listeners it stands in for, in their order.
     */
    @Test
    void aPeerReplayEvictsSettingsAndReachesListenersAsNotLocal() throws Exception {
        java.nio.file.Path external = java.nio.file.Files.createTempDirectory("runway-k3-peer");
        mx.quest.runway.storage.StorageProperties storage = new mx.quest.runway.storage.StorageProperties();
        storage.setExternal(external);
        mx.quest.runway.storage.StorageLayout layout = mx.quest.runway.storage.StorageLayout.from(storage);
        java.nio.file.Path file = java.nio.file.Files.createDirectories(layout.siteDir("demo").resolve("plugins"))
                .resolve("search.yml");
        java.nio.file.Files.writeString(file, "mode: old\n");
        mx.quest.runway.plugin.SitePluginConfigService configB = new mx.quest.runway.plugin.SitePluginConfigService(
                layout, org.mockito.Mockito.mock(mx.quest.runway.crypto.SecretCipher.class), event -> { });
        assertEquals("old", configB.config("demo", "search").get("mode"), "node B has the value cached");

        List<Object> seenOnB = new CopyOnWriteArrayList<>();
        mx.quest.runway.spi.service.SiteEventListener listener = new mx.quest.runway.spi.service.SiteEventListener() {
            @Override
            public void onSettingsChanged(mx.quest.runway.spi.model.SettingsChange change) {
                seenOnB.add(change);
                seenOnB.add(configB.config("demo", "search").get("mode")); // what a plugin re-reads here
            }

            @Override
            public void onSiteLifecycle(mx.quest.runway.spi.model.SiteLifecycleEvent event) {
                seenOnB.add(event);
            }
        };
        mx.quest.runway.event.SpiEventBridge bridgeB = new mx.quest.runway.event.SpiEventBridge(() -> List.of(listener),
                () -> new mx.quest.runway.content.ContentCatalog(new mx.quest.runway.content.ContentSourceRegistry(
                        List.of())), id -> java.util.Optional.empty(),
                mx.quest.runway.plugin.ContributionGuard.standalone(),
                contribution -> java.util.Optional.of("search"), id -> 0, () -> null);
        bridgeB.collect();
        publisherB.forward = event -> {
            if (event instanceof mx.quest.runway.plugin.SitePluginConfigChangedEvent e) {
                configB.onConfigChanged(e);           // KernelEventOrder.STORES
                bridgeB.onSitePluginConfigChanged(e); // KernelEventOrder.PLUGIN_DISPATCH
            } else if (event instanceof SiteChangedEvent e) {
                bridgeB.onSiteChanged(e);
            }
        };
        try {
            // Node A saved (shared storage) and announced the change.
            java.nio.file.Files.writeString(file, "mode: new\n");
            relayA.onSitePluginConfigChanged(new mx.quest.runway.plugin.SitePluginConfigChangedEvent("demo",
                    "search"));
            relayA.onSiteChanged(SiteChangedEvent.createdFromBackup("demo"));
            Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() -> seenOnB.size() >= 3);

            mx.quest.runway.spi.model.SettingsChange change = (mx.quest.runway.spi.model.SettingsChange) seenOnB.get(0);
            assertEquals("search", change.pluginId());
            assertFalse(change.local(), "a replayed change is not local: side effects stay on the origin node");
            assertEquals("new", seenOnB.get(1), "the peer's settings cache was evicted before the listener ran");
            mx.quest.runway.spi.model.SiteLifecycleEvent created =
                    (mx.quest.runway.spi.model.SiteLifecycleEvent) seenOnB.get(2);
            assertTrue(created.fromBackup(), "fromBackup crosses the cluster (review m14a)");
            assertFalse(created.local());
        } finally {
            publisherB.forward = null;
        }
    }

    /** Captures every object the relay re-publishes locally, so the test can inspect the cross-node deliveries. */
    private static final class RecordingPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new CopyOnWriteArrayList<>();
        /** Optionally hands each replayed event on (to the kernel listeners a test stands in for). */
        volatile java.util.function.Consumer<Object> forward;

        @Override
        public void publishEvent(Object event) {
            events.add(event);
            java.util.function.Consumer<Object> next = forward;
            if (next != null) {
                next.accept(event);
            }
        }

        @Override
        public void publishEvent(ApplicationEvent event) {
            publishEvent((Object) event);
        }

        @SuppressWarnings("unchecked")
        <T> List<T> of(Class<T> type) {
            return events.stream().filter(type::isInstance).map(e -> (T) e).toList();
        }
    }
}
