package mx.quest.runway.editorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageAuthoringValidator;
import mx.quest.runway.page.PageReview;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageWriteService;
import mx.quest.runway.page.PendingRevisionStore;
import mx.quest.runway.quality.QualityGate;
import mx.quest.runway.site.EditorialLevel;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.TemplateCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The editorial rules of {@link EditorialService}, level by level, over real page storage with the
 * {@link QualityGate} stubbed (its rendering is exercised end to end in the console screen test). Each test names
 * the rule it pins; the matrix is: advisory changes nothing, enforced gates publish and live saves on ERRORs only,
 * reviewed routes a non-approver's live edit into a pending revision and requires approval to publish.
 */
class EditorialServiceTest {

    private static final String SITE = "acme";
    private static final Instant NOW = Instant.parse("2026-10-17T15:00:00Z");
    private static final List<Finding> ERRORS = List.of(Finding.error("title.missing", "no title"));

    @TempDir
    Path external;
    private PageStore pages;
    private PageWriteService writer;
    private PendingRevisionStore pending;
    private QualityGate gate;
    private SiteLogService siteLog;
    private EditorialService service;
    private Site advisory;
    private Site enforced;
    private Site reviewed;

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        advisory = new Site(SITE, "Acme", List.of("acme.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE);
        sites.save(advisory);
        enforced = advisory.withEditorial(EditorialLevel.ENFORCED);
        reviewed = advisory.withEditorial(EditorialLevel.REVIEWED);
        pages = new PageStore(layout, yaml);
        writer = new PageWriteService(layout, yaml, pages, mock(TemplateCacheService.class),
                new PageAuthoringValidator(sites, pages));
        pending = writer.pending();
        gate = mock(QualityGate.class);
        when(gate.blocking(any(), any(), any(), any())).thenReturn(List.of());
        siteLog = mock(SiteLogService.class);
        service = new EditorialService(pages, writer, gate, siteLog, Clock.fixed(NOW, ZoneOffset.UTC));
        Path templates = layout.siteDir(SITE).resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("_master.html"), "<html><head></head><body></body></html>");
        writer.save(SITE, page("live", "/live", false, "Live"), "<p>LIVE</p>", null);
        writer.save(SITE, page("draft", "/draft", true, "Draft"), "<p>DRAFT</p>", null);
    }

    private static Page page(String slug, String path, boolean draft, String title) {
        return new Page(slug, path, Map.of(), List.of(), Map.of("en", title), Map.of(), "", "_master", draft, false,
                null, null);
    }

    private String liveBody() {
        return writer.readBody(SITE, "live").orElseThrow();
    }

    // --- advisory --------------------------------------------------------------------------------------

    @Test
    void advisoryWritesLiveAndPublishesWithoutEverAskingTheGate() {
        EditorialService.SaveResult r = service.save(advisory, page("live", "/live", false, "L2"), "<p>NEW</p>",
                null, false, null);
        assertEquals(EditorialService.Outcome.SAVED, r.outcome());
        assertEquals("<p>NEW</p>", liveBody());
        assertTrue(service.publish(advisory, "draft", false).isEmpty());
        assertTrue(pages.find(SITE, "draft").orElseThrow().published());
        verify(gate, never()).blocking(any(), any(), any(), any());
    }

    // --- enforced --------------------------------------------------------------------------------------

    @Test
    void enforcedBlocksPublishingOnErrorsAndJournalsIt() {
        when(gate.blocking(any(), any(), any(), any())).thenReturn(ERRORS);
        List<Finding> blocking = service.publish(enforced, "draft", false);
        assertEquals(ERRORS, blocking);
        assertTrue(pages.find(SITE, "draft").orElseThrow().draft(), "still a draft");
        verify(siteLog).audit(eq(SITE), anyString(), eq("page.quality.blocked"), eq("draft publish: title.missing"));
    }

    @Test
    void enforcedPublishesWhenTheGateIsClean() {
        assertTrue(service.publish(enforced, "draft", false).isEmpty());
        assertTrue(pages.find(SITE, "draft").orElseThrow().published());
        verify(gate).blocking(eq(enforced), any(), eq("<p>DRAFT</p>"), any());
    }

