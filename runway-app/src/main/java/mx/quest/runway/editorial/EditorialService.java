package mx.quest.runway.editorial;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageReseedService;
import mx.quest.runway.page.PageReview;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageWriteService;
import mx.quest.runway.page.PendingRevisionStore;
import mx.quest.runway.quality.QualityGate;
import mx.quest.runway.site.EditorialLevel;
import mx.quest.runway.site.Site;
import mx.quest.runway.spi.model.Finding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * The editorial workflow: the one place that decides, for a site's {@linkplain EditorialLevel editorial level},
 * what saving and publishing a page does — and the review queue that sits between an editor and the live site
 * when the site requires review.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li><b>Advisory</b> — exactly the behaviour before the levels existed: a save writes the live files, publish
 *       flips the draft flag, quality is a report.</li>
 *   <li><b>Enforced</b> — publishing a draft, and saving a page that is published, first renders the content and
 *       runs every quality check ({@link QualityGate}); any {@code ERROR} refuses the action and the findings are
 *       returned to show. Warnings and infos never block. Saving a <em>draft</em> is never checked: a draft is
 *       work in progress.</li>
 *   <li><b>Reviewed</b> — the enforced checks run on every publish, approval and live save, and a user who does
 *       not hold {@code APPROVE_PAGES} cannot publish: they {@linkplain #submit submit} a draft for review, and
 *       their save of a <em>published</em> page becomes a <b>pending revision</b> — stored beside the page,
 *       leaving the live page, its ETag and the sitemap untouched — that is automatically waiting for review.
 *       An approver {@linkplain #approve approves} (the revision replaces the live files; a draft is published) or
 *       {@linkplain #sendBack sends it back} with a note (it stays as the editor's working copy). A user who holds
 *       {@code APPROVE_PAGES} publishes their own changes directly.</li>
 * </ul>
 *
 * <p>Changing the level never moves a page: what is in review stays in review and can still be approved or sent
 * back at any level; the level governs the next save or publish.
 *
 * <h2>Who calls this</h2>
 * The console controllers, which have already enforced the route permission ({@code EDIT_PAGES} to save and
 * submit, {@code DISABLE_PAGES} to publish, {@code APPROVE_PAGES} to approve and send back) and pass in whether
 * the current user holds {@code APPROVE_PAGES} — the one permission whose presence changes what an action means.
 *
 * <h2>Concurrency and audit</h2>
 * Every operation runs under {@link PageWriteService#withPageLock} for its page, so a save, a re-seed, an approval,
 * a publish or unpublish ({@link PageWriteService#setDraft} takes the same lock) and a deletion of the same page
 * are serialized; each reads the page's draft flag under that lock, so none undoes a concurrent unpublish. An approval names the {@linkplain PendingRevisionStore#fingerprint
 * fingerprint} of what the approver looked at and is refused if the content changed since. Each step is written to
 * the site's audit log: {@code page.review.submitted}, {@code page.revision.submitted}, {@code page.review.withdrawn},
 * {@code page.revision.discarded}, {@code page.revision.published} (a user who may write live saved over a
 * pending revision they were editing), {@code page.review.approved}, {@code page.review.returned} (with the note,
 * shortened) and {@code page.quality.blocked} (with the action and the blocking codes).
 */
@Service
public class EditorialService {

    /** How much of a send-back note is copied into the audit log. */
    static final int AUDIT_NOTE_CHARS = 200;

    private final PageStore pages;
    private final PageWriteService writer;
    private final PendingRevisionStore pending;
    private final QualityGate gate;
    private final SiteLogService siteLog; // nullable — unit tests
    private final Clock clock;

    @Autowired
    public EditorialService(PageStore pages, PageWriteService writer, QualityGate gate, SiteLogService siteLog) {
        this(pages, writer, gate, siteLog, Clock.systemUTC());
    }

    /** Full form, for tests that pin the clock. */
    public EditorialService(PageStore pages, PageWriteService writer, QualityGate gate, SiteLogService siteLog,
                            Clock clock) {
        this.pages = Objects.requireNonNull(pages, "pages");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.pending = writer.pending();
        this.gate = Objects.requireNonNull(gate, "gate");
        this.siteLog = siteLog;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What a save did. */
    public enum Outcome {
        /** The live files were written. */
        SAVED,
        /** The live page was left alone; the change is a pending revision waiting for review. */
        SAVED_AS_REVISION,
        /** Refused by the quality gate; nothing was written. */
        BLOCKED
    }

    /**
     * A save's result.
     *
     * @param outcome  what happened
     * @param blocking the blocking findings when {@link Outcome#BLOCKED}, else empty
     */
    public record SaveResult(Outcome outcome, List<Finding> blocking) {
        public SaveResult {
            blocking = List.copyOf(blocking);
        }
    }

    /**
     * One entry of the review queue.
     *
     * @param page        the live page record
     * @param review      its review state (always submitted)
     * @param revision    whether the item is a pending revision of a published page ({@code false}: a first
     *                    publication of a draft)
     * @param fingerprint the fingerprint an approval must present
     */
    public record QueueItem(Page page, PageReview review, boolean revision, String fingerprint) {
    }

    // --- status -------------------------------------------------------------------------------------

    /** The editorial status of one page. */
    public PageStatus status(String siteId, Page page) {
        return new PageStatus(page.published(), pending.review(siteId, page.slug()).orElse(null),
                pending.hasRevision(siteId, page.slug()));
    }

    /** The status of each of {@code list}'s pages, by slug — one directory listing for the whole site. */
    public Map<String, PageStatus> statuses(String siteId, List<Page> list) {
        Set<String> withPending = pending.pendingSlugs(siteId);
        Map<String, PageStatus> out = new LinkedHashMap<>();
        for (Page page : list) {
            out.put(page.slug(), withPending.contains(page.slug())
                    ? status(siteId, page)
                    : new PageStatus(page.published(), null, false));
        }
        return out;
    }

    /**
     * Whether a save by this user writes the live files ({@code true}) or becomes a pending revision. Only a
     * <em>published</em> page on a <em>reviewed</em> site edited by someone <em>without</em> {@code APPROVE_PAGES}
     * goes to review; a draft is not live, so editing it in place changes nothing visitors see.
     */
    public boolean publishesDirectly(Site site, Page page, boolean mayApprove) {
        return !site.editorial().requiresReview() || mayApprove || page.draft();
    }

    /** Whether this user may publish (flip a draft live) at this site's level. */
    public boolean mayPublish(Site site, boolean mayApprove) {
        return !site.editorial().requiresReview() || mayApprove;
    }

    // --- save / publish -----------------------------------------------------------------------------

    /**
     * Saves an edit of an existing page according to the site's level.
     *
     * @param candidate         the edited page (its slug the page's; its draft flag is ignored — a save keeps the
     *                          page's draft/published state as it is when the save takes the page lock)
     * @param html              the edited body
     * @param thxml             the edited logic ({@code null}/blank ⇒ none)
     * @param mayApprove        whether the current user holds {@code APPROVE_PAGES}
     * @param editedRevisionKey the {@linkplain PendingRevisionStore#fingerprint fingerprint} of the pending revision
     *                          the editor was showing when it loaded, or {@code null}/blank when it showed the live
     *                          page. On a direct (live) save the pending revision is dropped only when it is still
     *                          that one — its content is what the user just saved. A revision someone created after
     *                          the editor loaded is kept, so it is never discarded unseen.
     * @throws mx.quest.runway.page.PageAuthoringException when the page fails authoring validation
     * @throws EditorialException {@code no-such-page}
     */
    public SaveResult save(Site site, Page candidate, String html, String thxml, boolean mayApprove,
                           String editedRevisionKey) {
        String slug = candidate.slug();
        return writer.withPageLock(site.id(), slug, () -> {
            Page live = pages.find(site.id(), slug)
                    .orElseThrow(() -> new EditorialException("no-such-page", "no such page: " + slug));
            if (!publishesDirectly(site, live, mayApprove)) {
                // Reviewed site, published page, no approval right: keep the change aside, live untouched.
                writer.saveRevision(site.id(), candidate.withDraft(live.draft()), html, thxml);
                pending.writeReview(site.id(), slug, PageReview.submitted(actor(), clock.instant()));
                audit(site.id(), "page.revision.submitted", slug);
                return new SaveResult(Outcome.SAVED_AS_REVISION, List.of());
            }
            // The draft flag is the page's state NOW, under the lock — not the one the caller read before taking it:
            // an unpublish that landed in between must not be undone by this save.
            Page toWrite = candidate.withDraft(live.draft());
            if (live.published() && site.editorial().enforcesChecks()) {
                List<Finding> blocking = gate.blocking(site, toWrite, html, thxml);
                if (!blocking.isEmpty()) {
                    auditBlocked(site.id(), slug, "save", blocking);
                    return new SaveResult(Outcome.BLOCKED, blocking);
                }
            }
            String revisionKey = pending.hasRevision(site.id(), slug) ? pending.fingerprint(site.id(), slug) : null;
            writer.save(site.id(), toWrite, html, thxml);
            if (revisionKey != null && revisionKey.equals(editedRevisionKey)) {
                // The editor was showing the pending revision and this user may write live: what they saved IS the
                // revision (possibly edited further), so it has now been published and is no longer pending.
                pending.discard(site.id(), slug);
                audit(site.id(), "page.revision.published", slug);
            }
            return new SaveResult(Outcome.SAVED, List.of());
        });
    }

    /**
     * Re-seeds the page's chrome from its master ({@link PageReseedService#reseeded}) and saves the result
     * <b>through the editorial level</b>, exactly as if the user had saved that body in the editor
     * ({@link #save}): on a reviewed site a published page re-seeded by a user without {@code APPROVE_PAGES}
     * becomes a pending revision (live untouched); a published page on an enforced or reviewed site passes the
     * quality gate first; everything runs under the page lock. A re-seed that wrote the live files directly would
     * bypass all three — and for a page without {@code .th.xml} seams that would change what visitors see.
     *
     * <p>When the re-seed will be a revision and one is already pending, the <em>revision's</em> body is re-seeded
     * (and resubmitted), so the editor's pending work is refreshed rather than overwritten by the live page. A user
     * who writes live re-seeds the live page and leaves a pending revision alone.
     *
     * @return the save's result, or empty when the chrome was already in sync (nothing written)
     * @throws EditorialException {@code no-such-page}
     * @throws mx.quest.runway.page.PageAuthoringException {@code missing-master}, or the page fails validation
     */
    public Optional<SaveResult> reseed(Site site, String slug, boolean mayApprove, PageReseedService reseeder) {
        return writer.withPageLock(site.id(), slug, () -> {
            Page live = pages.find(site.id(), slug)
                    .orElseThrow(() -> new EditorialException("no-such-page", "no such page: " + slug));
            Optional<PendingRevisionStore.Revision> revision = publishesDirectly(site, live, mayApprove)
                    ? Optional.empty() : pending.revision(site.id(), slug);
            Page base = revision.map(PendingRevisionStore.Revision::page).orElse(live);
            String body = revision.isPresent() ? revision.get().html() : writer.readBody(site.id(), slug)
                    .orElseThrow(() -> new EditorialException("no-such-page", "page body missing: " + slug));
            String logic = revision.isPresent() ? revision.get().thxml()
                    : writer.readLogic(site.id(), slug).orElse(null);
            Optional<String> reseeded = reseeder.reseeded(site.id(), base, body);
            if (reseeded.isEmpty()) {
                return Optional.<SaveResult>empty();
            }
            String editedRevisionKey = revision.isPresent() ? pending.fingerprint(site.id(), slug) : null;
            return Optional.of(save(site, base, reseeded.get(), logic, mayApprove, editedRevisionKey));
        });
    }

    /**
     * Publishes a draft (the console's Enable action) — after the quality gate on an enforced or reviewed site.
     * A no-op when the page is already published. Clears a first-publication review the page was waiting in.
     *
     * @return the blocking findings (nothing was published), or empty when it was published or already live
     * @throws EditorialException {@code needs-approval} on a reviewed site for a user without
     *                            {@code APPROVE_PAGES}; {@code no-such-page}
     * @throws mx.quest.runway.page.PageAuthoringException when publishing would create a route collision
     */
    public List<Finding> publish(Site site, String slug, boolean mayApprove) {
        if (!mayPublish(site, mayApprove)) {
            throw new EditorialException("needs-approval", "this site requires review before publishing");
        }
        return writer.withPageLock(site.id(), slug, () -> {
            Page page = pages.find(site.id(), slug)
                    .orElseThrow(() -> new EditorialException("no-such-page", "no such page: " + slug));
            if (page.published()) {
                return List.of();
            }
            List<Finding> blocking = gateStored(site, page, "publish");
            if (!blocking.isEmpty()) {
                return blocking;
            }
            writer.setDraft(site.id(), slug, false);
            if (!pending.hasRevision(site.id(), slug)) {
                pending.clearReview(site.id(), slug); // a first publication done directly needs no review any more
            }
            return List.of();
        });
    }

    // --- review -------------------------------------------------------------------------------------

    /**
     * Submits the page for review: a draft (its first publication), or a published page's pending revision that
     * was sent back. Resubmitting something already waiting is harmless (it refreshes who and when).
     *
     * @throws EditorialException {@code not-reviewed} when the site does not require review; {@code
     *                            nothing-to-review} for a published page with no pending revision; {@code
     *                            no-such-page}
     */
    public void submit(Site site, String slug) {
        if (!site.editorial().requiresReview()) {
            throw new EditorialException("not-reviewed", "this site does not use review");
        }
        writer.withPageLock(site.id(), slug, () -> {
            Page page = pages.find(site.id(), slug)
                    .orElseThrow(() -> new EditorialException("no-such-page", "no such page: " + slug));
            boolean revision = pending.hasRevision(site.id(), slug);
            if (page.published() && !revision) {
                throw new EditorialException("nothing-to-review", "the page is live and has no pending changes");
            }
            pending.writeReview(site.id(), slug, PageReview.submitted(actor(), clock.instant()));
            audit(site.id(), revision ? "page.revision.submitted" : "page.review.submitted", slug);
            return null;
        });
    }

    /**
     * Takes the page out of review. A pending revision is <b>discarded</b> (the live page was never touched, so
     * nothing else changes); a draft's submission is withdrawn and the draft stays as it is. A no-op when nothing
     * is pending.
     */
    public void withdraw(Site site, String slug) {
        writer.withPageLock(site.id(), slug, () -> {
            if (pending.hasRevision(site.id(), slug)) {
                pending.discard(site.id(), slug);
                audit(site.id(), "page.revision.discarded", slug);
            } else if (pending.review(site.id(), slug).isPresent()) {
                pending.clearReview(site.id(), slug);
                audit(site.id(), "page.review.withdrawn", slug);
            }
            return null;
        });
    }

    /**
     * Approves what is waiting for review: a pending revision replaces the live files (the page keeps its current
     * published/draft state), or a draft is published. The enforced quality gate runs first on an enforced or
     * reviewed site.
     *
     * @param fingerprint the {@linkplain PendingRevisionStore#fingerprint fingerprint} the approver's screen showed
     * @return the blocking findings (nothing changed), or empty on success
     * @throws EditorialException {@code not-in-review} when nothing is waiting; {@code changed} when the content
     *                            differs from what the approver saw; {@code no-such-page}
     * @throws mx.quest.runway.page.PageAuthoringException when the revision would create a route collision
     */
    public List<Finding> approve(Site site, String slug, String fingerprint) {
        return writer.withPageLock(site.id(), slug, () -> {
            Page live = pages.find(site.id(), slug)
                    .orElseThrow(() -> new EditorialException("no-such-page", "no such page: " + slug));
            requireSubmitted(site.id(), slug);
            if (fingerprint == null || !fingerprint.equals(pending.fingerprint(site.id(), slug))) {
                throw new EditorialException("changed", "the content under review changed; reload it and look again");
            }
            Optional<PendingRevisionStore.Revision> revision = pending.revision(site.id(), slug);
            if (revision.isPresent()) {
                PendingRevisionStore.Revision rev = revision.get();
                Page candidate = rev.page().withDraft(live.draft());
                if (site.editorial().enforcesChecks()) {
                    List<Finding> blocking = gate.blocking(site, candidate, rev.html(), rev.thxml());
                    if (!blocking.isEmpty()) {
                        auditBlocked(site.id(), slug, "approve", blocking);
                        return blocking;
                    }
                }
                writer.save(site.id(), candidate, rev.html(), rev.thxml());
            } else {
                List<Finding> blocking = gateStored(site, live, "approve");
                if (!blocking.isEmpty()) {
                    return blocking;
                }
                writer.setDraft(site.id(), slug, false);
            }
            pending.discard(site.id(), slug);
            audit(site.id(), "page.review.approved", slug);
            return List.of();
        });
    }

    /**
     * Sends what is waiting for review back to its editor with a note. The content is untouched: a revision stays
     * as the editor's working copy, a draft stays a draft, and the note is shown in the editor until they save or
     * resubmit.
     *
     * @throws EditorialException {@code note-required} for a blank note; {@code not-in-review}; {@code
     *                            no-such-page}
     */
    public void sendBack(Site site, String slug, String note) {
        if (note == null || note.isBlank()) {
            throw new EditorialException("note-required", "say what needs to change");
        }
        writer.withPageLock(site.id(), slug, () -> {
            pages.find(site.id(), slug)
                    .orElseThrow(() -> new EditorialException("no-such-page", "no such page: " + slug));
            PageReview review = requireSubmitted(site.id(), slug);
            PageReview returned = review.returned(actor(), clock.instant(), note);
            pending.writeReview(site.id(), slug, returned);
            String shown = returned.note().length() <= AUDIT_NOTE_CHARS ? returned.note()
                    : returned.note().substring(0, AUDIT_NOTE_CHARS) + "…";
            audit(site.id(), "page.review.returned", slug + ": " + shown);
            return null;
        });
    }

    /** Everything waiting for review in the site, oldest submission first. */
    public List<QueueItem> queue(Site site) {
        List<QueueItem> items = new ArrayList<>();
        for (String slug : pending.pendingSlugs(site.id())) {
            Optional<PageReview> review = pending.review(site.id(), slug).filter(PageReview::isSubmitted);
            Optional<Page> page = pages.find(site.id(), slug);
            if (review.isPresent() && page.isPresent()) {
                items.add(new QueueItem(page.get(), review.get(), pending.hasRevision(site.id(), slug),
                        pending.fingerprint(site.id(), slug)));
            }
        }
        items.sort(java.util.Comparator.comparing(
                (QueueItem item) -> item.review().submittedAt() == null ? java.time.Instant.EPOCH
                        : item.review().submittedAt()));
        return items;
    }

    // --- internals ----------------------------------------------------------------------------------

    private PageReview requireSubmitted(String siteId, String slug) {
        return pending.review(siteId, slug).filter(PageReview::isSubmitted)
                .orElseThrow(() -> new EditorialException("not-in-review", "nothing is waiting for review: " + slug));
    }

    /** Runs the gate over a page's stored (live) files when the level enforces checks; audits a block. */
    private List<Finding> gateStored(Site site, Page page, String action) {
        if (!site.editorial().enforcesChecks()) {
            return List.of();
        }
        String html = writer.readBody(site.id(), page.slug()).orElse("");
        String thxml = writer.readLogic(site.id(), page.slug()).orElse(null);
        List<Finding> blocking = gate.blocking(site, page.withDraft(false), html, thxml);
        if (!blocking.isEmpty()) {
            auditBlocked(site.id(), page.slug(), action, blocking);
        }
        return blocking;
    }

    private void auditBlocked(String siteId, String slug, String action, List<Finding> blocking) {
        audit(siteId, "page.quality.blocked", slug + " " + action + ": "
                + blocking.stream().map(Finding::code).distinct().collect(Collectors.joining(",")));
    }

    private void audit(String siteId, String action, String detail) {
        if (siteLog != null) {
            siteLog.audit(siteId, actor(), action, detail);
        }
    }

    private static String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : "system";
    }
}
