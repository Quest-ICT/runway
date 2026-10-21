package mx.quest.runway.page;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import mx.quest.runway.event.HostWriteScope;
import mx.quest.runway.storage.AtomicFiles;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.TemplateCacheService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The single write/create/delete/rename surface for tenant pages — {@link PageStore} stays a pure reader.
 * Keeping mutation in a focused service (rather than widening the read-only store) matches the kernel's
 * established pattern and gives one <b>choke point</b> that keeps both caches coherent after every change.
 *
 * <p>A page is three files under {@code sites/<siteId>/templates/}: {@code <slug>.meta.yml} (the routable
 * anchor — the only file {@link PageStore} reads), {@code <slug>.html} (the page body), and an optional
 * {@code <slug>.th.xml} (decoupled logic). Two invariants make the multi-file write safe even though
 * {@link AtomicFiles} is per-file:
 *
 * <ol>
 *   <li><b>Meta is written LAST.</b> A page becomes routable only once its meta exists, so a crash between
 *       files can never leave a routable page whose {@code .html} is missing (which would 404). On delete,
 *       meta is removed FIRST, so the page stops routing before its body is gone.</li>
 *   <li><b>Both caches are evicted after every mutation.</b> The {@link PageStore} record cache and the
 *       parsed-template cache ({@link TemplateCacheService#evictPage}) — the latter because the body/logic
 *       parse to one name-keyed entry that would otherwise serve stale HTML. Page edits
 *       use the targeted {@code evictPage}, never a site-wide {@code evictAll} (that is for chrome/master
 *       changes). The eviction is <b>not</b> called inline: every mutation publishes a {@link PageChangedEvent}
 *       and {@link PageCacheInvalidationListener} performs the two evictions — ONE eviction path. Spring events
 *       are synchronous, so the eviction has completed by the time the mutating call returns (the same
 *       "evict-before-return" guarantee as the old inline call), and the same event lets a cluster relay fan the
 *       edit out to peer nodes. Transient preview scratch pages (the reserved {@code rw-preview-} prefix) are
 *       excluded — they are per-request and never cluster-relevant.</li>
 * </ol>
 *
 * <p><b>Pending editorial work.</b> When a site requires review, an editor's change to a published page is kept
 * as a pending revision beside the page ({@link #saveRevision}, stored by {@link PendingRevisionStore}) and the
 * live files are untouched until it is approved. Deleting a page removes its pending work; renaming a page that
 * has any is refused.
 *
 * <p>Every path is resolved through {@link PathSafety#resolveConfined}, so a slug can never traverse out of
 * the tenant's templates directory. There is no {@code @Transactional}: this is the filesystem, not JDBC —
 * atomicity is per-file plus the meta-last/meta-first ordering.
 */
@Component
public class PageWriteService {

    private static final String TEMPLATES_DIR = "templates";
    private static final String META = ".meta.yml";
    private static final String HTML = ".html";
    private static final String THXML = ".th.xml";

    private final StorageLayout layout;
    private final YamlStore store;
    private final PageStore pages;
    private final TemplateCacheService templateCache;
    private final PageAuthoringValidator validator;
    private final ApplicationEventPublisher events;
    private final mx.quest.runway.log.SiteLogService siteLog; // nullable — audits content changes when present
    /** The page's pending editorial work ({@code templates/.pending/<slug>/}) — removed with the page, never renamed. */
    private final PendingRevisionStore pending;
    /** Per-page monitors (striped) so two concurrent mutations of the same page can't interleave their
     *  {@code .html}/{@code .th.xml}/{@code .meta.yml} writes and leave one file from each. */
    private final Object[] pageLocks = createLocks(64);

    /**
     * Bare form for unit tests (no audit sink). A {@code null} publisher makes {@link #evict} fall back to
     * evicting the caches inline, so a test that constructs the service without a Spring context (and thus has
     * no {@link PageCacheInvalidationListener} wired) still sees the exact same eviction it always did.
     */
    public PageWriteService(StorageLayout layout, YamlStore store, PageStore pages,
                            TemplateCacheService templateCache, PageAuthoringValidator validator) {
        this(layout, store, pages, templateCache, validator, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PageWriteService(StorageLayout layout, YamlStore store, PageStore pages,
                            TemplateCacheService templateCache, PageAuthoringValidator validator,
                            ApplicationEventPublisher events,
                            mx.quest.runway.log.SiteLogService siteLog) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.store = Objects.requireNonNull(store, "store");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.templateCache = Objects.requireNonNull(templateCache, "templateCache");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.events = events;
        this.siteLog = siteLog;
        this.pending = new PendingRevisionStore(layout, store);
    }

    /** The store of pending editorial work (review state and pending revisions) for the pages this service writes. */
    public PendingRevisionStore pending() {
        return pending;
    }

    /**
     * Runs {@code action} holding the same per-page monitor every mutation of {@code (siteId, slug)} in this service
     * takes, so a multi-step editorial operation (check the review state, run the quality gate, write the live
     * files, drop the revision) cannot interleave with a concurrent save, delete or approval of the same page.
     * The monitor is re-entrant: {@code action} may call this service's own mutators for the same page.
     *
     * @return whatever {@code action} returns
     */
    public <T> T withPageLock(String siteId, String slug, java.util.function.Supplier<T> action) {
        // The write scope wraps the monitor: plugin event callbacks raised inside are delivered once it is released
        // (HostWriteScope), still before this call returns.
        try (HostWriteScope scope = HostWriteScope.enter()) {
            synchronized (lockFor(siteId, slug)) {
                return action.get();
            }
        }
    }

    /**
     * Whether the current thread holds the write monitor of {@code (siteId, slug)} — a test hook for the guarantee
     * that plugin event callbacks never run under a page lock. The monitors are striped, so this answers
     * for the stripe: {@code false} is exact, {@code true} may be a sibling page sharing it.
     */
    public boolean isPageLockHeldByCurrentThread(String siteId, String slug) {
        return Thread.holdsLock(lockFor(siteId, slug));
    }

    /**
     * Saves a <b>pending revision</b> of a published page — the live files are not touched, no page-changed event
     * fires and no cache is evicted, so the public page, its ETag and the sitemap stay exactly as they were. The
     * revision is validated exactly like a live save (route collision, unsafe path, missing master), so approving
     * it later cannot introduce a routing fault. Not audited here: the caller (the editorial workflow) journals
     * the submission.
     *
     * @throws PageAuthoringException if the revision fails the same validation a live save would, or the page
     *                                does not exist
     */
    public void saveRevision(String siteId, Page page, String html, String thxml) {
        String slug = requireSlug(page);
        Objects.requireNonNull(html, "html");
        if (pages.find(siteId, slug).isEmpty()) {
            throw new PageAuthoringException("no-such-page", "no such page: " + slug);
        }
        validator.validate(siteId, page);
        requireMasterIfLogic(templatesDir(siteId), page, thxml);
        synchronized (lockFor(siteId, slug)) {
            pending.writeRevision(siteId, page, html, thxml);
        }
    }

    /**
     * Writes (creates or overwrites) a page: validates, writes {@code .html} then {@code .th.xml} (or
     * removes a now-absent {@code .th.xml}), writes {@code .meta.yml} LAST, then evicts both caches.
     *
     * @param page the page to persist; its {@link Page#slug()} is the identity (must be set)
     * @param html the page body ({@code <slug>.html}); required
     * @param thxml the decoupled logic ({@code <slug>.th.xml}); {@code null}/blank removes it (a bare page)
     * @throws PageAuthoringException if the page fails validation (its master is missing, its slug/path is
     *                                unsafe or reserved, or it collides with a sibling's route)
     */
    public void save(String siteId, Page page, String html, String thxml) {
        String slug = requireSlug(page);
        Objects.requireNonNull(html, "html");
        validator.validate(siteId, page);
        Path templates = templatesDir(siteId);
        requireMasterIfLogic(templates, page, thxml);
        doWrite(siteId, slug, templates, page, html, thxml);
        audit(siteId, slug, "page.saved");
    }

    /**
     * Creates a new page, refusing to overwrite an existing one (its meta already present).
     *
     * @throws PageAuthoringException if a page with this slug already exists, or validation fails
     */
    public void create(String siteId, Page page, String html, String thxml) {
        String slug = requireSlug(page);
        if (Files.exists(resolve(templatesDir(siteId), slug + META))) {
            throw new PageAuthoringException("already-exists", "a page already exists: " + slug);
        }
        save(siteId, page, html, thxml);
    }

    /** Whether the page has pending editorial work: it is in review, or has a pending revision. */
    public boolean hasPendingWork(String siteId, String slug) {
        return PathSafety.isSafeId(siteId) && PathSafety.isSafeId(slug) && pending.exists(siteId, slug);
    }

    /**
     * Deletes a page's three files, meta FIRST (so it stops routing immediately), then evicts both caches.
     *
     * @return {@code true} if a meta file was present and removed
     */
    public boolean delete(String siteId, String slug) {
        if (!PathSafety.isSafeId(siteId) || !PathSafety.isSafeId(slug)) {
            return false;
        }
        Path templates = templatesDir(siteId);
        boolean removed;
        // The write scope wraps the monitor (HostWriteScope): plugin callbacks run after it is released.
        try (HostWriteScope scope = HostWriteScope.enter()) {
            synchronized (lockFor(siteId, slug)) { // serialize with a concurrent save of the same page
                try {
                    removed = store.delete(resolve(templates, slug + META)); // meta first — page stops routing
                    Files.deleteIfExists(resolve(templates, slug + HTML));
                    Files.deleteIfExists(resolve(templates, slug + THXML));
                    if (!slug.startsWith(PageAuthoringValidator.SCRATCH_SLUG_PREFIX)) {
                        pending.discard(siteId, slug); // a deleted page's review and pending revision go with it
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("deleting page " + siteId + "/" + slug, e);
                }
                evict(siteId, slug);
            }
            if (removed) {
                audit(siteId, slug, "page.deleted");
            }
        }
        return removed;
    }

    /**
     * Records a content change to the site's audit log — actor from the current security context, scratch
     * preview pages skipped. No-op when there is no audit sink (unit tests) or the change was a preview.
     */
    private void audit(String siteId, String slug, String action) {
        if (siteLog == null || slug.startsWith(PageAuthoringValidator.SCRATCH_SLUG_PREFIX)) {
            return;
        }
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        String actor = authentication != null ? authentication.getName() : "system";
        siteLog.audit(siteId, actor, action, slug);
    }

    /**
     * Renames a page ({@code create}-new + {@code delete}-old): copies the body/logic to {@code toSlug},
     * re-pointing the default path when it was the derived {@code /<fromSlug>} (a custom path is kept), then
     * removes the old files. Two-phase and meta-last within each phase.
     *
     * @throws PageAuthoringException if the source is missing, the target exists, or {@code toSlug} is unsafe
     */
    public void rename(String siteId, String fromSlug, String toSlug) {
        Page from = pages.find(siteId, fromSlug)
                .orElseThrow(() -> new PageAuthoringException("no-such-page", "no page to rename: " + fromSlug));
        if (toSlug == null || !PathSafety.isSafeId(toSlug)
                || toSlug.startsWith(PageAuthoringValidator.SCRATCH_SLUG_PREFIX)) {
            throw new PageAuthoringException("unsafe-slug", "unsafe or reserved target id: " + toSlug);
        }
        if (Files.exists(resolve(templatesDir(siteId), toSlug + META))) {
            throw new PageAuthoringException("already-exists", "a page already exists: " + toSlug);
        }
        // Conservative: a page with pending editorial work (in review, or a pending revision) is not renamed. The
        // review is about a page at an address; moving it underneath an approver would leave the queue pointing at
        // nothing. Approve, send back and discard, or withdraw first.
        if (pending.exists(siteId, fromSlug)) {
            throw new PageAuthoringException("review-pending",
                    "the page has pending editorial work; resolve it before renaming: " + fromSlug);
        }
        String html = readBody(siteId, fromSlug)
                .orElseThrow(() -> new PageAuthoringException("no-such-page", "page body missing: " + fromSlug));
        String thxml = readLogic(siteId, fromSlug).orElse(null);
        String path = ("/" + fromSlug).equals(from.path()) ? "/" + toSlug : from.path();
        // A rename is a change of ADDRESS, not of authorship — everything else travels with the page. Done
        // through the record's own transformation rather than rebuilt field by field here, because a rebuild
        // at this distance is how authorship was silently dropped once already.
        Page renamed = from.renamedTo(toSlug, path);
        // Remove the old page FIRST: a rename preserves the page's route, so the renamed copy would collide
        // with the still-present original on that shared path. If writing the renamed page then fails,
        // restore the original so a failed rename never loses content. One write scope over both steps: plugin
        // listeners see the rename's two changes after it completed, never the page-less moment in between.
        try (HostWriteScope scope = HostWriteScope.enter()) {
            delete(siteId, fromSlug);
            try {
                save(siteId, renamed, html, thxml);
            } catch (RuntimeException e) {
                doWrite(siteId, fromSlug, templatesDir(siteId), from, html, thxml);
                throw e;
            }
        }
    }

    /**
     * Writes a transient <b>preview scratch</b> page, bypassing authoring validation — so a live preview
     * renders exactly what the author typed (a missing master, an unsaved draft, and all). The slug MUST be
     * under the reserved {@link PageAuthoringValidator#SCRATCH_SLUG_PREFIX}, so this can never write a real
     * routable page; callers make it unique per request and {@link #delete} it in a {@code finally}. Traversal
     * safety and cache eviction still apply.
     *
     * @throws PageAuthoringException if the slug is not under the reserved scratch prefix
     */
    public void writeScratch(String siteId, Page page, String html, String thxml) {
        String slug = requireSlug(page);
        Objects.requireNonNull(html, "html");
        if (!slug.startsWith(PageAuthoringValidator.SCRATCH_SLUG_PREFIX)) {
            throw new PageAuthoringException("reserved-slug",
                    "a scratch page must use the reserved '" + PageAuthoringValidator.SCRATCH_SLUG_PREFIX + "' prefix");
        }
        doWrite(siteId, slug, templatesDir(siteId), page, html, thxml);
    }

    /**
     * Flips a page's draft/published state, rewriting only its {@code .meta.yml} (the body/logic are
     * untouched) and evicting the caches. Publishing ({@code draft=false}) re-validates so it can never
     * introduce a route collision with a sibling; a no-op when already in the requested state. A real flip is
     * recorded in the site's audit log as {@code page.published} / {@code page.unpublished} (a no-op is not).
     *
     * <p>The read-modify-write of the meta runs under the page's write monitor ({@link #withPageLock}), like every
     * other mutation here: an unpublish that arrives while an approval (or a save) of the same page is in progress
     * waits for it and then applies, instead of being silently overwritten by that operation's meta write, which
     * would leave the page live while the audit log said {@code page.unpublished}.
     *
     * @throws PageAuthoringException if the page is missing, or publishing would collide with a sibling route
     */
    public void setDraft(String siteId, String slug, boolean draft) {
        boolean flipped = withPageLock(siteId, slug, () -> {
            Page page = pages.find(siteId, slug)
                    .orElseThrow(() -> new PageAuthoringException("no-such-page", "no such page: " + slug));
            if (page.draft() == draft) {
                return false;
            }
            // Same rule as rename: toggling visibility changes visibility and nothing else.
            Page updated = page.withDraft(draft);
            if (!draft) {
                validator.validate(siteId, updated); // publishing must not create an ambiguous route
            }
            try {
                store.write(resolve(templatesDir(siteId), slug + META), updated.withoutSlug());
            } catch (IOException e) {
                throw new UncheckedIOException("setting draft on " + siteId + "/" + slug, e);
            }
            evict(siteId, slug);
            return true;
        });
        if (flipped) {
            audit(siteId, slug, draft ? "page.unpublished" : "page.published");
        }
    }

    /** The raw {@code <slug>.html} source (for the editor), or empty if absent. */
    public Optional<String> readBody(String siteId, String slug) {
        return readSource(siteId, slug, HTML);
    }

    /** The raw {@code <slug>.th.xml} source (for the editor), or empty if the page has no decoupled logic. */
    public Optional<String> readLogic(String siteId, String slug) {
        return readSource(siteId, slug, THXML);
    }

    // --- internals ---------------------------------------------------------------------------------

    private void doWrite(String siteId, String slug, Path templates, Page page, String html, String thxml) {
        // Serialize per page: two authors saving the same slug at once would otherwise interleave the three
        // atomic renames and commit e.g. writer A's body with writer B's meta. meta stays LAST — the routable
        // anchor PageStore reads, so a page never becomes routable before its body/logic are in place. The write
        // scope wraps the monitor (HostWriteScope): plugin callbacks run after it is released.
        try (HostWriteScope scope = HostWriteScope.enter()) {
            synchronized (lockFor(siteId, slug)) {
                try {
                    Files.createDirectories(templates);
                    AtomicFiles.write(resolve(templates, slug + HTML), html.getBytes(StandardCharsets.UTF_8));
                    Path logic = resolve(templates, slug + THXML);
                    if (thxml != null && !thxml.isBlank()) {
                        AtomicFiles.write(logic, thxml.getBytes(StandardCharsets.UTF_8));
                    } else {
                        Files.deleteIfExists(logic);
                    }
                    store.write(resolve(templates, slug + META), page.withoutSlug()); // meta LAST — the anchor
                } catch (IOException e) {
                    throw new UncheckedIOException("writing page " + siteId + "/" + slug, e);
                }
                evict(siteId, slug);
            }
        }
    }

    private static Object[] createLocks(int stripes) {
        Object[] locks = new Object[stripes];
        for (int i = 0; i < stripes; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    /** The stripe monitor guarding mutations of {@code (siteId, slug)} — same key ⇒ same monitor. */
    private Object lockFor(String siteId, String slug) {
        return pageLocks[Math.floorMod(PathSafety.compoundKey(siteId, slug).hashCode(), pageLocks.length)];
    }

    /** A page that carries decoupled logic pulls its master's chrome seams, so the master must exist. */
    private void requireMasterIfLogic(Path templates, Page page, String thxml) {
        if (thxml == null || thxml.isBlank()) {
            return; // a bare/standalone page pulls no master
        }
        Path masterHtml = PathSafety.resolveConfined(templates, page.template() + HTML);
        if (!Files.isRegularFile(masterHtml)) {
            throw new PageAuthoringException("missing-master",
                    "the master '" + page.template() + "' does not exist");
        }
    }

    private Optional<String> readSource(String siteId, String slug, String suffix) {
        if (!PathSafety.isSafeId(siteId) || !PathSafety.isSafeId(slug)) {
            return Optional.empty();
        }
        Path file = resolve(templatesDir(siteId), slug + suffix);
        try {
            return Files.isRegularFile(file)
                    ? Optional.of(Files.readString(file, StandardCharsets.UTF_8))
                    : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("reading source " + siteId + "/" + slug + suffix, e);
        }
    }

    /**
     * Drops the mutated page from both caches by publishing a {@link PageChangedEvent} —
     * {@link PageCacheInvalidationListener} performs the eviction, so there is ONE eviction path (which a
     * cluster relay can also observe to fan the edit out to peer nodes). Publication is synchronous, so the
     * eviction has completed before this returns.
     *
     * <p>Two carve-outs preserve exact prior behaviour: (1) a transient preview scratch page (the reserved
     * {@code rw-preview-} prefix) is never broadcast — it is per-request, listing-excluded and not
     * cluster-relevant, but its caches are still evicted inline so a fresh preview never serves a stale parse;
     * (2) when there is no publisher (the bare unit-test constructor, which has no listener wired) the eviction
     * falls back to the inline calls the write path used before — same effect, no event.
     */
    private void evict(String siteId, String slug) {
        if (events == null || slug.startsWith(PageAuthoringValidator.SCRATCH_SLUG_PREFIX)) {
            pages.evict(siteId, slug);
            templateCache.evictPage(siteId, slug);
            return;
        }
        events.publishEvent(new PageChangedEvent(siteId, slug));
    }

    private Path templatesDir(String siteId) {
        if (!PathSafety.isSafeId(siteId)) {
            throw new PageAuthoringException("unsafe-slug", "unsafe tenancy id: " + siteId);
        }
        return layout.siteDir(siteId).resolve(TEMPLATES_DIR);
    }

    private static Path resolve(Path templates, String fileName) {
        return PathSafety.resolveConfined(templates, fileName);
    }

    private static String requireSlug(Page page) {
        Objects.requireNonNull(page, "page");
        if (page.slug() == null) {
            throw new PageAuthoringException("unsafe-slug", "the page has no slug");
        }
        return page.slug();
    }
}
