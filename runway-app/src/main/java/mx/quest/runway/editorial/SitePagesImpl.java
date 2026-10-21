package mx.quest.runway.editorial;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import mx.quest.runway.authz.Permissions;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageAuthoringException;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageWriteService;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.InstallResult;
import mx.quest.runway.spi.model.InstallResult.Outcome;
import mx.quest.runway.spi.model.StarterPage;
import mx.quest.runway.spi.service.AccessCheck;
import mx.quest.runway.spi.service.SitePages;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.web.ReservedPaths;
import org.springframework.stereotype.Component;

/**
 * The kernel's {@link SitePages}: a plugin's starter page, installed through the same write path and editorial
 * workflow a person in the console goes through, so a plugin can never publish around a site's rules.
 *
 * <h2>Why "create a draft, then publish or submit"</h2>
 * {@link EditorialService} has no create operation — the console creates pages as drafts and then publishes or
 * submits them — so installing does the same two steps: {@link PageWriteService#create} as a draft (validated like
 * any page: safe path, no route collision, the master present), then by the site's level
 * {@link EditorialService#publish} (which runs the quality gate where the level enforces it) or
 * {@link EditorialService#submit} (a Reviewed site, a principal without {@code APPROVE_PAGES}). The whole sequence
 * holds the page's write lock ({@link PageWriteService#withPageLock}, re-entrant), so two installs of the same page —
 * two operators clicking at once — cannot both create it, and nobody edits it between the two steps.
 *
 * <p>Who did it is the current principal (it is the console request that asked); off a request the audit log says
 * {@code system}, and on a Reviewed site the page goes to review, since nobody holds {@code APPROVE_PAGES}.
 */
@Component
public class SitePagesImpl implements SitePages {

    private final SiteStore sites;
    private final PageStore pages;
    private final PageWriteService writer;
    private final EditorialService editorial;
    private final AccessCheck access;

    public SitePagesImpl(SiteStore sites, PageStore pages, PageWriteService writer, EditorialService editorial,
                         AccessCheck access) {
        this.sites = Objects.requireNonNull(sites, "sites");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.editorial = Objects.requireNonNull(editorial, "editorial");
        this.access = Objects.requireNonNull(access, "access");
    }

    @Override
    public Optional<String> pathOf(String siteId, String slug) {
        requireSafeId("site id", siteId);
        requireSafeId("slug", slug);
        return pages.find(siteId, slug).map(SitePagesImpl::defaultPath);
    }

    @Override
    public InstallResult install(String siteId, StarterPage starter) {
        requireSafeId("site id", siteId);
        Objects.requireNonNull(starter, "page");
        String slug = starter.slug();
        requireSafeId("slug", slug);
        String path = starter.path();
        if (!PathSafety.isSafePagePath(path) || ReservedPaths.atEnumerationTime(path)) {
            throw new IllegalArgumentException("unsafe or reserved page path: " + path);
        }
        Site site = sites.find(siteId).orElseThrow(() -> new IllegalStateException("no such site: " + siteId));
        return writer.withPageLock(siteId, slug, () -> {
            Optional<Page> existing = pages.find(siteId, slug);
            if (existing.isPresent()) {
                return new InstallResult(Outcome.ALREADY_EXISTS, defaultPath(existing.get()), List.of());
            }
            Page draft = new Page(slug, path, Map.of(), List.of(), titles(starter, site), Map.of(), "", "_master",
                    true, starter.noindex(), null, null);
            try {
                writer.create(siteId, draft, starter.html(), starter.logic());
            } catch (PageAuthoringException refused) {
                if ("already-exists".equals(refused.code())) { // created by hand since the check: still not ours
                    return new InstallResult(Outcome.ALREADY_EXISTS,
                            pages.find(siteId, slug).map(SitePagesImpl::defaultPath).orElse(path), List.of());
                }
                throw new IllegalStateException("the page '" + slug + "' could not be created: "
                        + refused.getMessage(), refused);
            } catch (RuntimeException failed) {
                throw new IllegalStateException("the page '" + slug + "' could not be written", failed);
            }
            return applyEditorialLevel(site, slug, path);
        });
    }

    /**
     * Step 3 of the contract: publish (gated where the level enforces checks) or submit for review. If that step
     * itself fails — publishing finds a route collision only a live page has, say — the draft just created is
     * removed again, so a failed install leaves nothing behind for an operator to wonder about.
     */
    private InstallResult applyEditorialLevel(Site site, String slug, String path) {
        boolean mayApprove = access.may(Permissions.APPROVE_PAGES.key());
        try {
            if (editorial.mayPublish(site, mayApprove)) {
                List<Finding> blocking = editorial.publish(site, slug, mayApprove);
                return blocking.isEmpty()
                        ? new InstallResult(Outcome.PUBLISHED, path, List.of())
                        : new InstallResult(Outcome.DRAFT_BLOCKED, path, blocking);
            }
            editorial.submit(site, slug);
            return new InstallResult(Outcome.PENDING_REVIEW, path, List.of());
        } catch (RuntimeException refused) {
            writer.delete(site.id(), slug); // also removes any pending work the submit step began
            throw new IllegalStateException("the page '" + slug + "' could not be published or submitted, so it was "
                    + "not installed: " + refused.getMessage(), refused);
        }
    }

    /** The titles as given; with none, the slug under the site's default language, so the page is never untitled. */
    private static Map<String, String> titles(StarterPage starter, Site site) {
        if (!starter.titles().isEmpty()) {
            return starter.titles();
        }
        Locale language = site.defaultLocale();
        return Map.of(language.getLanguage(), starter.slug());
    }

    private static String defaultPath(Page page) {
        return page.path() != null ? page.path() : "/" + page.slug();
    }

    private static void requireSafeId(String what, String value) {
        if (value == null || !PathSafety.isSafeId(value)) {
            throw new IllegalArgumentException("unsafe " + what + ": " + value);
        }
    }
}
