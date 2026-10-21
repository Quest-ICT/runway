package mx.quest.runway.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hazelcast.cluster.Endpoint;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.topic.ITopic;
import java.util.UUID;
import mx.quest.runway.content.ContentChangedEvent;
import mx.quest.runway.page.PageChangedEvent;
import mx.quest.runway.plugin.SitePluginConfigChangedEvent;
import mx.quest.runway.site.SiteChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The receive/publish paths of {@link ClusterEventRelay}, driven with a mocked Hazelcast topic and
 * {@link ApplicationEventPublisher} — no live grid. Covers Runway's four
 * relayed event types ({@link SiteChangedEvent}, {@link SitePluginConfigChangedEvent}, {@link PageChangedEvent},
 * {@link ContentChangedEvent}): a local event is published to the topic tagged with the local node id; a message
 * from another node is replayed as the equivalent local event; a self-origin message is dropped; and a replayed
 * (received) event is NOT rebroadcast back to the topic (the echo-loop guard).
 */
class ClusterEventRelayTest {

    private final UUID LOCAL = UUID.randomUUID();
    private final UUID OTHER = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private final ITopic<ClusterEventEnvelope> topic = (ITopic<ClusterEventEnvelope>) mock(ITopic.class);
    private HazelcastInstance hz;
    private ApplicationEventPublisher events;
    private ClusterEventRelay relay;

    @BeforeEach
    void setup() {
        hz = mock(HazelcastInstance.class);
        Endpoint endpoint = mock(Endpoint.class);
        when(endpoint.getUuid()).thenReturn(LOCAL);
        when(hz.getLocalEndpoint()).thenReturn(endpoint);
        RunwayClusterProperties props = new RunwayClusterProperties(); // default topic name "runway.events"
        doReturn(topic).when(hz).getTopic(props.getEventsTopicName());
        when(topic.addMessageListener(any())).thenReturn(UUID.randomUUID());
        events = mock(ApplicationEventPublisher.class);
        relay = new ClusterEventRelay(hz, events, props);
        relay.subscribe();
    }

    // --- publish-on-local-event ---------------------------------------------------------------------------

    @Test
    void localPageChangeIsPublishedToTopicWithOriginId() {
        relay.onPageChanged(new PageChangedEvent("demo", "about"));
        ClusterEventEnvelope env = capturePublished();
        assertThat(env.getKind()).isEqualTo(ClusterEventEnvelope.Kind.PAGE_CHANGED);
        assertThat(env.getSiteId()).isEqualTo("demo");
        assertThat(env.getSlug()).isEqualTo("about");
        assertThat(env.getOriginNodeId()).isEqualTo(LOCAL.toString());
    }

    @Test
    void localSiteChangeCarriesKindNameAndTemplateId() {
        relay.onSiteChanged(new SiteChangedEvent("demo", SiteChangedEvent.Kind.CREATED, "blog"));
        ClusterEventEnvelope env = capturePublished();
        assertThat(env.getKind()).isEqualTo(ClusterEventEnvelope.Kind.SITE_CHANGED);
        assertThat(env.getSiteId()).isEqualTo("demo");
        assertThat(env.getSiteChangedKind()).isEqualTo("CREATED");
        assertThat(env.getTemplateId()).isEqualTo("blog");
        assertThat(env.getOriginNodeId()).isEqualTo(LOCAL.toString());
    }

    @Test
    void localSitePluginConfigChangeCarriesPluginId() {
        relay.onSitePluginConfigChanged(new SitePluginConfigChangedEvent("demo", "search"));
        ClusterEventEnvelope env = capturePublished();
        assertThat(env.getKind()).isEqualTo(ClusterEventEnvelope.Kind.SITE_PLUGIN_CONFIG_CHANGED);
        assertThat(env.getSiteId()).isEqualTo("demo");
        assertThat(env.getPluginId()).isEqualTo("search");
        assertThat(env.getSlug()).isNull();
    }

    @Test
    void localContentChangeCarriesSourceKeyAndContentId() {
        relay.onContentChanged(new ContentChangedEvent("demo", "blog", "my-post"));
        ClusterEventEnvelope env = capturePublished();
        assertThat(env.getKind()).isEqualTo(ClusterEventEnvelope.Kind.CONTENT_CHANGED);
        assertThat(env.getSiteId()).isEqualTo("demo");
        assertThat(env.getSourceKey()).isEqualTo("blog");
        assertThat(env.getContentId()).isEqualTo("my-post");
        assertThat(env.getOriginNodeId()).isEqualTo(LOCAL.toString());
    }

