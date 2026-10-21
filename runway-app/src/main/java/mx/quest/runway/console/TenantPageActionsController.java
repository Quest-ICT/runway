package mx.quest.runway.console;

import java.util.List;
import java.util.stream.Collectors;
import mx.quest.runway.authz.AuthzExpressions;
import mx.quest.runway.editorial.EditorialException;
import mx.quest.runway.editorial.EditorialService;
import mx.quest.runway.page.PageAuthoringException;
import mx.quest.runway.page.PageReseedService;
import mx.quest.runway.page.PageWriteService;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.spi.model.Finding;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The tenant page mutating <b>actions</b> that are not content edits: publish/unpublish (disable/enable) and
 * re-seed. Kept off the editor controller so their distinct permission gates read cleanly.
 *
 * <p><b>Visibility is {@code DISABLE_PAGES}, content is {@code EDIT_PAGES}.</b> Disable/enable flip a page's
 * draft/published state (its public visibility), so they require {@code DISABLE_PAGES} — separate from the
 * {@code EDIT_PAGES} that authors content, so a content editor cannot publish. Re-seed only refreshes the
 * page's offline chrome copy (never what visitors see, which is pulled live from the master), so it is an
 * {@code EDIT_PAGES} action. All are bound-tenancy only.
 *
 * <p><b>Publishing follows the site's editorial level</b> ({@link EditorialService#publish}): on an enforced or
 * reviewed site the quality gate runs first and an {@code ERROR} refuses it (the findings are flashed); on a
 * reviewed site a user without {@code APPROVE_PAGES} is refused even with {@code DISABLE_PAGES} — they submit for
 * review instead. Unpublishing is never gated by the level: taking a page down needs no second opinion.
 */
@Controller
public class TenantPageActionsController {

    private final SiteResolver siteResolver;
    private final PageWriteService writer;
    private final PageReseedService reseedService;
    private final MessageSource messages;
    private final EditorialService editorial;
    private final AuthzExpressions authz;
    private final mx.quest.runway.page.PageStore pages;

    public TenantPageActionsController(SiteResolver siteResolver, PageWriteService writer,
                                       PageReseedService reseedService,
                                       @Qualifier("operatorAwareMessageSource") MessageSource messages,
                                       EditorialService editorial, AuthzExpressions authz,
                                       mx.quest.runway.page.PageStore pages) {
        this.pages = pages;
        this.editorial = editorial;
        this.authz = authz;
        this.siteResolver = siteResolver;
        this.writer = writer;
        this.reseedService = reseedService;
        this.messages = messages;
    }

    /** Unpublish a page (make it a draft). */
    @PostMapping("/__admin/pages/{slug}/disable")
    @PreAuthorize("@authz.may('DISABLE_PAGES')")
    public String disable(@PathVariable String slug, RedirectAttributes redirect) {
        Site site = boundTenancy();
        try {
            writer.setDraft(site.id(), slug, true);
        } catch (PageAuthoringException ex) {
            redirect.addFlashAttribute("error", message("op.pages.error." + ex.code(), ex.getMessage()));
        }
        return "redirect:/__admin/pages";
    }

    /** Publish a page (clear its draft flag). */
    @PostMapping("/__admin/pages/{slug}/enable")
    @PreAuthorize("@authz.may('DISABLE_PAGES')")
    public String enable(@PathVariable String slug, RedirectAttributes redirect) {
        Site site = boundTenancy();
        try {
            List<Finding> blocking = editorial.publish(site, slug, authz.may("APPROVE_PAGES"));
            if (!blocking.isEmpty()) {
                redirect.addFlashAttribute("error", blockedMessage(blocking));
            }
        } catch (PageAuthoringException ex) {
            redirect.addFlashAttribute("error", message("op.pages.error." + ex.code(), ex.getMessage()));
        } catch (EditorialException ex) {
            redirect.addFlashAttribute("error", message("op.review.error." + ex.code(), ex.getMessage()));
        }
        return "redirect:/__admin/pages";
    }

    /** "Not published: ..." followed by each blocking finding's localized message. */
    private String blockedMessage(List<Finding> blocking) {
        java.util.Locale ui = LocaleContextHolder.getLocale();
        String reasons = blocking.stream()
                .map(f -> messages.getMessage("quality." + f.code(), null, f.message(), ui))
                .collect(Collectors.joining(" · "));
        return message("op.review.blocked.publish", "Not published: the page has quality errors.") + " " + reasons;
    }

    /**
     * Refresh the page's chrome copy from its master, then return to the editor. The write follows the site's
     * editorial level like an editor save ({@link EditorialService#reseed}): a pending revision for a non-approver
     * on a reviewed site, the quality gate on an enforced or reviewed one.
     */
    @PostMapping("/__admin/pages/{slug}/reseed")
    @PreAuthorize("@authz.may('EDIT_PAGES')")
    public String reseed(@PathVariable String slug, RedirectAttributes redirect) {
        Site site = boundTenancy();
        try {
            java.util.Optional<EditorialService.SaveResult> result =
                    editorial.reseed(site, slug, authz.may("APPROVE_PAGES"), reseedService);
            if (result.isEmpty()) {
                redirect.addFlashAttribute("flash", message("op.pages.reseed.nochange", "Already in sync."));
            } else if (result.get().outcome() == EditorialService.Outcome.BLOCKED) {
                String reasons = result.get().blocking().stream()
                        .map(f -> messages.getMessage("quality." + f.code(), null, f.message(),
                                LocaleContextHolder.getLocale()))
                        .collect(Collectors.joining(" · "));
                redirect.addFlashAttribute("error", message("op.pages.reseed.blocked",
                        "Not re-seeded: the page is published and the result has quality errors.") + " " + reasons);
            } else {
                redirect.addFlashAttribute("flash", message(
                        result.get().outcome() == EditorialService.Outcome.SAVED_AS_REVISION
                                ? "op.pages.reseed.revision" : "op.pages.reseed.done", "Re-seeded."));
            }
        } catch (PageAuthoringException ex) {
            redirect.addFlashAttribute("error", message("op.pages.error." + ex.code(), ex.getMessage()));
        } catch (EditorialException ex) {
            redirect.addFlashAttribute("error", message("op.review.error." + ex.code(), ex.getMessage()));
        }
        return "redirect:/__admin/pages/" + slug + "/edit";
    }