    @Test
    void enforcedBlocksSavingAPublishedPageButNeverADraft() {
        when(gate.blocking(any(), any(), any(), any())).thenReturn(ERRORS);
        EditorialService.SaveResult live = service.save(enforced, page("live", "/live", false, ""), "<p>BAD</p>",
                null, false, null);
        assertEquals(EditorialService.Outcome.BLOCKED, live.outcome());
        assertEquals(ERRORS, live.blocking());
        assertEquals("<p>LIVE</p>", liveBody(), "nothing was written");

        EditorialService.SaveResult draft = service.save(enforced, page("draft", "/draft", true, ""), "<p>WIP</p>",
                null, false, null);
        assertEquals(EditorialService.Outcome.SAVED, draft.outcome(), "a draft is work in progress");
        assertEquals("<p>WIP</p>", writer.readBody(SITE, "draft").orElseThrow());
    }

    @Test
    void publishingAnAlreadyLivePageIsANoOpThatSkipsTheGate() {
        assertTrue(service.publish(enforced, "live", false).isEmpty());
        verify(gate, never()).blocking(any(), any(), any(), any());
    }

    // --- reviewed: saving --------------------------------------------------------------------------------

    @Test
    void aNonApproversLiveEditBecomesASubmittedPendingRevision() {
        EditorialService.SaveResult r = service.save(reviewed, page("live", "/live", false, "Proposed"),
                "<p>PROPOSED</p>", null, false, null);
        assertEquals(EditorialService.Outcome.SAVED_AS_REVISION, r.outcome());
        assertEquals("<p>LIVE</p>", liveBody(), "the live page is untouched");
        assertEquals("<p>PROPOSED</p>", pending.revision(SITE, "live").orElseThrow().html());
        PageReview review = pending.review(SITE, "live").orElseThrow();
        assertTrue(review.isSubmitted());
        assertEquals(NOW, review.submittedAt());
        assertEquals("published-pending", service.status(SITE, pages.find(SITE, "live").orElseThrow()).key());
        verify(gate, never()).blocking(any(), any(), any(), any());
        verify(siteLog).audit(eq(SITE), anyString(), eq("page.revision.submitted"), eq("live"));
    }

    @Test
    void aNonApproverEditsADraftInPlace() {
        EditorialService.SaveResult r = service.save(reviewed, page("draft", "/draft", true, "D2"), "<p>D2</p>",
                null, false, null);
        assertEquals(EditorialService.Outcome.SAVED, r.outcome());
        assertEquals("<p>D2</p>", writer.readBody(SITE, "draft").orElseThrow());
        assertFalse(pending.exists(SITE, "draft"));
    }

    @Test
    void anApproverPublishesTheirOwnLiveEditDirectlyAfterTheGate() {
        EditorialService.SaveResult r = service.save(reviewed, page("live", "/live", false, "L2"), "<p>MINE</p>",
                null, true, null);
        assertEquals(EditorialService.Outcome.SAVED, r.outcome());
        assertEquals("<p>MINE</p>", liveBody());
        assertFalse(pending.exists(SITE, "live"));
        verify(gate).blocking(eq(reviewed), any(), eq("<p>MINE</p>"), any());
    }

    @Test
    void aDirectSaveAbsorbsOnlyTheRevisionTheEditorWasShowing() {
        service.save(reviewed, page("live", "/live", false, "P"), "<p>PROPOSED</p>", null, false, null);
        String key = pending.fingerprint(SITE, "live");

        // An approver whose editor loaded BEFORE the revision existed (no key): the revision survives, unseen work
        // is never discarded.
        service.save(reviewed, page("live", "/live", false, "A"), "<p>APPROVER</p>", null, true, "");
        assertEquals("<p>APPROVER</p>", liveBody());
        assertTrue(pending.hasRevision(SITE, "live"), "a revision created after the editor loaded is kept");

        // An approver who was editing that revision (its key): what they saved is the revision, now published.
        service.save(reviewed, page("live", "/live", false, "A2"), "<p>MERGED</p>", null, true, key);
        assertEquals("<p>MERGED</p>", liveBody());
        assertFalse(pending.exists(SITE, "live"));
        verify(siteLog).audit(eq(SITE), anyString(), eq("page.revision.published"), eq("live"));
    }

