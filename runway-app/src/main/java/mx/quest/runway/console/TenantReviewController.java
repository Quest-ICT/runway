package mx.quest.runway.console;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import mx.quest.runway.editorial.EditorialException;
import mx.quest.runway.editorial.EditorialService;
import mx.quest.runway.editorial.LineDiff;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageAuthoringException;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.page.PageWriteService;
import mx.quest.runway.page.PendingRevisionStore;
import mx.quest.runway.quality.QualityGate;
import mx.quest.runway.quality.QualityReport;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.spi.model.Finding;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The tenant console's <b>editorial review</b> surface, for sites at the {@code reviewed} level (it also works on
 * whatever is still waiting after a site leaves that level):
 *
 * <ul>
 *   <li>{@code GET /__admin/review} — the <b>queue</b>: drafts submitted for their first publication and pending
 *       revisions of published pages, oldest first ({@code APPROVE_PAGES});</li>
 *   <li>{@code GET /__admin/review/{slug}} — one item: what changes (metadata table and a line diff of the body and
 *       logic against the live page), the live and proposed renders side by side, the quality findings of the
 *       proposed content, and the Approve / Send back actions ({@code APPROVE_PAGES});</li>
 *   <li>{@code POST /__admin/review/{slug}/approve} and {@code …/return} — the approver's two answers
 *       ({@code APPROVE_PAGES});</li>
 *   <li>{@code POST /__admin/pages/{slug}/submit} and {@code …/withdraw} — the editor's side: submit a draft (or
 *       a returned revision) for review, or take it back out ({@code EDIT_PAGES}).</li>
 * </ul>
 *
 * <p>Every route is method-gated and acts on the bound tenancy only ({@link SiteResolver#current()}), never a
 * path or parameter id, so one site's approver can never see or act on another site's queue. The rules
 * themselves live in {@link EditorialService}; this controller only maps them to screens and messages.
 */
@Controller
public class TenantReviewController {

    private final SiteResolver siteResolver;
    private final PageStore pages;
    private final PageWriteService writer;
    private final EditorialService editorial;
    private final QualityGate gate;
    private final MessageSource messages;

    public TenantReviewController(SiteResolver siteResolver, PageStore pages, PageWriteService writer,
                                  EditorialService editorial, QualityGate gate,
                                  @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.siteResolver = siteResolver;
        this.pages = pages;
        this.writer = writer;
        this.editorial = editorial;
        this.gate = gate;
        this.messages = messages;
    }

    /** One metadata field of the comparison: its label key, the live and proposed values, whether it changed. */
    public record MetaRow(String label, String locale, String before, String after, boolean changed) {
    }

    /** One queue row for the screen: the page, whether it is a revision, who submitted it and when (formatted). */
    public record QueueRow(String slug, String path, boolean revision, String submittedBy, String submittedAt) {
    }

    /** A localized finding (severity lower-cased for the badge class). */
    public record FindingRow(String severity, String code, String message) {
    }

    @GetMapping("/__admin/review")
    @PreAuthorize("@authz.may('APPROVE_PAGES')")
    public String queue(Model model) {
        Site site = boundTenancy();
        model.addAttribute("items", editorial.queue(site).stream()
                .map(i -> new QueueRow(i.page().slug(), i.page().path(), i.revision(), i.review().submittedBy(),
                        TenantPageEditorController.when(i.review().submittedAt())))
                .toList());
        model.addAttribute("editorialLevel", site.editorial().key());
        model.addAttribute("pageTitleKey", "op.review.title");
        model.addAttribute("contentTemplate", "operator/admin/review");
        return "operator/op-layout";
    }

    @GetMapping("/__admin/review/{slug}")
    @PreAuthorize("@authz.may('APPROVE_PAGES')")
    public String item(@PathVariable String slug, Model model, RedirectAttributes redirect) {
        Site site = boundTenancy();
        Optional<EditorialService.QueueItem> found = editorial.queue(site).stream()
                .filter(i -> i.page().slug().equals(slug)).findFirst();
        if (found.isEmpty()) {
            redirect.addFlashAttribute("error", message("op.review.error.not-in-review", "Nothing is waiting."));
            return "redirect:/__admin/review";
        }
        EditorialService.QueueItem item = found.get();
        Page live = item.page();
        String liveHtml = writer.readBody(site.id(), slug).orElse("");
        String liveLogic = writer.readLogic(site.id(), slug).orElse("");
        Page proposed = live;
        String html = liveHtml;
        String logic = liveLogic;
        if (item.revision()) {
            Optional<PendingRevisionStore.Revision> rev = writer.pending().revision(site.id(), slug);
            if (rev.isPresent()) {
                proposed = rev.get().page().withDraft(live.draft());
                html = rev.get().html();
                logic = rev.get().thxml() == null ? "" : rev.get().thxml();
            }
        }
        model.addAttribute("item", item);
        model.addAttribute("submittedAt", TenantPageEditorController.when(item.review().submittedAt()));
        model.addAttribute("live", live);
        model.addAttribute("proposed", proposed);
        List<MetaRow> meta = item.revision() ? metaRows(live, proposed, site) : List.of();
        model.addAttribute("metaRows", meta);
        model.addAttribute("metaChanged", meta.stream().anyMatch(MetaRow::changed));
        LineDiff.Result body = LineDiff.diff(liveHtml, html);
        LineDiff.Result logicDiff = LineDiff.diff(liveLogic, logic);
        model.addAttribute("bodyDiff", body);
        model.addAttribute("logicDiff", logicDiff);
        model.addAttribute("liveUrl", "/__admin/pages/" + slug + "/preview");
        model.addAttribute("proposedUrl", "/__admin/pages/" + slug + "/preview" + (item.revision() ? "?revision=1" : ""));
        QualityReport report = gate.inspect(site, proposed, html, logic,
                proposed.primaryLocale(site.defaultLocale()));
        model.addAttribute("findings", localize(report.findings()));
        model.addAttribute("enforced", site.editorial().enforcesChecks());
        model.addAttribute("hasErrors", report.errors() > 0);
        model.addAttribute("editorialLevel", site.editorial().key());
        model.addAttribute("pageTitleKey", "op.review.item.title");
        model.addAttribute("contentTemplate", "operator/admin/review-item");
        return "operator/op-layout";
    }

    @PostMapping("/__admin/review/{slug}/approve")
    @PreAuthorize("@authz.may('APPROVE_PAGES')")
    public String approve(@PathVariable String slug, @RequestParam(required = false) String fingerprint,
                          RedirectAttributes redirect) {
        Site site = boundTenancy();
        try {
            List<Finding> blocking = editorial.approve(site, slug, fingerprint);
            if (!blocking.isEmpty()) {
                redirect.addFlashAttribute("error", message("op.review.blocked.approve",
                        "Not approved: the page has quality errors.") + " " + reasons(blocking));
                return "redirect:/__admin/review/" + slug;
            }
            redirect.addFlashAttribute("flash", message("op.review.approved", "Approved and published."));
        } catch (EditorialException ex) {
            redirect.addFlashAttribute("error", message("op.review.error." + ex.code(), ex.getMessage()));
            return "changed".equals(ex.code()) ? "redirect:/__admin/review/" + slug : "redirect:/__admin/review";
        } catch (PageAuthoringException ex) {
            redirect.addFlashAttribute("error", message("op.pages.error." + ex.code(), ex.getMessage()));
            return "redirect:/__admin/review/" + slug;
        }
        return "redirect:/__admin/review";
    }

    @PostMapping("/__admin/review/{slug}/return")
    @PreAuthorize("@authz.may('APPROVE_PAGES')")
    public String sendBack(@PathVariable String slug, @RequestParam(required = false) String note,
                           RedirectAttributes redirect) {
        Site site = boundTenancy();
        try {
            editorial.sendBack(site, slug, note);
            redirect.addFlashAttribute("flash", message("op.review.returned", "Sent back to the editor."));
        } catch (EditorialException ex) {
            redirect.addFlashAttribute("error", message("op.review.error." + ex.code(), ex.getMessage()));
            return "note-required".equals(ex.code()) ? "redirect:/__admin/review/" + slug : "redirect:/__admin/review";
        }
        return "redirect:/__admin/review";
    }

    @PostMapping("/__admin/pages/{slug}/submit")
    @PreAuthorize("@authz.may('EDIT_PAGES')")
    public String submit(@PathVariable String slug, RedirectAttributes redirect) {
        Site site = boundTenancy();
        try {
            editorial.submit(site, slug);
            redirect.addFlashAttribute("flash", message("op.review.submitted", "Submitted for review."));
        } catch (EditorialException ex) {
            redirect.addFlashAttribute("error", message("op.review.error." + ex.code(), ex.getMessage()));
        }
        return "redirect:/__admin/pages/" + slug + "/edit";
    }

    @PostMapping("/__admin/pages/{slug}/withdraw")
    @PreAuthorize("@authz.may('EDIT_PAGES')")
    public String withdraw(@PathVariable String slug, RedirectAttributes redirect) {
        Site site = boundTenancy();
        if (pages.find(site.id(), slug).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such page");
        }
        editorial.withdraw(site, slug);
        redirect.addFlashAttribute("flash", message("op.review.withdrawn", "Taken out of review."));
        return "redirect:/__admin/pages/" + slug + "/edit";
    }

    /**
     * The metadata comparison rows between the live page and its revision — every field the editor can change,
     * per locale where it is per-locale. Only changed rows are flagged; unchanged ones are still listed so the
     * approver sees the whole picture.
     */
    static List<MetaRow> metaRows(Page live, Page proposed, Site site) {
        List<MetaRow> rows = new ArrayList<>();
        rows.add(row("path", "", live.path(), proposed.path()));
        Set<String> langs = new LinkedHashSet<>();
        site.locales().forEach(l -> langs.add(l.getLanguage()));
        langs.addAll(live.title().keySet());
        langs.addAll(proposed.title().keySet());
        langs.addAll(live.description().keySet());
        langs.addAll(proposed.description().keySet());
        langs.addAll(live.localizedPaths().keySet());
        langs.addAll(proposed.localizedPaths().keySet());
        for (String lang : langs) {
            rows.add(row("title", lang, live.title().get(lang), proposed.title().get(lang)));
            rows.add(row("description", lang, live.description().get(lang), proposed.description().get(lang)));
            rows.add(row("localizedPath", lang, live.localizedPaths().get(lang), proposed.localizedPaths().get(lang)));
        }
        rows.add(row("locales", "", join(live.locales()), join(proposed.locales())));
        rows.add(row("template", "", live.template(), proposed.template()));
        rows.add(row("activeNav", "", live.activeNav(), proposed.activeNav()));
        rows.add(row("author", "", live.author(), proposed.author()));
        rows.add(row("publishedAt", "", str(live.publishedAt()), str(proposed.publishedAt())));
        rows.add(row("canonical", "", live.canonical(), proposed.canonical()));
        rows.add(row("cacheControl", "", live.cacheControl(), proposed.cacheControl()));
        rows.add(row("noindex", "", Boolean.toString(live.noindex()), Boolean.toString(proposed.noindex())));
        rows.add(row("nosnippet", "", Boolean.toString(live.nosnippet()), Boolean.toString(proposed.nosnippet())));
        rows.add(row("gone", "", Boolean.toString(live.gone()), Boolean.toString(proposed.gone())));
        rows.add(row("params", "", live.params().toString(), proposed.params().toString()));
        return rows;
    }

    private static MetaRow row(String label, String locale, String before, String after) {
        String b = before == null ? "" : before;
        String a = after == null ? "" : after;
        return new MetaRow(label, locale, b, a, !Objects.equals(b, a));
    }

    private static String join(List<Locale> locales) {
        return locales.stream().map(Locale::getLanguage).collect(Collectors.joining(", "));
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private List<FindingRow> localize(List<Finding> findings) {
        Locale ui = LocaleContextHolder.getLocale();
        List<FindingRow> rows = new ArrayList<>(findings.size());
        for (Finding f : findings) {
            rows.add(new FindingRow(f.severity().name().toLowerCase(Locale.ROOT), f.code(),
                    messages.getMessage("quality." + f.code(), null, f.message(), ui)));
        }
        return rows;
    }

    private String reasons(List<Finding> blocking) {
        return localize(blocking).stream().map(FindingRow::message).collect(Collectors.joining(" · "));
    }

    private String message(String code, String fallback) {
        return messages.getMessage(code, null, fallback, LocaleContextHolder.getLocale());
    }

    private Site boundTenancy() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"));
    }
}