    /**
     * Renames a page: its id (the name of its files, and how templates link to it with {@code ${urls['id']}}).
     * {@link PageWriteService#rename} moves the files, keeps a custom path and re-derives {@code /<id>}, and refuses
     * while the page has pending review work.
     *
     * <p>{@code EDIT_PAGES} renames. When the page is <b>published</b> and its path follows the id, the rename also
     * moves its public address, and the old one stops answering: that is a visibility change, so it needs
     * {@code DISABLE_PAGES} as well, and {@code APPROVE_PAGES} on a site that requires review — the same as
     * publishing. A draft, or a page with its own path, changes no public address.
     */
    @PostMapping("/__admin/pages/{slug}/rename")
    @PreAuthorize("@authz.may('EDIT_PAGES')")
    public String rename(@PathVariable String slug, @RequestParam(required = false) String to,
                         RedirectAttributes redirect) {
        Site site = boundTenancy();
        String target = to == null ? "" : to.trim();
        try {
            mx.quest.runway.page.Page page = pages.find(site.id(), slug)
                    .orElseThrow(() -> new PageAuthoringException("no-such-page", "no such page: " + slug));
            boolean movesPublicAddress = !page.draft() && ("/" + slug).equals(page.path());
            if (movesPublicAddress && (!authz.may("DISABLE_PAGES")
                    || (site.editorial().requiresReview() && !authz.may("APPROVE_PAGES")))) {
                redirect.addFlashAttribute("error", message("op.pages.rename.needsPublish",
                        "This page is published at /" + slug + ", and renaming it moves that address. Ask someone "
                                + "who may publish pages to rename it."));
                return "redirect:/__admin/pages/" + slug + "/edit";
            }
            if (target.equals(slug)) {
                return "redirect:/__admin/pages/" + slug + "/edit";
            }
            writer.rename(site.id(), slug, target);
            redirect.addFlashAttribute("flash", messages.getMessage("op.pages.rename.done",
                    new Object[] {slug, target}, "Renamed.", LocaleContextHolder.getLocale()));
            return "redirect:/__admin/pages/" + target + "/edit";
        } catch (PageAuthoringException ex) {
            redirect.addFlashAttribute("error", message("op.pages.error." + ex.code(), ex.getMessage()));
            return "redirect:/__admin/pages/" + slug + "/edit";
        }
    }

    /**
     * Deletes a page from its editor. {@code EDIT_PAGES} deletes a draft; deleting a <b>published</b> page takes
     * content off the site, so it also needs {@code DISABLE_PAGES}, and {@code APPROVE_PAGES} on a site that
     * requires review — the same as unpublishing it and more. A page with pending review work is refused, so a
     * review nobody resolved is never thrown away by someone else's delete.
     */
    @PostMapping("/__admin/pages/{slug}/delete")
    @PreAuthorize("@authz.may('EDIT_PAGES')")
    public String delete(@PathVariable String slug, RedirectAttributes redirect) {
        Site site = boundTenancy();
        try {
            mx.quest.runway.page.Page page = pages.find(site.id(), slug)
                    .orElseThrow(() -> new PageAuthoringException("no-such-page", "no such page: " + slug));
            if (!page.draft() && (!authz.may("DISABLE_PAGES")
                    || (site.editorial().requiresReview() && !authz.may("APPROVE_PAGES")))) {
                redirect.addFlashAttribute("error", message("op.pages.delete.needsPublish",
                        "This page is published. Deleting it takes it off the site, which needs the permission to "
                                + "publish pages."));
                return "redirect:/__admin/pages/" + slug + "/edit";
            }
            if (writer.hasPendingWork(site.id(), slug)) {
                redirect.addFlashAttribute("error", message("op.pages.delete.pending",
                        "This page has pending review work. Resolve it before deleting the page."));
                return "redirect:/__admin/pages/" + slug + "/edit";
            }
            writer.delete(site.id(), slug);
            redirect.addFlashAttribute("flash", messages.getMessage("op.pages.delete.done", new Object[] {slug},
                    "Deleted.", LocaleContextHolder.getLocale()));
            return "redirect:/__admin/pages";
        } catch (PageAuthoringException ex) {
            redirect.addFlashAttribute("error", message("op.pages.error." + ex.code(), ex.getMessage()));
            return "redirect:/__admin/pages";
        }
    }

    private String message(String code, String fallback) {
        return messages.getMessage(code, null, fallback, LocaleContextHolder.getLocale());
    }

    private Site boundTenancy() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"));
    }
}