    @Test
    void savingAMissingPageIsRefused() {
        EditorialException ex = assertThrows(EditorialException.class,
                () -> service.save(reviewed, page("ghost", "/g", false, "G"), "<p/>", null, false, null));
        assertEquals("no-such-page", ex.code());
    }

    // --- reviewed: publishing, submitting --------------------------------------------------------------

    @Test
    void aNonApproverCannotPublishOnAReviewedSiteEvenWithoutTheGate() {
        EditorialException ex = assertThrows(EditorialException.class,
                () -> service.publish(reviewed, "draft", false));
        assertEquals("needs-approval", ex.code());
        assertTrue(pages.find(SITE, "draft").orElseThrow().draft());
        assertTrue(service.publish(reviewed, "draft", true).isEmpty(), "an approver can");
        assertTrue(pages.find(SITE, "draft").orElseThrow().published());
    }

    @Test
    void submittingADraftPutsItInTheQueueAndPublishingDirectlyClearsIt() {
        service.submit(reviewed, "draft");
        assertEquals("in-review", service.status(SITE, pages.find(SITE, "draft").orElseThrow()).key());
        assertEquals(List.of("draft"), service.queue(reviewed).stream().map(i -> i.page().slug()).toList());
        assertFalse(service.queue(reviewed).get(0).revision());
        verify(siteLog).audit(eq(SITE), anyString(), eq("page.review.submitted"), eq("draft"));

        service.publish(reviewed, "draft", true);
        assertTrue(service.queue(reviewed).isEmpty(), "a direct publish by an approver ends the review");
        assertFalse(pending.exists(SITE, "draft"));
    }

    @Test
    void submitRules() {
        assertEquals("not-reviewed",
                assertThrows(EditorialException.class, () -> service.submit(enforced, "draft")).code());
        assertEquals("nothing-to-review",
                assertThrows(EditorialException.class, () -> service.submit(reviewed, "live")).code());
        assertEquals("no-such-page",
                assertThrows(EditorialException.class, () -> service.submit(reviewed, "ghost")).code());
    }

    // --- reviewed: approving, sending back, withdrawing ------------------------------------------------

    @Test
    void approvingARevisionReplacesTheLiveFilesAndClearsThePendingWork() {
        service.save(reviewed, page("live", "/live", false, "Proposed"), "<p>PROPOSED</p>", "<thlogic/>", false, null);
        String key = service.queue(reviewed).get(0).fingerprint();
        assertTrue(service.approve(reviewed, "live", key).isEmpty());
        assertEquals("<p>PROPOSED</p>", liveBody());
        assertEquals("<thlogic/>", writer.readLogic(SITE, "live").orElseThrow());
        Page now = pages.find(SITE, "live").orElseThrow();
        assertEquals("Proposed", now.title().get("en"));
        assertTrue(now.published(), "it stays published");
        assertFalse(pending.exists(SITE, "live"));
        verify(siteLog).audit(eq(SITE), anyString(), eq("page.review.approved"), eq("live"));
    }

    @Test
    void approvingAFirstPublicationPublishesTheDraft() {
        service.submit(reviewed, "draft");
        assertTrue(service.approve(reviewed, "draft", pending.fingerprint(SITE, "draft")).isEmpty());
        assertTrue(pages.find(SITE, "draft").orElseThrow().published());
        assertFalse(pending.exists(SITE, "draft"));
    }

    @Test
    void anApprovalOfContentThatChangedSinceItWasShownIsRefused() {
        service.save(reviewed, page("live", "/live", false, "P1"), "<p>ONE</p>", null, false, null);
        String seen = service.queue(reviewed).get(0).fingerprint();
        service.save(reviewed, page("live", "/live", false, "P2"), "<p>TWO</p>", null, false, null);
        EditorialException ex = assertThrows(EditorialException.class, () -> service.approve(reviewed, "live", seen));
        assertEquals("changed", ex.code());
        assertEquals("<p>LIVE</p>", liveBody(), "nothing was published");
        assertEquals("changed",
                assertThrows(EditorialException.class, () -> service.approve(reviewed, "live", null)).code());
    }