    @Test
    void localWholeCorpusContentChangeCarriesNullContentId() {
        relay.onContentChanged(new ContentChangedEvent("demo", "blog", null)); // null id = whole corpus
        ClusterEventEnvelope env = capturePublished();
        assertThat(env.getKind()).isEqualTo(ClusterEventEnvelope.Kind.CONTENT_CHANGED);
        assertThat(env.getContentId()).isNull();
    }

    // --- re-fire-on-remote-message ------------------------------------------------------------------------

    @Test
    void topicMessageFromOtherNodeFiresLocalPageEvent() {
        relay.handleClusterMessage(ClusterEventEnvelope.pageChanged("demo", "about", OTHER.toString()));
        ArgumentCaptor<PageChangedEvent> cap = ArgumentCaptor.forClass(PageChangedEvent.class);
        verify(events).publishEvent(cap.capture());
        assertThat(cap.getValue().siteId()).isEqualTo("demo");
        assertThat(cap.getValue().slug()).isEqualTo("about");
    }

    @Test
    void topicMessageFromOtherNodeReconstructsSiteChangedWithKind() {
        relay.handleClusterMessage(
                ClusterEventEnvelope.siteChanged("demo", "RESTORED", null, OTHER.toString()));
        ArgumentCaptor<SiteChangedEvent> cap = ArgumentCaptor.forClass(SiteChangedEvent.class);
        verify(events).publishEvent(cap.capture());
        assertThat(cap.getValue().siteId()).isEqualTo("demo");
        assertThat(cap.getValue().kind()).isEqualTo(SiteChangedEvent.Kind.RESTORED);
    }

    @Test
    void topicMessageFromOtherNodeReconstructsSitePluginConfigChanged() {
        relay.handleClusterMessage(
                ClusterEventEnvelope.sitePluginConfigChanged("demo", "search", OTHER.toString()));
        ArgumentCaptor<SitePluginConfigChangedEvent> cap =
                ArgumentCaptor.forClass(SitePluginConfigChangedEvent.class);
        verify(events).publishEvent(cap.capture());
        assertThat(cap.getValue().siteId()).isEqualTo("demo");
        assertThat(cap.getValue().pluginId()).isEqualTo("search");
    }

    @Test
    void topicMessageFromOtherNodeReconstructsContentChanged() {
        relay.handleClusterMessage(
                ClusterEventEnvelope.contentChanged("demo", "blog", "my-post", OTHER.toString()));
        ArgumentCaptor<ContentChangedEvent> cap = ArgumentCaptor.forClass(ContentChangedEvent.class);
        verify(events).publishEvent(cap.capture());
        assertThat(cap.getValue().siteId()).isEqualTo("demo");
        assertThat(cap.getValue().sourceKey()).isEqualTo("blog");
        assertThat(cap.getValue().id()).isEqualTo("my-post");
    }

    // --- echo guard ---------------------------------------------------------------------------------------

    @Test
    void topicMessageFromSelfIsDropped() {
        relay.handleClusterMessage(ClusterEventEnvelope.pageChanged("demo", "x", LOCAL.toString()));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aReceivedEventIsNotRebroadcastBackToTheTopic() {
        // Wire the publisher to actually call the relay's own listeners back (as Spring would), so the
        // thread-local guard is what must stop the re-publish — not merely the absence of a real context.
        doAnswer(inv -> {
            Object e = inv.getArgument(0);
            if (e instanceof PageChangedEvent pce) {
                relay.onPageChanged(pce);
            } else if (e instanceof SiteChangedEvent sce) {
                relay.onSiteChanged(sce);
            } else if (e instanceof SitePluginConfigChangedEvent spe) {
                relay.onSitePluginConfigChanged(spe);
            } else if (e instanceof ContentChangedEvent cce) {
                relay.onContentChanged(cce);
            }
            return null;
        }).when(events).publishEvent(any(Object.class));

        relay.handleClusterMessage(ClusterEventEnvelope.pageChanged("demo", "about", OTHER.toString()));

        // The relay replayed the event locally exactly once and published NOTHING back to the topic
        // (which would loop forever across the two nodes).
        verify(events).publishEvent(any(PageChangedEvent.class));
        verify(topic, never()).publish(any(ClusterEventEnvelope.class));
    }

    @Test
    void aReceivedContentEventIsNotRebroadcastBackToTheTopic() {
        // Same loop-guard, exercised through the NEW content listener: the replayed local event reaches
        // relay.onContentChanged (as Spring would deliver it) and must not go back out to the topic.
        doAnswer(inv -> {
            Object e = inv.getArgument(0);
            if (e instanceof ContentChangedEvent cce) {
                relay.onContentChanged(cce);
            }
            return null;
        }).when(events).publishEvent(any(Object.class));

        relay.handleClusterMessage(ClusterEventEnvelope.contentChanged("demo", "blog", "my-post", OTHER.toString()));

        verify(events).publishEvent(any(ContentChangedEvent.class));
        verify(topic, never()).publish(any(ClusterEventEnvelope.class));
    }

    @Test
    void aMalformedSiteChangedKindIsDroppedNotThrown() {
        // A newer node could broadcast a SiteChangedEvent.Kind this node doesn't know; the relay must swallow
        // the reconstruction failure rather than break the topic listener.
        relay.handleClusterMessage(
                ClusterEventEnvelope.siteChanged("demo", "NOT_A_REAL_KIND", null, OTHER.toString()));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aContentChangedEnvelopeSurvivesJavaSerializationRoundTrip() throws Exception {
        // The wire form: Hazelcast's default serializer is Java serialization of the JavaBean. Round-trip the
        // new kind (including the null-contentId whole-corpus form) to pin the serialization compatibility.
        for (String contentId : new String[] {"my-post", null}) {
            ClusterEventEnvelope out = ClusterEventEnvelope.contentChanged("demo", "blog", contentId, "node-1");
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bytes)) {
                oos.writeObject(out);
            }
            ClusterEventEnvelope in;
            try (java.io.ObjectInputStream ois =
                         new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
                in = (ClusterEventEnvelope) ois.readObject();
            }
            assertThat(in.getKind()).isEqualTo(ClusterEventEnvelope.Kind.CONTENT_CHANGED);
            assertThat(in.getSiteId()).isEqualTo("demo");
            assertThat(in.getSourceKey()).isEqualTo("blog");
            assertThat(in.getContentId()).isEqualTo(contentId);
            assertThat(in.getOriginNodeId()).isEqualTo("node-1");
        }
    }

