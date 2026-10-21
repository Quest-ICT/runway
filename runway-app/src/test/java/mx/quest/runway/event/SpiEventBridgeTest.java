package mx.quest.runway.event;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import mx.quest.runway.content.ContentCatalog;
import mx.quest.runway.content.ContentChangedEvent;
import mx.quest.runway.content.ContentChangesImpl;
import mx.quest.runway.content.ContentSourceRegistry;
import mx.quest.runway.page.PageChangedEvent;
import mx.quest.runway.plugin.ContributionGuard;
import mx.quest.runway.plugin.SitePluginConfigChangedEvent;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.SettingsChange;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.SiteEventListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link SpiEventBridge} in isolation: phases and the second eviction between them, the order within a
 * phase and within one listener, settings scoping, isolation, the {@code local} flag, previous/current URLs, and the
 * re-entrancy refusal of {@code ContentChanges}.
 */
class SpiEventBridgeTest {

    private static final SiteView SITE = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), null);

    /** A self-caching content source: it serves {@link #index}, which only {@link #reindex} brings up to date. */
    static final class NotesSource implements ContentSource {
        volatile Map<String, String> data = Map.of("hello", "/notes/hello");
        volatile Map<String, String> index = data;

        void reindex() {
            index = data;
        }

        @Override
        public String key() {
            return "notes";
        }

        @Override
        public List<ContentRef> enumerate(SiteView site) {
            List<ContentRef> refs = new ArrayList<>();
            index.forEach((id, path) -> refs.add(ContentRef.of(id, path, Map.of(), List.of(), Map.of(), Map.of(),
                    "__plugin__/notes/t", null)));
            return refs;
        }

        @Override
        public ContentBody body(SiteView site, String id, Locale locale) {
            return ContentBody.empty();
        }
    }

    /** Records every callback, as {@code <name>:<what>}. */
    static class Recorder implements SiteEventListener {
        final String name;
        final Phase phase;
        final List<String> calls;
        final List<Object> received = new CopyOnWriteArrayList<>();

        Recorder(String name, Phase phase, List<String> calls) {
            this.name = name;
            this.phase = phase;
            this.calls = calls;
        }

        @Override
        public Phase phase() {
            return phase;
        }

        @Override
        public void onSiteLifecycle(SiteLifecycleEvent event) {
            calls.add(name + ":lifecycle");
            received.add(event);
        }

        @Override
        public void onSettingsChanged(SettingsChange change) {
            calls.add(name + ":settings");
            received.add(change);
        }

        @Override
        public void onCatalogChanged(CatalogChange change) {
            calls.add(name + ":catalog");
            received.add(change);
        }

        CatalogChange lastCatalog() {
            for (int i = received.size() - 1; i >= 0; i--) {
                if (received.get(i) instanceof CatalogChange c) {
                    return c;
                }
            }
            return null;
        }
    }

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final Map<Object, String> owners = new IdentityHashMap<>();
    private final Map<String, Integer> loadOrder = Map.of("alpha", 0, "beta", 1, "notes-plugin", 2);
    private NotesSource source;
    private ContentSourceRegistry registry;
    private ContentCatalog catalog;
    private final List<SiteEventListener> listeners = new ArrayList<>();
    private SpiEventBridge bridge;

    @BeforeEach
    void setUp() {
        source = new NotesSource();
        registry = new ContentSourceRegistry(List.of(source));
        catalog = new ContentCatalog(registry);
        owners.put(source, "notes-plugin");
    }

    private SpiEventBridge bridge() {
        bridge = new SpiEventBridge(() -> List.copyOf(listeners), () -> catalog,
                id -> "acme".equals(id) ? Optional.of(SITE) : Optional.empty(), ContributionGuard.standalone(),
                contribution -> Optional.ofNullable(owners.get(contribution)),
                id -> loadOrder.getOrDefault(id, Integer.MAX_VALUE), () -> registry);
        bridge.collect();
        return bridge;
    }

    private Recorder listener(String name, String plugin, SiteEventListener.Phase phase) {
        Recorder recorder = new Recorder(name, phase, calls);
        listeners.add(recorder);
        if (plugin != null) {
            owners.put(recorder, plugin);
        }
        return recorder;
    }

    /** What Spring does for one content event: CAPTURE first, kernel eviction, PLUGIN_DISPATCH last. */
    private void publishContent(ContentChangedEvent event) {
        bridge.captureContent(event);
        catalog.onContentChanged(event);
        bridge.onContentChanged(event);
    }

    private void publishPage(PageChangedEvent event) {
        bridge.capturePage(event);
        catalog.onPageChanged(event);
        bridge.onPageChanged(event);
    }

    // --- order ------------------------------------------------------------------------------------------------

    @Test
    void sourcePhaseRunsBeforeConsumersRegardlessOfRegistrationOrder() {
        listener("consumer", "alpha", SiteEventListener.Phase.CONSUMER);
        listener("source", "beta", SiteEventListener.Phase.SOURCE);
        bridge();
        bridge.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.UPDATED));
        assertEquals(List.of("source:lifecycle", "source:catalog", "consumer:lifecycle", "consumer:catalog"), calls);
    }

    @Test
    void withinAPhasePluginLoadOrderWinsAndKernelListenersComeLast() {
        listener("kernel", null, SiteEventListener.Phase.CONSUMER);
        listener("beta", "beta", SiteEventListener.Phase.CONSUMER);
        listener("alpha", "alpha", SiteEventListener.Phase.CONSUMER);
        listener("alpha2", "alpha", SiteEventListener.Phase.CONSUMER);
        bridge();
        bridge.onSitePluginConfigChanged(new SitePluginConfigChangedEvent("acme", "zeta"));
        assertEquals(List.of("alpha:catalog", "alpha2:catalog", "beta:catalog", "kernel:catalog"), calls,
                "load order, then registration order within a plugin");
    }

    @Test
    void settingsChangesReachOnlyTheOwnPluginAndBeforeTheCatalogCallback() {
        Recorder alpha = listener("alpha", "alpha", SiteEventListener.Phase.CONSUMER);
        Recorder beta = listener("beta", "beta", SiteEventListener.Phase.CONSUMER);
        Recorder kernel = listener("kernel", null, SiteEventListener.Phase.CONSUMER);
        bridge();
        bridge.onSitePluginConfigChanged(new SitePluginConfigChangedEvent("acme", "alpha"));
        assertEquals(List.of("alpha:settings", "alpha:catalog", "beta:catalog", "kernel:catalog"), calls);
        SettingsChange settings = (SettingsChange) alpha.received.get(0);
        assertEquals("acme", settings.siteId());
        assertEquals("alpha", settings.pluginId());
        CatalogChange change = beta.lastCatalog();
        assertEquals(CatalogChange.Cause.SETTINGS, change.cause());
        assertEquals("alpha", change.pluginId(), "review m20: the catalog change names whose settings changed");
        assertTrue(change.previousUrls().isEmpty() && change.currentUrls().isEmpty());
        assertEquals(1, kernel.received.size(), "a listener of no plugin never receives settings changes");
    }

    @Test
    void theLifecycleCallbackPrecedesTheCatalogCallbackAndCarriesTheEvent() {
        Recorder recorder = listener("one", "alpha", SiteEventListener.Phase.CONSUMER);
        bridge();
        bridge.onSiteChanged(SiteChangedEvent.createdFromBackup("acme"));
        assertEquals(List.of("one:lifecycle", "one:catalog"), calls);
        SiteLifecycleEvent event = (SiteLifecycleEvent) recorder.received.get(0);
        assertEquals(SiteLifecycleEvent.Kind.CREATED, event.kind());
        assertTrue(event.fromBackup());
        assertTrue(event.local());
        CatalogChange change = recorder.lastCatalog();
        assertEquals(CatalogChange.Cause.SITE, change.cause());
        assertNull(change.sourceKey());
        assertNull(change.id());
    }

    @Test
    void everyKernelSiteKindMapsToTheSpiKind() {
        Recorder recorder = listener("one", "alpha", SiteEventListener.Phase.CONSUMER);
        bridge();
        for (SiteChangedEvent.Kind kind : SiteChangedEvent.Kind.values()) {
            bridge.onSiteChanged(new SiteChangedEvent("acme", kind));
        }
        List<String> kinds = recorder.received.stream().filter(SiteLifecycleEvent.class::isInstance)
                .map(e -> ((SiteLifecycleEvent) e).kind().name()).toList();
        assertEquals(java.util.Arrays.stream(SiteChangedEvent.Kind.values()).map(Enum::name).toList(), kinds);
    }

    // --- the second eviction between the phases -----------------------------------------------------------

    @Test
    void consumersNeverSeeAListingRebuiltFromASourcesStaleIndex() {
        // The source drops its index in the SOURCE phase. Before that, a concurrent request rebuilt (and cached) the
        // listing from the stale index — simulated here by enumerating after the kernel's eviction.
        listener("notes", "notes-plugin", new SiteEventListener() {
            @Override
            public Phase phase() {
                return Phase.SOURCE;
            }

            @Override
            public void onCatalogChanged(CatalogChange change) {
                source.reindex();
            }
        });
        List<List<String>> consumerSaw = new ArrayList<>();
        listener("consumer", "alpha", new SiteEventListener() {
            @Override
            public void onCatalogChanged(CatalogChange change) {
                consumerSaw.add(catalog.entries(SITE).stream().map(e -> e.compositeId()).toList());
            }
        });
        bridge();
        source.data = Map.of("hello", "/notes/hello", "added", "/notes/added");
        ContentChangedEvent event = new ContentChangedEvent("acme", "notes", "added");
        bridge.captureContent(event);
        catalog.onContentChanged(event);
        catalog.entries(SITE); // the concurrent rebuild from the stale index, cached
        bridge.onContentChanged(event);
        assertEquals(1, consumerSaw.size());
        assertTrue(consumerSaw.get(0).contains("notes:added"),
                "the second eviction dropped the stale listing; saw " + consumerSaw.get(0));
    }

    private void listener(String name, String plugin, SiteEventListener listener) {
        listeners.add(listener);
        owners.put(listener, plugin);
    }

    // --- URLs ------------------------------------------------------------------------------------------------

    @Test
    void aDeletedItemReportsItsPreviousUrlsAndNoCurrentOnes() {
        Recorder source = listener("source", "beta", SiteEventListener.Phase.SOURCE);
        Recorder consumer = listener("consumer", "alpha", SiteEventListener.Phase.CONSUMER);
        bridge();
        catalog.entries(SITE); // the site has been listed: the last-seen index knows notes:hello
        this.source.data = Map.of();
        this.source.reindex();
        publishContent(new ContentChangedEvent("acme", "notes", "hello"));

        CatalogChange change = consumer.lastCatalog();
        assertEquals(CatalogChange.Cause.CONTENT, change.cause());
        assertEquals("notes", change.sourceKey());
        assertEquals("hello", change.id());
        assertEquals("notes-plugin", change.pluginId(), "the plugin that owns the source");
        assertEquals(List.of(new LocalizedUrl(Locale.ENGLISH, "/notes/hello", null)), change.previousUrls());
        assertEquals(List.of(), change.currentUrls(), "gone after the change");
        assertEquals(change.previousUrls(), source.lastCatalog().previousUrls());
    }

    @Test
    void aMovedItemReportsBothAddressesButTheSourcePhaseNoCurrentOnes() {
        Recorder source = listener("source", "beta", SiteEventListener.Phase.SOURCE);
        Recorder consumer = listener("consumer", "alpha", SiteEventListener.Phase.CONSUMER);
        bridge();
        catalog.entries(SITE);
        this.source.data = Map.of("hello", "/notes/moved");
        this.source.reindex();
        publishContent(new ContentChangedEvent("acme", "notes", "hello"));

        assertEquals("/notes/hello", consumer.lastCatalog().previousUrls().get(0).path());
        assertEquals("/notes/moved", consumer.lastCatalog().currentUrls().get(0).path());
        assertEquals(List.of(), source.lastCatalog().currentUrls(),
                "the source phase runs before the listing can be resolved");
    }

    @Test
    void aSiteNeverListedSinceBootHasNoPreviousUrls() {
        Recorder consumer = listener("consumer", "alpha", SiteEventListener.Phase.CONSUMER);
        bridge();
        publishPage(new PageChangedEvent("acme", "about"));
        assertEquals(List.of(), consumer.lastCatalog().previousUrls());
        assertEquals("pages", consumer.lastCatalog().sourceKey());
        assertEquals("about", consumer.lastCatalog().id());
        assertNull(consumer.lastCatalog().pluginId());
    }

    @Test
    void aWholeCorpusChangeCarriesNoUrls() {
        Recorder consumer = listener("consumer", "alpha", SiteEventListener.Phase.CONSUMER);
        bridge();
        catalog.entries(SITE);
        publishContent(new ContentChangedEvent("acme", "notes", null));
        assertNull(consumer.lastCatalog().id());
        assertEquals(List.of(), consumer.lastCatalog().previousUrls());
        assertEquals(List.of(), consumer.lastCatalog().currentUrls());
    }

    // --- isolation, local, reentrancy ----------------------------------------------------------------------

    @Test
    void aThrowingListenerNeverReachesThePublisherOrTheNextListener() {
        listener("bad", "alpha", new SiteEventListener() {
            @Override
            public void onCatalogChanged(CatalogChange change) {
                throw new IllegalStateException("listener boom");
            }

            @Override
            public void onSiteLifecycle(SiteLifecycleEvent event) {
                throw new NoClassDefFoundError("a missing class");
            }
        });
        Recorder next = listener("next", "beta", SiteEventListener.Phase.CONSUMER);
        bridge();
        assertDoesNotThrow(() -> bridge.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.UPDATED)));
        assertEquals(List.of("next:lifecycle", "next:catalog"), calls);
        assertEquals(2, next.received.size());
    }

    @Test
    void aThrowingPhaseCountsAsConsumer() {
        listener("weird", "alpha", new SiteEventListener() {
            @Override
            public Phase phase() {
                throw new IllegalStateException("no phase for you");
            }
        });
        listener("nullphase", "beta", new SiteEventListener() {
            @Override
            public Phase phase() {
                return null;
            }
        });
        bridge();
        assertTrue(bridge.listeners().stream().allMatch(r -> r.phase() == SiteEventListener.Phase.CONSUMER));
    }

    @Test
    void aReplayedEventIsNotLocal() {
        Recorder recorder = listener("one", "alpha", SiteEventListener.Phase.CONSUMER);
        bridge();
        bridge.onPageChanged(new PageChangedEvent("acme", "about"));
        ClusterReplay.run(() -> bridge.onSitePluginConfigChanged(new SitePluginConfigChangedEvent("acme", "alpha")));
        ClusterReplay.run(() -> bridge.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.RESUMED)));
        List<Boolean> locals = recorder.received.stream().map(r -> switch (r) {
            case CatalogChange c -> c.local();
            case SettingsChange s -> s.local();
            case SiteLifecycleEvent e -> e.local();
            default -> throw new IllegalStateException();
        }).toList();
        assertEquals(List.of(true, false, false, false, false), locals);
        assertFalse(ClusterReplay.active(), "the mark is cleared after the replay");
    }

    @Test
    void contentChangesIsRefusedFromInsideACallbackAndTheChangeStillCompletes() {
        ApplicationEventPublisher publisher = event -> { };
        ContentChangesImpl changes = new ContentChangesImpl("notes-plugin", () -> registry,
                type -> type == NotesSource.class ? Optional.of("notes-plugin") : Optional.empty(), publisher);
        List<Throwable> refused = new ArrayList<>();
        List<Boolean> inside = new ArrayList<>();
        listener("reentrant", "notes-plugin", new SiteEventListener() {
            @Override
            public void onCatalogChanged(CatalogChange change) {
                inside.add(SpiEventBridge.inCallback());
                try {
                    changes.changed("acme", "notes", "x");
                } catch (IllegalStateException e) {
                    refused.add(e);
                }
            }
        });
        Recorder after = listener("after", "beta", SiteEventListener.Phase.CONSUMER);
        bridge();
        bridge.onSiteChanged(new SiteChangedEvent("acme", SiteChangedEvent.Kind.UPDATED));
        assertEquals(List.of(true), inside);
        assertEquals(1, refused.size());
        assertFalse(SpiEventBridge.inCallback(), "outside a callback again");
        assertEquals(2, after.received.size(), "the dispatch went on");
        assertDoesNotThrow(() -> changes.changed("acme", "notes", "x"), "allowed outside a callback");
    }

    @Test
    void withNoListenersNothingIsCapturedOrDispatched() {
        bridge();
        assertTrue(bridge.listeners().isEmpty());
        catalog.entries(SITE);
        assertDoesNotThrow(() -> publishContent(new ContentChangedEvent("acme", "notes", "hello")));
    }

    @Test
    void anEventInsideAWriteScopeIsDeliveredWhenTheScopeCloses() {
        Recorder recorder = listener("one", "alpha", SiteEventListener.Phase.CONSUMER);
        bridge();
        try (HostWriteScope scope = HostWriteScope.enter()) {
            publishPage(new PageChangedEvent("acme", "about"));
            assertTrue(recorder.received.isEmpty(), "deferred while the scope is open");
        }
        assertEquals(1, recorder.received.size());
    }

    @Test
    void anUnknownSiteResolvesNoCurrentUrlsAndDoesNotFail() {
        Recorder recorder = listener("one", "alpha", SiteEventListener.Phase.CONSUMER);
        bridge();
        publishPage(new PageChangedEvent("gone", "about"));
        assertEquals(List.of(), recorder.lastCatalog().currentUrls());
        assertThrows(IllegalArgumentException.class, () -> new ContentChangesImpl("p", () -> registry,
                t -> Optional.empty(), e -> { }).changed("../x", "notes", null));
    }
}