    @Test
    void approvalRunsTheGateAndAnErrorKeepsEverythingAsItWas() {
        service.save(reviewed, page("live", "/live", false, ""), "<p>BROKEN</p>", null, false, null);
        when(gate.blocking(any(), any(), any(), any())).thenReturn(ERRORS);
        List<Finding> blocking = service.approve(reviewed, "live", pending.fingerprint(SITE, "live"));
        assertEquals(ERRORS, blocking);
        assertEquals("<p>LIVE</p>", liveBody());
        assertTrue(pending.review(SITE, "live").orElseThrow().isSubmitted(), "still waiting");
        verify(siteLog).audit(eq(SITE), anyString(), eq("page.quality.blocked"), startsWith("live approve:"));
    }

    @Test
    void approvingNothingIsRefused() {
        assertEquals("not-in-review", assertThrows(EditorialException.class,
                () -> service.approve(reviewed, "live", pending.fingerprint(SITE, "live"))).code());
        assertEquals("no-such-page", assertThrows(EditorialException.class,
                () -> service.approve(reviewed, "ghost", "x")).code());
    }

    @Test
    void sendingBackKeepsTheRevisionAsTheEditorsWorkingCopyWithTheNote() {
        service.save(reviewed, page("live", "/live", false, "P"), "<p>PROPOSED</p>", null, false, null);
        service.sendBack(reviewed, "live", "Fix the heading, please.\nAnd the image.");
        PageReview review = pending.review(SITE, "live").orElseThrow();
        assertTrue(review.isReturned());
        assertEquals("Fix the heading, please.\nAnd the image.", review.note());
        assertEquals(NOW, review.returnedAt());
        assertEquals("<p>PROPOSED</p>", pending.revision(SITE, "live").orElseThrow().html(), "the revision stays");
        assertEquals("<p>LIVE</p>", liveBody());
        assertTrue(service.queue(reviewed).isEmpty(), "out of the queue");
        assertEquals("published-returned", service.status(SITE, pages.find(SITE, "live").orElseThrow()).key());

        // The editor saves again: the revision is resubmitted and the note is gone.
        service.save(reviewed, page("live", "/live", false, "P2"), "<p>FIXED</p>", null, false, null);
        assertTrue(pending.review(SITE, "live").orElseThrow().isSubmitted());
        assertEquals(1, service.queue(reviewed).size());
    }

    @Test
    void theAuditKeepsAShortenedNote() {
        service.submit(reviewed, "draft");
        String note = "n".repeat(EditorialService.AUDIT_NOTE_CHARS + 50);
        service.sendBack(reviewed, "draft", note);
        verify(siteLog).audit(eq(SITE), anyString(), eq("page.review.returned"),
                eq("draft: " + "n".repeat(EditorialService.AUDIT_NOTE_CHARS) + "…"));
        assertEquals("returned", service.status(SITE, pages.find(SITE, "draft").orElseThrow()).key());
        service.submit(reviewed, "draft");
        assertEquals("in-review", service.status(SITE, pages.find(SITE, "draft").orElseThrow()).key(),
                "a returned draft can be resubmitted");
    }

    @Test
    void sendBackRules() {
        assertEquals("note-required", assertThrows(EditorialException.class,
                () -> service.sendBack(reviewed, "draft", "   ")).code());
        assertEquals("not-in-review", assertThrows(EditorialException.class,
                () -> service.sendBack(reviewed, "draft", "why")).code());
    }

    @Test
    void withdrawDiscardsARevisionOrWithdrawsADraftAndIsOtherwiseANoOp() {
        service.save(reviewed, page("live", "/live", false, "P"), "<p>PROPOSED</p>", null, false, null);
        service.withdraw(reviewed, "live");
        assertFalse(pending.exists(SITE, "live"));
        assertEquals("<p>LIVE</p>", liveBody());
        verify(siteLog).audit(eq(SITE), anyString(), eq("page.revision.discarded"), eq("live"));

        service.submit(reviewed, "draft");
        service.withdraw(reviewed, "draft");
        assertFalse(pending.exists(SITE, "draft"));
        assertEquals("<p>DRAFT</p>", writer.readBody(SITE, "draft").orElseThrow(), "the draft stays");
        verify(siteLog).audit(eq(SITE), anyString(), eq("page.review.withdrawn"), eq("draft"));

        service.withdraw(reviewed, "live"); // nothing pending: no-op, no journal line
        verify(siteLog, never()).audit(eq(SITE), anyString(), eq("page.review.withdrawn"), eq("live"));
    }

