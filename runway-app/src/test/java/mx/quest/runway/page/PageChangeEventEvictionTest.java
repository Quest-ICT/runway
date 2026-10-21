package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.TemplateCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Proves the page-write eviction now flows through {@link PageChangedEvent} + a local
 * {@link PageCacheInvalidationListener}, with the <b>same effect</b> as the old inline evict:
 *
 * <ul>
 *   <li>a page write publishes a single {@link PageChangedEvent} for that page;</li>
 *   <li>the listener evicts both the parsed-template cache ({@link TemplateCacheService#evictPage}) and the
 *       {@link PageStore} record cache — a re-read after an overwrite sees the NEW meta (no stale record);</li>
 *   <li>a transient preview scratch write is NOT evented (it still evicts inline, never cluster-relevant).</li>
 * </ul>
 *
 * <p>The publisher is a tiny synchronous stand-in that forwards straight to the real listener — modelling
 * Spring's default synchronous dispatch — so the assertion "eviction has happened by the time the write
 * returns" is exercised exactly as in production.
 */
class PageChangeEventEvictionTest {

    private static final String SITE = "acme";

    @TempDir
    Path external;
    private PageStore pages;
    private TemplateCacheService templateCache;
    private PageCacheInvalidationListener listener;
    private RecordingPublisher publisher;
    private PageWriteService writer;
    private Path templates;

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        sites.save(new Site(SITE, "Acme", List.of("acme.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE));
        pages = new PageStore(layout, yaml);
        templateCache = mock(TemplateCacheService.class);
        listener = new PageCacheInvalidationListener(pages, templateCache);
        publisher = new RecordingPublisher(listener);
        writer = new PageWriteService(layout, yaml, pages, templateCache,
                new PageAuthoringValidator(sites, pages), publisher, null);
        templates = layout.siteDir(SITE).resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("_master.html"), "<html><head></head><body></body></html>");
    }

    private Page page(String slug, boolean draft) {
        return new Page(slug, "/" + slug, Map.of(), List.of(), Map.of("en", "T"), Map.of(), "", "_master",
                draft, false, null, null);
    }

    @Test
    void aPageSavePublishesOnePageChangedEventAndTheListenerEvictsBothCaches() {
        writer.save(SITE, page("about", false), "<html/>", null);

        // Exactly one PAGE_CHANGED event for that page reached the publisher...
        assertEquals(1, publisher.pageEvents.size());
        assertEquals(SITE, publisher.pageEvents.get(0).siteId());
        assertEquals("about", publisher.pageEvents.get(0).slug());
        // ...and the listener it forwarded to evicted the parsed-template cache for that exact page.
        verify(templateCache).evictPage(SITE, "about");
    }

    @Test
    void overwriteEvictsTheRecordCacheThroughTheEventSoFindSeesTheNewMeta() {
        writer.save(SITE, page("x", false), "<html/>", null);
        assertTrue(pages.find(SITE, "x").orElseThrow().published(), "cached as published");
        // Overwrite as a draft: the record-cache eviction (via the event → listener) must let the re-read see it.
        writer.save(SITE, page("x", true), "<html/>", null);
        assertTrue(pages.find(SITE, "x").orElseThrow().draft(),
                "the record cache was evicted through the event, so the draft flag is seen on re-read");
    }

    @Test
    void deletePublishesTheEventAndEvicts() {
        writer.save(SITE, page("gone", false), "<html/>", null);
        clearInvocations(templateCache);
        publisher.pageEvents.clear();

        assertTrue(writer.delete(SITE, "gone"));
        assertEquals(1, publisher.pageEvents.size());
        assertEquals("gone", publisher.pageEvents.get(0).slug());
        verify(templateCache).evictPage(SITE, "gone");
        assertTrue(pages.find(SITE, "gone").isEmpty(), "deleted page is gone from the record cache too");
    }

    @Test
    void previewScratchWriteIsNotEventedButStillEvictsInline() {
        writer.writeScratch(SITE, page("rw-preview-1", true), "<html/>", null);
        // No cluster-relevant event for a transient scratch page...
        assertTrue(publisher.pageEvents.isEmpty(), "a preview scratch write publishes no PageChangedEvent");
        // ...but its parsed-template entry was still evicted inline (fresh preview never serves a stale parse).
        verify(templateCache).evictPage(SITE, "rw-preview-1");
    }

    @Test
    void aNullSlugEventEvictsAllPages() {
        // The wholesale (null-slug) branch of the listener — a future site-wide page invalidation.
        writer.save(SITE, page("one", false), "<html/>", null);
        listener.onPageChanged(new PageChangedEvent(SITE, null));
        verify(templateCache).evictAll();
    }

    /** A synchronous {@link ApplicationEventPublisher} that records page events and forwards straight to the
     *  real listener — Spring's default dispatch is synchronous, which is the property under test. */
    private static final class RecordingPublisher implements ApplicationEventPublisher {
        private final PageCacheInvalidationListener listener;
        private final List<PageChangedEvent> pageEvents = new java.util.ArrayList<>();

        RecordingPublisher(PageCacheInvalidationListener listener) {
            this.listener = listener;
        }

        @Override
        public void publishEvent(Object event) {
            if (event instanceof PageChangedEvent pce) {
                pageEvents.add(pce);
                listener.onPageChanged(pce);
            }
        }
    }
}
