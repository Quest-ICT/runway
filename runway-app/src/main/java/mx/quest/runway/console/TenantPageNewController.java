package mx.quest.runway.console;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.page.MasterTemplates;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageAuthoringException;
import mx.quest.runway.page.PageScaffold;
import mx.quest.runway.page.PageWriteService;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Creates a new tenant page ({@code /__admin/pages/new}) — the two authoring shapes:
 *
 * <ul>
 *   <li><b>Born from a master</b> — the operator picks one of the tenancy's masters ({@link MasterTemplates});
 *       {@link PageScaffold#fromMaster} emits a skeleton that pulls that master's seams so the page previews
 *       finished immediately and the author only fills {@code #content};</li>
 *   <li><b>Standalone</b> — a self-contained single-document page ({@link PageScaffold#standalone}), no master.</li>
 * </ul>
 *
 * <p>New pages are created as <b>drafts</b> (never published on creation), through {@link
 * PageWriteService#create} (which refuses to clobber an existing slug and validates the page). On success the
 * operator lands in the editor for the new page; a rejection returns to the form with a flash error. Gated
 * by {@code @authz.may('EDIT_PAGES')}, bound tenancy only.
 */
@Controller
public class TenantPageNewController {

    private final SiteResolver siteResolver;
    private final MasterTemplates masters;
    private final PageWriteService writer;
    private final MessageSource messages;

    public TenantPageNewController(SiteResolver siteResolver, MasterTemplates masters, PageWriteService writer,
                                   @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.siteResolver = siteResolver;
        this.masters = masters;
        this.writer = writer;
        this.messages = messages;
    }

    @GetMapping("/__admin/pages/new")
    @PreAuthorize("@authz.may('EDIT_PAGES')")
    public String form(Model model) {
        Site site = boundTenancy();
        model.addAttribute("masters", masters.list(site.id()));
        model.addAttribute("pageTitleKey", "op.pages.new.title");
        model.addAttribute("contentTemplate", "operator/admin/page-new");
        return "operator/op-layout";
    }

    @PostMapping("/__admin/pages/new")
    @PreAuthorize("@authz.may('EDIT_PAGES')")
    public String create(@RequestParam String slug, @RequestParam(required = false) String title,
                         @RequestParam(required = false) String path, @RequestParam(required = false) String master,
                         RedirectAttributes redirect) {
        Site site = boundTenancy();
        Locale def = site.defaultLocale();
        Map<String, String> titleMap = (title == null || title.isBlank())
                ? Map.of() : Map.of(def.getLanguage(), title.trim());
        String cleanPath = (path == null || path.isBlank()) ? null : path.trim();
        boolean standalone = master == null || master.isBlank();
        try {
            String html;
            String thxml;
            String template;
            if (standalone) {
                html = PageScaffold.standalone();
                thxml = null;
                template = "_master";
            } else {
                String masterHtml = masters.readHtml(site.id(), master)
                        .orElseThrow(() -> new PageAuthoringException("missing-master", "no such master: " + master));
                PageScaffold.Scaffold scaffold = PageScaffold.fromMaster(masterHtml);
                html = scaffold.html();
                thxml = scaffold.thxml();
                template = master;
            }
            // A new page starts indexable with no canonical or Cache-Control override; the author sets these
            // in the editor.
            Page page = new Page(slug, cleanPath, Map.of(), List.of(), titleMap, Map.of(), "", template, true,
                    false, null, null);
            writer.create(site.id(), page, html, thxml);
            redirect.addFlashAttribute("flash", message("op.pages.new.created", "Page created."));
            return "redirect:/__admin/pages/" + slug + "/edit";
        } catch (PageAuthoringException ex) {
            redirect.addFlashAttribute("error", message("op.pages.error." + ex.code(), ex.getMessage()));
            return "redirect:/__admin/pages/new";
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
