package mx.quest.runway.editorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageAuthoringValidator;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageWriteService;
import mx.quest.runway.page.PendingRevisionStore;
import mx.quest.runway.quality.QualityGate;
import mx.quest.runway.site.EditorialLevel;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.InstallResult;
import mx.quest.runway.spi.model.InstallResult.Outcome;
import mx.quest.runway.spi.model.StarterPage;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.TemplateCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code SitePages}: a starter page installs through the editorial workflow — the four outcomes (published,
 * pending review, draft blocked, already exists) under Advisory, Enforced and Reviewed, never overwriting, never
 * publishing around the site's rules — and the input rules (unsafe → {@code IllegalArgumentException}, a write that
 * cannot happen → {@code IllegalStateException}).
 */
class SitePagesImplTest {

    private static final String SITE = "acme";
    private static final List<Finding> ERRORS = List.of(Finding.error("title.missing", "no title"));
    private static final StarterPage CONTACT = new StarterPage("contact", "/contact",
            Map.of("en", "Contact", "es", "Contacto"), "<main data-widget=\"contact\"></main>", null, false);

    @TempDir
    Path external;
    private SiteStore sites;
    private PageStore pages;
    private PendingRevisionStore pending;
    private QualityGate gate;
    private SiteLogService siteLog;
    private final List<String> granted = new ArrayList<>();
    private SitePagesImpl sitePages;

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        sites = new SiteStore(layout, yaml);
        level(EditorialLevel.ADVISORY);
        pages = new PageStore(layout, yaml);
        PageWriteService writer = new PageWriteService(layout, yaml, pages, mock(TemplateCacheService.class),
                new PageAuthoringValidator(sites, pages));
        pending = writer.pending();
        gate = mock(QualityGate.class);
        when(gate.blocking(any(), any(), any(), any())).thenReturn(List.of());
        siteLog = mock(SiteLogService.class);
        EditorialService editorial = new EditorialService(pages, writer, gate, siteLog);
        sitePages = new SitePagesImpl(sites, pages, writer, editorial, granted::contains);
        Path templates = layout.siteDir(SITE).resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("_master.html"), "<html><head></head><body></body></html>");
    }

    private void level(EditorialLevel level) {
        sites.save(new Site(SITE, "Acme", List.of("acme.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE).withEditorial(level));
    }

    private Page installed() {
        return pages.find(SITE, "contact").orElseThrow();
    }

    // --- the four outcomes --------------------------------------------------------------------------------

    @Test
    void advisoryPublishesAtOnceWithoutAskingTheGate() {
        InstallResult result = sitePages.install(SITE, CONTACT);
        assertEquals(new InstallResult(Outcome.PUBLISHED, "/contact", List.of()), result);
        Page page = installed();
        assertTrue(page.published());
        assertEquals(Map.of("en", "Contact", "es", "Contacto"), page.title());
        assertEquals("/contact", page.path());
        verify(gate, never()).blocking(any(), any(), any(), any());
    }

    @Test
    void enforcedPublishesWhenTheChecksPass() {
        level(EditorialLevel.ENFORCED);
        assertEquals(Outcome.PUBLISHED, sitePages.install(SITE, CONTACT).outcome());
        assertTrue(installed().published());
        verify(gate).blocking(any(), any(), any(), any());
    }

    @Test
    void enforcedLeavesADraftWithTheFindingsWhenTheChecksBlock() {
        level(EditorialLevel.ENFORCED);
        when(gate.blocking(any(), any(), any(), any())).thenReturn(ERRORS);
        InstallResult result = sitePages.install(SITE, CONTACT);
        assertEquals(Outcome.DRAFT_BLOCKED, result.outcome());
        assertEquals(ERRORS, result.blocking());
        assertEquals("/contact", result.path());
        assertTrue(installed().draft(), "the page exists, as a draft");
    }

    @Test
    void reviewedWithoutApprovePagesGoesToTheReviewQueue() {
        level(EditorialLevel.REVIEWED);
        InstallResult result = sitePages.install(SITE, CONTACT);
        assertEquals(Outcome.PENDING_REVIEW, result.outcome());
        assertTrue(installed().draft(), "not live until an approver approves it");
        assertTrue(pending.review(SITE, "contact").orElseThrow().isSubmitted(), "it is in the queue");
        verify(gate, never()).blocking(any(), any(), any(), any());
    }

    @Test
    void reviewedWithApprovePagesPublishesThroughTheGate() {
        level(EditorialLevel.REVIEWED);
        granted.add("APPROVE_PAGES");
        assertEquals(Outcome.PUBLISHED, sitePages.install(SITE, CONTACT).outcome());
        assertTrue(installed().published());
        when(gate.blocking(any(), any(), any(), any())).thenReturn(ERRORS);
        InstallResult blocked = sitePages.install(SITE, new StarterPage("search", "/search", Map.of(), "<p/>", null,
                true));
        assertEquals(Outcome.DRAFT_BLOCKED, blocked.outcome(), "an approver still cannot skip the checks");
    }

    @Test
    void anExistingPageIsNeverTouchedWhateverItsState() throws IOException {
        level(EditorialLevel.ADVISORY);
        sitePages.install(SITE, CONTACT);
        Path html = external.resolve("sites/acme/templates/contact.html");
        Files.writeString(html, "<p>EDITED BY THE OPERATOR</p>");
        InstallResult again = sitePages.install(SITE, new StarterPage("contact", "/elsewhere", Map.of(), "<p>NEW</p>",
                null, false));
        assertEquals(new InstallResult(Outcome.ALREADY_EXISTS, "/contact", List.of()), again,
                "the existing page's path, not the requested one");
        assertEquals("<p>EDITED BY THE OPERATOR</p>", Files.readString(html));
    }

    @Test
    void aDraftOrQueuedPageAlsoCountsAsExisting() {
        level(EditorialLevel.REVIEWED);
        sitePages.install(SITE, CONTACT);
        assertEquals(Outcome.ALREADY_EXISTS, sitePages.install(SITE, CONTACT).outcome());
    }

    // --- pathOf ---------------------------------------------------------------------------------------

    @Test
    void pathOfFindsThePageInAnyStateAndFollowsAMove() {
        assertEquals(Optional.empty(), sitePages.pathOf(SITE, "contact"));
        level(EditorialLevel.REVIEWED);
        sitePages.install(SITE, CONTACT);
        assertEquals(Optional.of("/contact"), sitePages.pathOf(SITE, "contact"), "a page waiting for review");
        assertEquals(Optional.empty(), sitePages.pathOf("nosuchsite", "contact"));
        assertThrows(IllegalArgumentException.class, () -> sitePages.pathOf("../x", "contact"));
        assertThrows(IllegalArgumentException.class, () -> sitePages.pathOf(SITE, "a/b"));
    }

    // --- input rules ----------------------------------------------------------------------------------

    @Test
    void unsafeOrReservedInputIsRefusedBeforeAnythingIsWritten() {
        for (String path : List.of("/__admin/contact", "/login", "/reset", "/forgot-password", "/actuator/x",
                "/error", "no-slash", "/a/../b", "/a//b", "/app/x")) {
            assertThrows(IllegalArgumentException.class, () -> sitePages.install(SITE,
                    new StarterPage("contact", path, Map.of(), "<p/>", null, false)), path);
        }
        assertThrows(IllegalArgumentException.class, () -> sitePages.install(SITE,
                new StarterPage("../contact", "/contact", Map.of(), "<p/>", null, false)));
        assertThrows(IllegalArgumentException.class, () -> sitePages.install("bad id", CONTACT));
        assertFalse(pages.find(SITE, "contact").isPresent());
    }

    @Test
    void aSiteThatDoesNotExistOrAPathCollisionIsAnIllegalState() {
        assertThrows(IllegalStateException.class, () -> sitePages.install("ghost", CONTACT));
        sitePages.install(SITE, CONTACT);
        IllegalStateException collision = assertThrows(IllegalStateException.class, () -> sitePages.install(SITE,
                new StarterPage("contact2", "/contact", Map.of(), "<p/>", null, false)));
        assertTrue(collision.getMessage().contains("contact2"), collision.getMessage());
        assertFalse(pages.find(SITE, "contact2").isPresent(), "a failed install leaves no stray draft behind");
        assertFalse(Files.exists(external.resolve("sites/acme/templates/contact2.html")));
    }

    @Test
    void withNoTitlesThePageIsTitledByItsSlugInTheSiteLanguage() {
        sitePages.install(SITE, new StarterPage("search", "/search", null, "<p/>", "", true));
        Page page = pages.find(SITE, "search").orElseThrow();
        assertEquals(Map.of("en", "search"), page.title());
        assertTrue(page.noindex());
    }
}