    @Test
    void leavingTheReviewedLevelLeavesPendingWorkActionable() {
        service.save(reviewed, page("live", "/live", false, "P"), "<p>PROPOSED</p>", null, false, null);
        // The site drops to advisory: the queue still holds the item and an approver can still approve it.
        assertEquals(1, service.queue(advisory).size());
        assertTrue(service.approve(advisory, "live", pending.fingerprint(SITE, "live")).isEmpty());
        assertEquals("<p>PROPOSED</p>", liveBody());
        verify(gate, never()).blocking(any(), any(), any(), any()); // advisory: no gate even on approval
    }

    @Test
    void statusesAreDerivedForTheWholeListInOnePass() {
        service.save(reviewed, page("live", "/live", false, "P"), "<p>P</p>", null, false, null);
        service.submit(reviewed, "draft");
        Map<String, PageStatus> statuses = service.statuses(SITE, pages.findAll(SITE));
        assertEquals("published-pending", statuses.get("live").key());
        assertEquals("in-review", statuses.get("draft").key());
        assertTrue(statuses.get("draft").awaitingReview());
        assertEquals("draft", new PageStatus(false, null, false).key());
        assertEquals("published", new PageStatus(true, null, false).key());
    }

    @Test
    void whoPublishesDirectly() {
        Page live = pages.find(SITE, "live").orElseThrow();
        Page draft = pages.find(SITE, "draft").orElseThrow();
        assertTrue(service.publishesDirectly(advisory, live, false));
        assertTrue(service.publishesDirectly(enforced, live, false));
        assertFalse(service.publishesDirectly(reviewed, live, false));
        assertTrue(service.publishesDirectly(reviewed, live, true));
        assertTrue(service.publishesDirectly(reviewed, draft, false), "a draft is never live");
        assertFalse(service.mayPublish(reviewed, false));
        assertTrue(service.mayPublish(enforced, false));
    }

    // --- re-seed goes through the editorial level ------------------------

    /** A re-seeder that tags the body it was given, so a test can see which body (live or revision) it used. */
    private mx.quest.runway.page.PageReseedService reseeder() {
        mx.quest.runway.page.PageReseedService reseeder = mock(mx.quest.runway.page.PageReseedService.class);
        when(reseeder.reseeded(eq(SITE), any(), anyString())).thenAnswer(invocation -> {
            assertTrue(writer.isPageLockHeldByCurrentThread(SITE, invocation.<Page>getArgument(1).slug()),
                    "the re-seed reads and merges under the page lock");
            String body = invocation.getArgument(2);
            return java.util.Optional.of(body.replace("<p>", "<header>FRESH</header><p>"));
        });
        return reseeder;
    }

    @Test
    void aReviewedReseedByANonApproverBecomesARevisionAndLeavesLiveAlone() {
        java.util.Optional<EditorialService.SaveResult> r = service.reseed(reviewed, "live", false, reseeder());
        assertEquals(EditorialService.Outcome.SAVED_AS_REVISION, r.orElseThrow().outcome());
        assertEquals("<p>LIVE</p>", liveBody(), "live untouched");
        assertEquals("<header>FRESH</header><p>LIVE</p>", pending.revision(SITE, "live").orElseThrow().html());
        assertTrue(pending.review(SITE, "live").orElseThrow().isSubmitted());
        verify(gate, never()).blocking(any(), any(), any(), any());
    }

    @Test
    void aReseedOverAnExistingRevisionRefreshesTheRevisionNotTheLivePage() {
        service.save(reviewed, page("live", "/live", false, "Proposed"), "<p>PROPOSED</p>", null, false, null);
        service.reseed(reviewed, "live", false, reseeder());
        assertEquals("<header>FRESH</header><p>PROPOSED</p>", pending.revision(SITE, "live").orElseThrow().html(),
                "the editor's pending work is re-seeded, not replaced by the live body");
        assertEquals("Proposed", pending.revision(SITE, "live").orElseThrow().page().title().get("en"));
        assertEquals("<p>LIVE</p>", liveBody());
    }

