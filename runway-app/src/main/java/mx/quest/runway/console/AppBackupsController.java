package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mx.quest.runway.backup.BackupService;
import mx.quest.runway.backup.BackupService.StoredBackup;
import mx.quest.runway.backup.UnsupportedBackupFormatException;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteHosts;
import mx.quest.runway.site.SiteStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The application console's backups screen ({@code /__app/backups}): the operator can back up, download and
 * restore <b>any</b> site (not just its own), and <b>create a new site from an uploaded backup</b> (cloning or
 * migrating). Gated to platform superadmins via {@link PlatformAuthority} — these are cross-tenant operations.
 */
@Controller
public class AppBackupsController {

    private static final Logger log = LoggerFactory.getLogger(AppBackupsController.class);

    private final SiteStore sites;
    private final BackupService backups;
    private final PlatformAuthority platformAuthority;
    private final MessageSource messages;
    private final AppActivityLog activity;

    private final mx.quest.runway.backup.BackupProperties policy;
    private final mx.quest.runway.platform.PlatformSettingsStore platform;

    public AppBackupsController(SiteStore sites, BackupService backups, PlatformAuthority platformAuthority,
                               @Qualifier("operatorAwareMessageSource") MessageSource messages,
                               AppActivityLog activity, mx.quest.runway.backup.BackupProperties policy,
                               mx.quest.runway.platform.PlatformSettingsStore platform) {
        this.platform = platform;
        this.policy = policy;
        this.sites = sites;
        this.backups = backups;
        this.platformAuthority = platformAuthority;
        this.messages = messages;
        this.activity = activity;
    }

    /** Flash attribute naming the site whose hosts the Backups screen shows for review after a restore. */
    static final String REVIEW_HOSTS = "reviewHostsOf";

    /** One site row: its id and the backups stored for it on the server. */
    public record SiteBackups(String siteId, List<StoredBackup> stored) {
    }

    @GetMapping("/__app/backups")
    public String view(Authentication authentication, Model model) {
        platformAuthority.require(authentication);
        List<SiteBackups> rows = new ArrayList<>();
        for (Site site : sites.findAll()) {
            rows.add(new SiteBackups(site.id(), backups.list(site.id())));
        }
        model.addAttribute("rows", rows);
        // After a restore (or a site created from a backup), how the site is reached now: the hosts and base URL may
        // have come from the backup, so they are shown for review with the editor, read from the store as they stand.
        if (model.getAttribute(REVIEW_HOSTS) instanceof String reviewed) {
            sites.find(reviewed).ifPresent(site -> {
                model.addAttribute("reviewSite", site);
                // A site with no host is reached only if it is the default (catch-all) site; otherwise not at all.
                model.addAttribute("reviewIsDefault", platform.get().defaultSite().filter(reviewed::equals).isPresent());
            });
        }
        // Shown, not edited: a server folder that receives every site's archives is the host's (settings-homes.yml).
        String offsite = policy.getOffsiteDir();
        model.addAttribute("offsiteDir", offsite == null || offsite.isBlank() ? null : offsite.trim());
        model.addAttribute("contentTemplate", "operator/app/backups");
        model.addAttribute("pageTitleKey", "op.appbackups.title");
        return "operator/op-layout";
    }

    /** Stream a fresh backup of any site. */
    @GetMapping("/__app/backups/{siteId}/download")
    public void download(@PathVariable String siteId, Authentication authentication, HttpServletResponse response)
            throws IOException {
        platformAuthority.require(authentication);
        if (!sites.exists(siteId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such site");
        }
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + siteId + "-backup-" + LocalDate.now() + ".zip\"");
        backups.backup(siteId, response.getOutputStream());
    }

    /** Stream a <b>transfer</b> archive of any site — no credentials, accounts or origin hostnames. */
    @GetMapping("/__app/backups/{siteId}/download-transfer")
    public void downloadForTransfer(@PathVariable String siteId, Authentication authentication,
                                    HttpServletResponse response) throws IOException {
        platformAuthority.require(authentication);
        if (!sites.exists(siteId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such site");
        }
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + siteId + "-transfer-" + LocalDate.now() + ".zip\"");
        backups.backup(siteId, response.getOutputStream(), BackupService.BackupPurpose.TRANSFER);
    }

    /** Save a stored backup of any site on the server. */
    @PostMapping("/__app/backups/{siteId}/save")
    public String save(@PathVariable String siteId, Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        try {
            String name = backups.create(siteId);
            activity.record(authentication.getName(), "backup.saved", siteId + "/" + name);
            redirect.addFlashAttribute("flash", message("op.appbackups.saved", "Backup saved.") + " " + name);
        } catch (IOException | RuntimeException e) {
            redirect.addFlashAttribute("error", message("op.appbackups.failed", "Backup failed."));
        }
        return "redirect:/__app/backups";
    }

