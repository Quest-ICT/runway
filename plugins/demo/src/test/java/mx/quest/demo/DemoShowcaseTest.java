package mx.quest.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.SiteEventListener;
import mx.quest.runway.spi.testkit.EventDispatch;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import mx.quest.runway.spi.testkit.TempDirSiteFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The showcase content source and its store: writes are announced with {@code ContentChanges} (the testkit's
 * {@link EventDispatch}, which applies the kernel's refusal rules and phase order), the source serves what was
 * published, and its {@code SOURCE}-phase listener drops the cache on the events that mean the file changed under it.
 */
class DemoShowcaseTest {

    private static final SiteView SITE = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), null);

    @TempDir
    Path sites;
    private EventDispatch events;
    private AtomicLong nanos;
    private DemoShowcaseStore store;
    private DemoShowcaseSource source;
    private FakeSiteServices kernel;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(sites.resolve("acme"));
        events = new EventDispatch("runway-demo", Set.of(DemoShowcaseSource.SOURCE_KEY));
        nanos = new AtomicLong();
        store = new DemoShowcaseStore(new TempDirSiteFiles(sites, "runway-demo"), events, nanos::get);
        kernel = new FakeSiteServices().site(SITE);
        source = new DemoShowcaseSource(store, kernel);
        events.listener(source);
    }

    private Path file() {
        return sites.resolve("acme/plugins/runway-demo").resolve(DemoShowcaseStore.FILE);
    }

    @Test
    void nothingIsServedUntilAShowcaseIsPublished() {
        assertEquals(List.of(), source.enumerate(SITE));
        assertTrue(source.resolve(SITE, DemoShowcaseSource.PATH, Locale.ENGLISH).isEmpty());
        assertTrue(source.body(SITE, DemoShowcaseSource.ID, Locale.ENGLISH).bodyHtml().isEmpty());
    }

    @Test
    void publishWritesTheFileThenAnnouncesTheChangeOfItsOwnSource() {
        store.publish("acme", "  Hello <world>  ");
        assertTrue(Files.isRegularFile(file()));
        List<CatalogChange> announced = events.announced();
        assertEquals(1, announced.size());
        assertEquals(CatalogChange.Cause.CONTENT, announced.getFirst().cause());
        assertEquals("runway-demo", announced.getFirst().sourceKey());
        assertEquals("showcase", announced.getFirst().id());
        assertEquals("runway-demo", announced.getFirst().pluginId());
        assertTrue(events.failures().isEmpty(), events.failures().toString());
    }

    @Test
    void thePublishedShowcaseIsServedAsAPageOfTheSite() {
        kernel.message("acme", "runwaydemo.showcase.title", "Our showcase");
        store.publish("acme", "Line one\r\nLine <two> & three");
        List<ContentRef> refs = source.enumerate(SITE);
        assertEquals(1, refs.size());
        ContentRef ref = refs.getFirst();
        assertEquals("showcase", ref.id());
        assertEquals("/demo/showcase", ref.path());
        assertEquals("__plugin__/runway-demo/showcase", ref.bodyTemplate());
        assertTrue(ref.published());
        assertEquals("Our showcase", ref.titleFor(Locale.ENGLISH), "the title comes from Messages.site");
        assertEquals("Line one\r\nLine <two> & three", ref.descriptionFor(Locale.ENGLISH));
        assertEquals(ref, source.resolve(SITE, "/demo/showcase", Locale.ENGLISH).orElseThrow());
        assertTrue(source.resolve(SITE, "/demo/showcase", Locale.of("es")).isEmpty(), "default language only");
        assertTrue(source.resolve(SITE, "/demo/other", Locale.ENGLISH).isEmpty());
        assertEquals("<p>Line one<br>\nLine &lt;two&gt; &amp; three</p>",
                source.body(SITE, "showcase", Locale.ENGLISH).bodyHtml(), "escaped; line breaks kept");
        assertEquals("", source.body(SITE, "other", Locale.ENGLISH).bodyHtml());
        assertTrue(source.indexableText(SITE, "showcase", Locale.ENGLISH).contains("Line &lt;two&gt;"));
        assertTrue(source.capabilities().has(ContentSource.CAP_INDEXABLE_TEXT), "search indexes it");
    }

    @Test
    void aRepublishMovesUpdatedAtSoTheEtagChanges() throws InterruptedException {
        store.publish("acme", "one");
        ContentRef first = source.enumerate(SITE).getFirst();
        Thread.sleep(5);
        store.publish("acme", "two");
        ContentRef second = source.enumerate(SITE).getFirst();
        assertTrue(second.updatedAt().isAfter(first.updatedAt()), first.updatedAt() + " → " + second.updatedAt());
    }

    @Test
    void aLongDescriptionIsCutAndALongTextCapped() {
        store.publish("acme", "y".repeat(DemoShowcaseStore.MAX_TEXT + 10));
        ContentRef ref = source.enumerate(SITE).getFirst();
        assertEquals(151, ref.descriptionFor(Locale.ENGLISH).length(), "150 chars and an ellipsis");
        assertEquals(DemoShowcaseStore.MAX_TEXT + 7,
                source.body(SITE, "showcase", Locale.ENGLISH).bodyHtml().length(), "<p>…</p> around the capped text");
    }

    @Test
    void aBlankShowcaseIsRefusedAndAnnouncesNothing() {
        assertThrows(IllegalArgumentException.class, () -> store.publish("acme", "  "));
        assertTrue(events.announced().isEmpty());
    }

    @Test
    void unpublishDeletesAndAnnouncesOnlyWhenSomethingWasPublished() {
        assertFalse(store.unpublish("acme"), "nothing to unpublish");
        assertTrue(events.announced().isEmpty(), "and nothing announced");
        store.publish("acme", "live");
        assertTrue(store.unpublish("acme"));
        assertEquals(2, events.announced().size());
        assertFalse(Files.exists(file()));
        assertEquals(List.of(), source.enumerate(SITE));
    }

    @Test
    void theCacheServesWithinTheTtlAndRereadsAfterIt() throws IOException {
        store.publish("acme", "cached");
        store.current("acme");   // the own announcement evicted the cache; this read fills it again
        Files.writeString(file(), "text: edited by hand\nupdatedAt: '2026-01-01T00:00:00Z'\n", StandardCharsets.UTF_8);
        source.onCatalogChanged(noChange());   // an unrelated change does not evict
        assertEquals("cached", store.current("acme").orElseThrow().text(), "no announcement: the cache holds");
        nanos.addAndGet(DemoShowcaseStore.TTL.toNanos());
        assertEquals("edited by hand", store.current("acme").orElseThrow().text(), "the TTL is the backstop");
    }

    private static CatalogChange noChange() {
        return new CatalogChange("acme", CatalogChange.Cause.PAGE, "pages", "about", null, null, null, true);
    }

    @Test
    void aChangeReplayedFromAnotherNodeEvictsThisNodesCache() throws IOException {
        store.publish("acme", "before");
        Files.writeString(file(), "text: from the peer\nupdatedAt: '2026-01-01T00:00:00Z'\n", StandardCharsets.UTF_8);
        events.catalog(new CatalogChange("acme", CatalogChange.Cause.CONTENT, "runway-demo", "showcase",
                "runway-demo", null, null, false));
        assertEquals("from the peer", store.current("acme").orElseThrow().text());
    }

    @Test
    void aWholesaleLifecycleEventEvictsAndOthersDoNot() throws IOException {
        store.publish("acme", "before");
        store.current("acme");
        Files.writeString(file(), "text: restored\nupdatedAt: '2026-01-01T00:00:00Z'\n", StandardCharsets.UTF_8);
        source.onSiteLifecycle(new SiteLifecycleEvent("acme", SiteLifecycleEvent.Kind.UPDATED, false, true));
        assertEquals("before", store.current("acme").orElseThrow().text(), "an UPDATED site keeps its files");
        events.lifecycle("acme", SiteLifecycleEvent.Kind.RESTORED, true);
        assertEquals("restored", store.current("acme").orElseThrow().text(), "a RESTORED site replaced them");
    }

    @Test
    void theSourceListensInTheSourcePhase() {
        assertEquals(SiteEventListener.Phase.SOURCE, source.phase());
    }

    @Test
    void aCorruptOrForeignFileServesNothing() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "text: [ unclosed", StandardCharsets.UTF_8);
        assertTrue(store.current("acme").isEmpty());
        assertTrue(store.current("../x").isEmpty(), "an unsafe id answers nothing rather than throwing on a request");
    }

    @Test
    void announcingFromInsideACallbackIsRefusedByTheKernelRule() {
        EventDispatch strict = new EventDispatch("runway-demo", Set.of("runway-demo"));
        DemoShowcaseStore inner = new DemoShowcaseStore(new TempDirSiteFiles(sites, "runway-demo"), strict);
        strict.listener(new SiteEventListener() {
            @Override
            public void onSiteLifecycle(SiteLifecycleEvent event) {
                inner.publish("acme", "from a callback");
            }
        });
        strict.lifecycle("acme", SiteLifecycleEvent.Kind.UPDATED, false);
        assertEquals(1, strict.failures().size());
        assertTrue(strict.failures().getFirst() instanceof IllegalStateException);
    }
}
