package mx.quest.runway.console;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import mx.quest.runway.bandwidth.Bytes;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStorageCeilings;
import mx.quest.runway.site.StaticFilesService;
import mx.quest.runway.site.StaticFilesService.Refused;
import mx.quest.runway.storage.SiteFileStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

/**
 * The site console's <b>Static files</b> screen ({@code /__admin/static}): browse the site's {@code static/} folder,
 * upload files (several at once, optionally replacing ones of the same name), create folders, and delete files and
 * empty folders, over {@link StaticFilesService} and its rules (web asset types only, a size cap, {@code doc/} left
 * to Documents, the site's storage ceiling). The screen shows what the site's files use against that ceiling.
 *
 * <p>Gated by {@code CONFIGURE_SITE}, like Fragments: stylesheets and scripts shape every page as much as the
 * master does. Always the bound site; every change is recorded in the site's audit log.
 */
@Controller
public class TenantStaticFilesController {

    private static final String SCREEN = "/__admin/static";

    private final SiteResolver siteResolver;
    private final StaticFilesService staticFiles;
    private final MessageSource messages;
    private final SiteLogService siteLog;
    private final SiteStorageCeilings ceilings;

    public TenantStaticFilesController(SiteResolver siteResolver, StaticFilesService staticFiles,
                                       @Qualifier("operatorAwareMessageSource") MessageSource messages,
                                       SiteLogService siteLog, SiteStorageCeilings ceilings) {
        this.siteResolver = siteResolver;
        this.staticFiles = staticFiles;
        this.messages = messages;
        this.siteLog = siteLog;
        this.ceilings = ceilings;
    }

    /** One row of the listing, with what the screen needs to show and link it. */
    public record Row(String name, boolean directory, String size, long sizeBytes, long modified, String path,
                      String publicUrl, boolean documents) {
    }

    /** One step of the folder breadcrumb. */
    public record Crumb(String name, String path) {
    }

