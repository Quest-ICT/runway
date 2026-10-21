package mx.quest.runway.console;

import java.io.IOException;
import java.util.List;
import mx.quest.runway.document.DocumentService;
import mx.quest.runway.document.DocumentService.DocumentEntry;
import mx.quest.runway.site.SiteResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The tenant console's documents screen ({@code /__admin/documents}): a site manager uploads, lists, deletes,
 * and enables/disables the site's downloadable files (its {@code static/doc/} area). Gated by the grantable
 * {@code MANAGE_DOCUMENTS} permission (narrower than {@code CONFIGURE_SITE}). Documents are served publicly as
 * {@code nosniff} attachments; a <em>disabled</em> document is withheld (404) without being deleted.
 */
@Controller
public class DocumentsController {

    private static final Logger log = LoggerFactory.getLogger(DocumentsController.class);

    private final SiteResolver siteResolver;
    private final DocumentService documents;
    private final MessageSource messages;

    public DocumentsController(SiteResolver siteResolver, DocumentService documents,
                               @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.siteResolver = siteResolver;
        this.documents = documents;
        this.messages = messages;
    }

    @GetMapping("/__admin/documents")
    @PreAuthorize("@authz.may('MANAGE_DOCUMENTS')")
    public String list(Model model) {
        String siteId = currentSiteId();
        List<DocumentEntry> docs;
        try {
            docs = documents.list(siteId);
        } catch (IOException e) {
            log.warn("could not list documents for {}", siteId, e);
            docs = List.of();
            model.addAttribute("error", message("op.documents.listFailed", "Could not list documents."));
        }
        model.addAttribute("documents", docs);
        model.addAttribute("maxMb", DocumentService.MAX_DOC_BYTES / (1024 * 1024));
        model.addAttribute("contentTemplate", "operator/admin/documents");
        model.addAttribute("pageTitleKey", "op.documents.title");
        return "operator/op-layout";
    }

    @PostMapping("/__admin/documents/upload")
    @PreAuthorize("@authz.may('MANAGE_DOCUMENTS')")
    public String upload(@RequestParam("file") MultipartFile file, RedirectAttributes redirect) {
        if (file == null || file.isEmpty()) {
            redirect.addFlashAttribute("error", message("op.documents.noFile", "Choose a file."));
            return "redirect:/__admin/documents";
        }
        if (file.getSize() > DocumentService.MAX_DOC_BYTES) {
            redirect.addFlashAttribute("error", message("op.documents.tooLarge", "The document is too large."));
            return "redirect:/__admin/documents";
        }
        try {
            documents.add(currentSiteId(), file.getOriginalFilename(), file.getBytes());
            redirect.addFlashAttribute("flash", message("op.documents.uploaded", "Document uploaded."));
        } catch (mx.quest.runway.site.SiteStorageCeilings.OverCeiling over) {
            redirect.addFlashAttribute("error", message("op.documents.overCeiling",
                    "Not uploaded: the site's files would go over their storage limit."));
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", message("op.documents.badName", "That file name cannot be used."));
        } catch (IOException e) {
            redirect.addFlashAttribute("error", message("op.documents.failed", "Could not save the document."));
        }
        return "redirect:/__admin/documents";
    }

    @PostMapping("/__admin/documents/delete")
    @PreAuthorize("@authz.may('MANAGE_DOCUMENTS')")
    public String delete(@RequestParam String path, RedirectAttributes redirect) {
        try {
            documents.delete(currentSiteId(), path);
            redirect.addFlashAttribute("flash", message("op.documents.deleted", "Document deleted."));
        } catch (IllegalArgumentException | IOException e) {
            redirect.addFlashAttribute("error", message("op.documents.failed", "Could not delete the document."));
        }
        return "redirect:/__admin/documents";
    }

    @PostMapping("/__admin/documents/toggle")
    @PreAuthorize("@authz.may('MANAGE_DOCUMENTS')")
    public String toggle(@RequestParam String path, @RequestParam(defaultValue = "false") boolean disable,
                         RedirectAttributes redirect) {
        try {
            documents.setDisabled(currentSiteId(), path, disable);
            redirect.addFlashAttribute("flash", disable
                    ? message("op.documents.disabledMsg", "Document disabled.")
                    : message("op.documents.enabledMsg", "Document enabled."));
        } catch (IllegalArgumentException | IOException e) {
            redirect.addFlashAttribute("error", message("op.documents.failed", "Could not update the document."));
        }
        return "redirect:/__admin/documents";
    }

    private String currentSiteId() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"))
                .id();
    }

    private String message(String key, String fallback) {
        return messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }
}
