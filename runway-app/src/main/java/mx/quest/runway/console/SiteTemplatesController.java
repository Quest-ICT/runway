package mx.quest.runway.console;

import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.site.SeedTemplateStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The application-realm <b>Site templates</b> catalog ({@code /__app/site-templates}) — the operator editor for
 * the named seed templates a new site can be <em>born from</em> (the starter master + home page). It is the UI
 * over {@link SeedTemplateStore}: list/create/import/export/delete templates, and edit/reset the files within
 * one. The built-in {@code default} template is always present and cannot be deleted.
 *
 * <p>Gated to the grantable {@code MANAGE_STYLES} permission by the {@code /__app/site-templates/**} request
 * matcher in {@code AppSecurityConfig}; no in-controller authority check is needed. Editing is <b>forward-only</b>
 * — it changes what future sites are created with; existing sites already own their copy (self-containment).
 * Every message is in the operator's language ({@code op.sitetpl.flash.*}, and {@code op.sitetpl.error.*} for the
 * catalog's coded refusals, {@link SeedTemplateStore.Invalid}).
 */
@Controller
@RequestMapping("/__app/site-templates")
public class SiteTemplatesController {

    private static final String LIST = "redirect:/__app/site-templates";

    private final SeedTemplateStore catalog;
    private final AppActivityLog activity;
    private final MessageSource messages;

    public SiteTemplatesController(SeedTemplateStore catalog, AppActivityLog activity,
                                   @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.messages = messages;
        this.catalog = catalog;
        this.activity = activity;
    }

    // ---- catalog -----------------------------------------------------------

    @GetMapping
    public String catalog(Model model) {
        model.addAttribute("templates", catalog.templates());
        model.addAttribute("contentTemplate", "operator/app/site-templates");
        model.addAttribute("pageTitleKey", "op.sitetpl.title");
        return "operator/op-layout";
    }

    @PostMapping("/new")
    public String create(@RequestParam String id, @RequestParam(required = false) String name,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) String copyFrom, RedirectAttributes redirect) {
        try {
            catalog.createTemplate(id, name, description, copyFrom);
            activity.record(actor(), "site-template.created", id.trim());
            redirect.addFlashAttribute("flash", message("op.sitetpl.flash.created", "Created template.", id.trim()));
            return "redirect:/__app/site-templates/" + id.trim();
        } catch (IllegalArgumentException invalid) {
            redirect.addFlashAttribute("error", error(invalid));
            return LIST;
        }
    }

    @PostMapping("/import")
    public String importZip(@RequestParam String id, @RequestParam(required = false) String name,
                            @RequestParam(required = false) String description,
                            @RequestParam("file") MultipartFile file, RedirectAttributes redirect) {
        try {
            if (file == null || file.isEmpty()) {
                redirect.addFlashAttribute("error", message("op.sitetpl.flash.chooseZip",
                        "Choose a .zip file to import."));
                return LIST;
            }
            catalog.importZip(id, name, description, file.getBytes());
            activity.record(actor(), "site-template.imported", id.trim());
            redirect.addFlashAttribute("flash", message("op.sitetpl.flash.imported", "Imported template.",
                    id.trim()));
            return "redirect:/__app/site-templates/" + id.trim();
        } catch (IllegalArgumentException invalid) {
            redirect.addFlashAttribute("error", error(invalid));
            return LIST;
        } catch (java.io.IOException io) {
            redirect.addFlashAttribute("error", message("op.sitetpl.flash.unreadable",
                    "The uploaded file could not be read."));
            return LIST;
        }
    }

    // ---- one template ------------------------------------------------------

    @GetMapping("/{templateId}")
    public String files(@PathVariable String templateId, Model model, RedirectAttributes redirect) {
        if (!exists(templateId, redirect)) {
            return LIST;
        }
        var files = catalog.files(templateId);
        model.addAttribute("template", catalog.template(templateId));
        model.addAttribute("files", files);
        model.addAttribute("anyOverridden", files.stream().anyMatch(SeedTemplateStore.SeedTemplateView::overridden));
        model.addAttribute("contentTemplate", "operator/app/site-template-files");
        model.addAttribute("pageTitleKey", "op.sitetpl.files.title");
        return "operator/op-layout";
    }

    /** Renames a template and replaces its description; the id, and every site born from it, are unaffected. */
    @PostMapping("/{templateId}/details")
    public String details(@PathVariable String templateId, @RequestParam(required = false) String name,
                          @RequestParam(required = false) String description, RedirectAttributes redirect) {
        try {
            catalog.updateDetails(templateId, name, description);
            activity.record(actor(), "site-template.renamed", templateId);
            redirect.addFlashAttribute("flash", message("op.sitetpl.flash.renamed",
                    "Saved the name and description.", templateId));
        } catch (IllegalArgumentException invalid) {
            redirect.addFlashAttribute("error", error(invalid));
        }
        return "redirect:/__app/site-templates/" + templateId;
    }

    @PostMapping("/{templateId}/delete")
    public String delete(@PathVariable String templateId, RedirectAttributes redirect) {
        try {
            catalog.deleteTemplate(templateId);
            activity.record(actor(), "site-template.deleted", templateId);
            redirect.addFlashAttribute("flash", message("op.sitetpl.flash.deleted", "Deleted template.",
                    templateId));
        } catch (IllegalArgumentException invalid) {
            redirect.addFlashAttribute("error", error(invalid));
        }
        return LIST;
    }

    @GetMapping("/{templateId}/export")
    public ResponseEntity<byte[]> export(@PathVariable String templateId) {
        if (!catalog.templateExists(templateId)) {
            return ResponseEntity.notFound().build();
        }
        byte[] zip = catalog.exportZip(templateId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("site-template-" + templateId + ".zip").build());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).headers(headers).body(zip);
    }

    // ---- one file within a template ---------------------------------------

    @GetMapping("/{templateId}/edit")
    public String edit(@PathVariable String templateId, @RequestParam String name, Model model,
                       RedirectAttributes redirect) {
        if (!exists(templateId, redirect)) {
            return LIST;
        }
        if (!catalog.isSeedFile(name)) {
            redirect.addFlashAttribute("error", message("op.sitetpl.error.notSeedFile", "No such seed file.", name));
            return "redirect:/__app/site-templates/" + templateId;
        }
        model.addAttribute("template", catalog.template(templateId));
        model.addAttribute("name", name);
        model.addAttribute("content", catalog.readText(templateId, name));
        model.addAttribute("overridden", catalog.isOverridden(templateId, name));
        model.addAttribute("descriptionKey", descriptionKey(name));
        model.addAttribute("contentTemplate", "operator/app/site-template-edit");
        model.addAttribute("pageTitleKey", "op.sitetpl.edit.title");
        return "operator/op-layout";
    }

    @PostMapping("/{templateId}/save")
    public String save(@PathVariable String templateId, @RequestParam String name,
                       @RequestParam(required = false) String content, RedirectAttributes redirect) {
        if (!exists(templateId, redirect)) {
            return LIST;
        }
        try {
            catalog.save(templateId, name, content == null ? "" : content);
            redirect.addFlashAttribute("flash", message("op.sitetpl.flash.saved", "Saved.", name));
        } catch (IllegalArgumentException invalid) {
            redirect.addFlashAttribute("error", error(invalid));
        }
        return "redirect:/__app/site-templates/" + templateId;
    }

    @PostMapping("/{templateId}/reset")
    public String reset(@PathVariable String templateId, @RequestParam String name, RedirectAttributes redirect) {
        if (!exists(templateId, redirect)) {
            return LIST;
        }
        try {
            boolean removed = catalog.reset(templateId, name);
            redirect.addFlashAttribute("flash", removed
                    ? message("op.sitetpl.flash.reset", "Reset to the platform default.", name)
                    : message("op.sitetpl.flash.alreadyDefault", "Already the platform default.", name));
        } catch (IllegalArgumentException invalid) {
            redirect.addFlashAttribute("error", error(invalid));
        }
        return "redirect:/__app/site-templates/" + templateId;
    }

    @PostMapping("/{templateId}/reset-all")
    public String resetAll(@PathVariable String templateId, RedirectAttributes redirect) {
        if (!exists(templateId, redirect)) {
            return LIST;
        }
        int removed = catalog.resetAll(templateId);
        redirect.addFlashAttribute("flash", removed == 0
                ? message("op.sitetpl.flash.allDefault", "This template already matches the platform default.")
                : messages.getMessage("op.sitetpl.flash.resetAll", new Object[] {removed},
                        "Reset to the platform default.", LocaleContextHolder.getLocale()));
        return "redirect:/__app/site-templates/" + templateId;
    }

    private boolean exists(String templateId, RedirectAttributes redirect) {
        if (catalog.templateExists(templateId)) {
            return true;
        }
        redirect.addFlashAttribute("error", message("op.sitetpl.error.noSuchTemplate", "No such template.",
                templateId));
        return false;
    }

    private String descriptionKey(String name) {
        return SeedTemplateStore.FILES.stream()
                .filter(f -> f.name().equals(name)).findFirst()
                .map(SeedTemplateStore.SeedFile::descriptionKey).orElse("");
    }

    /** The signed-in operator (from the security context — these methods take no {@code Authentication}). */
    private static String actor() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }

    /** A message in the operator's language; {@code args} are shown as given (never formatted as numbers). */
    private String message(String key, String fallback, String... args) {
        return messages.getMessage(key, args.length == 0 ? null : args, fallback, LocaleContextHolder.getLocale());
    }

    /** A refusal in the operator's language when the catalog gave it a code, else its own text. */
    private String error(IllegalArgumentException invalid) {
        if (invalid instanceof SeedTemplateStore.Invalid coded) {
            return messages.getMessage("op.sitetpl.error." + coded.code(), coded.args(), invalid.getMessage(),
                    LocaleContextHolder.getLocale());
        }
        return invalid.getMessage();
    }
}