    // --- fromBackup and the replay mark -----------------------------------------------

    @Test
    void aBackupImportCarriesFromBackupBothWays() {
        relay.onSiteChanged(SiteChangedEvent.createdFromBackup("demo"));
        ClusterEventEnvelope env = capturePublished();
        assertThat(env.getFromBackup()).isTrue();

        relay.handleClusterMessage(ClusterEventEnvelope.siteChanged("demo", "CREATED", null, true, OTHER.toString()));
        ArgumentCaptor<SiteChangedEvent> cap = ArgumentCaptor.forClass(SiteChangedEvent.class);
        verify(events).publishEvent(cap.capture());
        assertThat(cap.getValue().fromBackup()).as("a peer's birth-time seeders stand down too").isTrue();
    }

    @Test
    void anEnvelopeFromANodeWithoutFromBackupReadsAsFalse() {
        ClusterEventEnvelope old = ClusterEventEnvelope.siteChanged("demo", "CREATED", null, OTHER.toString());
        old.setFromBackup(null); // what a node that predates the field puts on the wire
        relay.handleClusterMessage(old);
        ArgumentCaptor<SiteChangedEvent> cap = ArgumentCaptor.forClass(SiteChangedEvent.class);
        verify(events).publishEvent(cap.capture());
        assertThat(cap.getValue().fromBackup()).isFalse();
    }

    @Test
    void aReplayIsMarkedForItsListenersAndTheMarkIsCleared() {
        java.util.List<Boolean> marked = new java.util.ArrayList<>();
        doAnswer(inv -> {
            marked.add(mx.quest.runway.event.ClusterReplay.active());
            return null;
        }).when(events).publishEvent(any(Object.class));
        relay.handleClusterMessage(ClusterEventEnvelope.pageChanged("demo", "about", OTHER.toString()));
        assertThat(marked).containsExactly(true);
        assertThat(mx.quest.runway.event.ClusterReplay.active()).isFalse();

        // also after a malformed envelope, whose replay throws
        relay.handleClusterMessage(ClusterEventEnvelope.siteChanged("demo", "NOT_A_KIND", null, OTHER.toString()));
        assertThat(mx.quest.runway.event.ClusterReplay.active()).isFalse();
    }

    @Test
    void theFromBackupFieldSurvivesJavaSerialization() throws Exception {
        ClusterEventEnvelope out = ClusterEventEnvelope.siteChanged("demo", "CREATED", null, true, "node-1");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bytes)) {
            oos.writeObject(out);
        }
        try (java.io.ObjectInputStream ois =
                     new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(((ClusterEventEnvelope) ois.readObject()).getFromBackup()).isTrue();
        }
    }

    private ClusterEventEnvelope capturePublished() {
        ArgumentCaptor<ClusterEventEnvelope> cap = ArgumentCaptor.forClass(ClusterEventEnvelope.class);
        verify(topic).publish(cap.capture());
        return cap.getValue();
    }
}