    @GetMapping(SCREEN)
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String list(@RequestParam(required = false) String dir, Model model) throws IOException {
        String siteId = currentSiteId();
        String folder;
        try {
            folder = StaticFilesService.existingPath(dir);
        } catch (Refused bad) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such folder");
        }
        if (!staticFiles.folderExists(siteId, folder)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such folder");
        }
        List<Row> rows = new ArrayList<>();
        for (SiteFileStore.Entry entry : staticFiles.list(siteId, folder)) {
            String path = folder.isEmpty() ? entry.name() : folder + "/" + entry.name();
            rows.add(new Row(entry.name(), entry.directory(),
                    entry.directory() ? "" : Bytes.human(entry.size()), entry.size(), entry.modified(), path,
                    "/static/" + UriUtils.encodePath(path, StandardCharsets.UTF_8),
                    StaticFilesService.isDocuments(path)));
        }
        List<Crumb> crumbs = new ArrayList<>();
        StringBuilder walked = new StringBuilder();
        if (!folder.isEmpty()) {
            for (String part : folder.split("/")) {
                walked.append(walked.length() == 0 ? "" : "/").append(part);
                crumbs.add(new Crumb(part, walked.toString()));
            }
        }
        model.addAttribute("folder", folder);
        model.addAttribute("crumbs", crumbs);
        model.addAttribute("rows", rows);
        model.addAttribute("inDocuments", StaticFilesService.isDocuments(folder));
        model.addAttribute("maxMiB", StaticFilesService.MAX_FILE_BYTES >> 20);
        // What the site's files use and the ceiling the operator set (read-only here).
        SiteStorageCeilings.Ceiling ceiling = ceilings.of(siteId);
        long used = ceilings.usedBytes(siteId);
        model.addAttribute("storageUsed", Bytes.human(used));
        model.addAttribute("storageLimited", ceiling.limited());
        model.addAttribute("storageCeiling", Bytes.human(ceiling.bytes()));
        model.addAttribute("storageOver", ceiling.limited() && used > ceiling.bytes());
        model.addAttribute("allowedTypes", String.join(", ",
                StaticFilesService.ALLOWED_EXTENSIONS.stream().sorted().toList()));
        model.addAttribute("contentTemplate", "operator/admin/static-files");
        model.addAttribute("pageTitleKey", "op.static.title");
        return "operator/op-layout";
    }

    /**
     * Uploads one or more files into {@code dir}. Each file is checked on its own: the ones that pass are stored,
     * the others are named in the error with the reason, so one bad file does not lose the rest.
     */
    @PostMapping(SCREEN + "/upload")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String upload(@RequestParam(required = false) String dir,
                         @RequestParam(name = "files", required = false) List<MultipartFile> uploads,
                         @RequestParam(defaultValue = "false") boolean replace, Authentication authentication,
                         RedirectAttributes redirect) {
        String siteId = currentSiteId();
        String folder = safeFolder(dir);
        List<String> stored = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        if (uploads != null) {
            for (MultipartFile upload : uploads) {
                if (upload == null || upload.isEmpty()) {
                    continue;
                }
                String original = upload.getOriginalFilename();
                try {
                    if (upload.getSize() > StaticFilesService.MAX_FILE_BYTES) {
                        throw new Refused("tooLarge", "too large");
                    }
                    String name = staticFiles.upload(siteId, folder, original, upload.getBytes(), replace);
                    stored.add(name);
                    siteLog.audit(siteId, actor(authentication), "static.uploaded", join(folder, name));
                } catch (Refused why) {
                    refused.add(original + ": " + message("op.static.refused." + why.code(), why.getMessage()));
                } catch (IOException | IllegalArgumentException failed) {
                    refused.add(original + ": " + message("op.static.failed", "could not be saved"));
                }
            }
        }
        if (stored.isEmpty() && refused.isEmpty()) {
            redirect.addFlashAttribute("error", message("op.static.noFile", "Choose at least one file."));
        }
        if (!stored.isEmpty()) {
            redirect.addFlashAttribute("flash", messages.getMessage("op.static.uploaded",
                    new Object[] {stored.size(), String.join(", ", stored)}, "Uploaded.",
                    LocaleContextHolder.getLocale()));
        }
        if (!refused.isEmpty()) {
            redirect.addFlashAttribute("error", message("op.static.notUploaded", "Not uploaded:") + " "
                    + String.join(" · ", refused));
        }
        return back(folder);
    }

    /** Creates a folder inside {@code dir}. */
    @PostMapping(SCREEN + "/folder")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String folder(@RequestParam(required = false) String dir, @RequestParam(required = false) String name,
                         Authentication authentication, RedirectAttributes redirect) {
        String siteId = currentSiteId();
        String folder = safeFolder(dir);
        try {
            staticFiles.createFolder(siteId, folder, name);
            siteLog.audit(siteId, actor(authentication), "static.folder-created", join(folder, name.strip()));
            redirect.addFlashAttribute("flash", message("op.static.folderCreated", "Folder created."));
        } catch (Refused why) {
            redirect.addFlashAttribute("error", message("op.static.refused." + why.code(), why.getMessage()));
        } catch (IOException failed) {
            redirect.addFlashAttribute("error", message("op.static.failed", "could not be saved"));
        }
        return back(folder);
    }

    /** Deletes a file, or a folder when it is empty, then returns to the folder that held it. */
    @PostMapping(SCREEN + "/delete")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String delete(@RequestParam(required = false) String path, Authentication authentication,
                         RedirectAttributes redirect) {
        String siteId = currentSiteId();
        // Not stripped: a name with a leading or trailing space ("  logo.css") is a real, listed name, and deleting
        // it must name it exactly. A blank path is the static folder itself, which the service refuses.
        String target = path == null || path.isBlank() ? "" : path;
        String parent = target.contains("/") ? target.substring(0, target.lastIndexOf('/')) : "";
        try {
            if (staticFiles.delete(siteId, target)) {
                ceilings.changed(siteId);
                siteLog.audit(siteId, actor(authentication), "static.deleted", target);
                redirect.addFlashAttribute("flash", message("op.static.deleted", "Deleted."));
            } else {
                redirect.addFlashAttribute("error", message("op.static.notDeleted",
                        "Nothing was deleted: the file is gone, or the folder is not empty."));
            }
        } catch (Refused why) {
            redirect.addFlashAttribute("error", message("op.static.refused." + why.code(), why.getMessage()));
        } catch (IOException failed) {
            redirect.addFlashAttribute("error", message("op.static.failed", "could not be saved"));
        }
        return back(safeFolderOrRoot(parent));
    }

    private static String safeFolder(String dir) {
        try {
            return StaticFilesService.existingPath(dir);
        } catch (Refused bad) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not a folder");
        }
    }

    private static String safeFolderOrRoot(String dir) {
        try {
            return StaticFilesService.existingPath(dir);
        } catch (Refused bad) {
            return "";
        }
    }

    private static String back(String folder) {
        return "redirect:" + SCREEN + (folder.isEmpty() ? ""
                : "?dir=" + UriUtils.encodeQueryParam(folder, StandardCharsets.UTF_8));
    }

    private static String join(String folder, String name) {
        return folder.isEmpty() ? name : folder + "/" + name;
    }

    private static String actor(Authentication authentication) {
        return authentication == null ? "unknown" : authentication.getName();
    }

    private String currentSiteId() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy")).id();
    }

    private String message(String key, String fallback) {
        return messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }
}