    @Test
    void aReviewedReseedByAnApproverRunsTheGateAndWritesLive() {
        assertEquals(EditorialService.Outcome.SAVED,
                service.reseed(reviewed, "live", true, reseeder()).orElseThrow().outcome());
        assertEquals("<header>FRESH</header><p>LIVE</p>", liveBody());
        verify(gate).blocking(eq(reviewed), any(), eq("<header>FRESH</header><p>LIVE</p>"), any());
    }

    @Test
    void anEnforcedReseedWithErrorsIsBlockedAndWritesNothing() {
        when(gate.blocking(any(), any(), any(), any())).thenReturn(ERRORS);
        EditorialService.SaveResult r = service.reseed(enforced, "live", false, reseeder()).orElseThrow();
        assertEquals(EditorialService.Outcome.BLOCKED, r.outcome());
        assertEquals(ERRORS, r.blocking());
        assertEquals("<p>LIVE</p>", liveBody());
        assertFalse(pending.exists(SITE, "live"));
    }

    @Test
    void anAdvisoryReseedWritesLiveAndAnInSyncPageWritesNothing() {
        assertEquals(EditorialService.Outcome.SAVED,
                service.reseed(advisory, "live", false, reseeder()).orElseThrow().outcome());
        assertEquals("<header>FRESH</header><p>LIVE</p>", liveBody());
        verify(gate, never()).blocking(any(), any(), any(), any());

        mx.quest.runway.page.PageReseedService inSync = mock(mx.quest.runway.page.PageReseedService.class);
        when(inSync.reseeded(any(), any(), any())).thenReturn(java.util.Optional.empty());
        assertTrue(service.reseed(reviewed, "live", false, inSync).isEmpty());
        assertFalse(pending.exists(SITE, "live"), "nothing written, nothing submitted");
        assertEquals("no-such-page", assertThrows(EditorialException.class,
                () -> service.reseed(advisory, "ghost", true, inSync)).code());
    }

    // --- unpublish is serialized with approval and save ---------------------------

    /**
     * An unpublish that lands while an approval is running (here: parked inside the quality gate) used to complete
     * at once, unlocked, and the approval then rewrote the meta with the draft flag it had read before — the page
     * was live again while the audit log said {@code page.unpublished}. Now the unpublish waits for the page lock
     * and applies after the approval.
     */
    @Test
    void anUnpublishDuringAnApprovalWaitsForItAndIsNotUndone() throws Exception {
        service.save(reviewed, page("live", "/live", false, "Proposed"), "<p>PROPOSED</p>", null, false, null);
        String key = pending.fingerprint(SITE, "live");
        java.util.concurrent.CountDownLatch inGate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(gate.blocking(any(), any(), any(), any())).thenAnswer(invocation -> {
            inGate.countDown();
            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return List.of();
        });
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<List<Finding>> approval = pool.submit(() -> service.approve(reviewed, "live", key));
            assertTrue(inGate.await(10, java.util.concurrent.TimeUnit.SECONDS), "the approval reached the gate");
            java.util.concurrent.Future<?> unpublish = pool.submit(() -> writer.setDraft(SITE, "live", true));
            Thread.sleep(300);
            assertFalse(unpublish.isDone(), "the unpublish must wait for the page lock the approval holds");
            release.countDown();
            assertTrue(approval.get(10, java.util.concurrent.TimeUnit.SECONDS).isEmpty());
            unpublish.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        Page now = pages.find(SITE, "live").orElseThrow();
        assertTrue(now.draft(), "the unpublish applied after the approval and stands");
        assertEquals("<p>PROPOSED</p>", liveBody(), "the approved content is what is (no longer) published");
    }

    /**
     * The editor reads the draft flag before the save takes the lock; a save must write the flag as it is under the
     * lock, so an unpublish between the two is not undone.
     */
    @Test
    void aSaveCarryingAStaleDraftFlagKeepsTheUnpublish() {
        writer.setDraft(SITE, "live", true); // unpublished after the editor loaded the page as published
        EditorialService.SaveResult r = service.save(advisory, page("live", "/live", false, "Edited"), "<p>EDIT</p>",
                null, false, null);
        assertEquals(EditorialService.Outcome.SAVED, r.outcome());
        assertTrue(pages.find(SITE, "live").orElseThrow().draft(), "still unpublished");
        assertEquals("<p>EDIT</p>", liveBody());
    }
}