    /** Restore any site from an uploaded backup (replaces that site). */
    @PostMapping("/__app/backups/{siteId}/restore")
    public String restoreUpload(@PathVariable String siteId, @RequestParam("file") MultipartFile file,
                                Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        if (file == null || file.isEmpty()) {
            redirect.addFlashAttribute("error", message("op.appbackups.noFile", "Choose a backup file."));
            return "redirect:/__app/backups";
        }
        try (InputStream in = file.getInputStream()) {
            backups.restore(siteId, in);
            activity.record(authentication.getName(), "site.restored", siteId + " (upload)");
            redirect.addFlashAttribute("flash", message("op.appbackups.restored", "Site restored."));
            redirect.addFlashAttribute(REVIEW_HOSTS, siteId);
        } catch (UnsupportedBackupFormatException e) {
            redirect.addFlashAttribute("error", newerFormat(e));
        } catch (IOException | RuntimeException e) {
            log.warn("app-realm restore-from-upload failed for {}", siteId, e);
            redirect.addFlashAttribute("error", message("op.appbackups.restoreFailed", "Restore failed."));
        }
        return "redirect:/__app/backups";
    }

    /** Restore a site from one of its stored backups. */
    @PostMapping("/__app/backups/{siteId}/{name}/restore")
    public String restoreStored(@PathVariable String siteId, @PathVariable String name,
                                Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        try {
            backups.restoreStored(siteId, name);
            activity.record(authentication.getName(), "site.restored", siteId + "/" + name);
            redirect.addFlashAttribute("flash", message("op.appbackups.restored", "Site restored."));
            redirect.addFlashAttribute(REVIEW_HOSTS, siteId);
        } catch (UnsupportedBackupFormatException e) {
            redirect.addFlashAttribute("error", newerFormat(e));
        } catch (IOException | RuntimeException e) {
            redirect.addFlashAttribute("error", message("op.appbackups.restoreFailed", "Restore failed."));
        }
        return "redirect:/__app/backups";
    }

    /** Delete a stored backup. */
    @PostMapping("/__app/backups/{siteId}/{name}/delete")
    public String deleteStored(@PathVariable String siteId, @PathVariable String name,
                               Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        backups.delete(siteId, name);
        activity.record(authentication.getName(), "backup.deleted", siteId + "/" + name);
        redirect.addFlashAttribute("flash", message("op.appbackups.deleted", "Backup deleted."));
        return "redirect:/__app/backups";
    }

    /** Create a NEW site from an uploaded backup — cloning or migrating a site under a new identity. */
    @PostMapping("/__app/backups/create-site")
    public String createSite(@RequestParam String newId, @RequestParam(required = false) String name,
                             @RequestParam(required = false) String hosts, @RequestParam("file") MultipartFile file,
                             Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        if (file == null || file.isEmpty()) {
            redirect.addFlashAttribute("error", message("op.appbackups.noFile", "Choose a backup file."));
            return "redirect:/__app/backups";
        }
        try {
            // Checked here first so a bad or claimed host is reported as such, not as an unusable site id (the
            // service checks again under the host lock when it saves).
            SiteHosts.requireUnclaimed(sites, newId.strip(), SiteHosts.normalize(splitHosts(hosts)));
        } catch (IllegalArgumentException badHost) {
            redirect.addFlashAttribute("error", message("op.appbackups.badHosts", "Those hosts cannot be used:")
                    + " " + badHost.getMessage());
            return "redirect:/__app/backups";
        }
        try (InputStream in = file.getInputStream()) {
            backups.createFromBackup(newId.strip(), name, splitHosts(hosts), in);
            activity.record(authentication.getName(), "site.created-from-backup", newId.strip());
            redirect.addFlashAttribute("flash", message("op.appbackups.created", "Site created from backup."));
            redirect.addFlashAttribute(REVIEW_HOSTS, newId.strip());
        } catch (UnsupportedBackupFormatException e) {
            redirect.addFlashAttribute("error", newerFormat(e));
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", message("op.appbackups.exists", "A site with that id already exists."));
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", message("op.appbackups.badId", "That site id cannot be used."));
        } catch (IOException | RuntimeException e) {
            log.warn("create-site-from-backup failed for {}", newId, e);
            redirect.addFlashAttribute("error", message("op.appbackups.createFailed", "Could not create the site."));
        }
        return "redirect:/__app/backups";
    }

    private static List<String> splitHosts(String hosts) {
        if (hosts == null || hosts.isBlank()) {
            return List.of();
        }
        return Arrays.stream(hosts.split("[,\\s]+")).map(String::strip).filter(h -> !h.isEmpty()).toList();
    }

    private String message(String key, String fallback) {
        return messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }

    /** The operator-facing refusal for an archive written by a newer Runway: what it is, and what to do. */
    private String newerFormat(UnsupportedBackupFormatException e) {
        return messages.getMessage("op.appbackups.newerFormat", new Object[] {e.found(), e.supported()},
                "This backup was made by a newer version of Runway. Upgrade Runway, then restore it.",
                LocaleContextHolder.getLocale());
    }
}
