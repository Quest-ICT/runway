package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import mx.quest.runway.backup.BackupService;
import mx.quest.runway.backup.UnsupportedBackupFormatException;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * The tenant console's <b>Backups</b> screen ({@code /__admin/backups}) — a tenant safeguards its own site:
 * create / download / delete snapshots, and restore from one.
 *
 * <p><b>Permission split.</b> The grantable {@code MANAGE_BACKUPS} lets its holder see the screen, create and delete
 * stored backups, and download a sanitized <b>transfer copy</b> — safekeeping and hand-over that any trusted operator
 * may do. Two acts are reserved for the site's administrators ({@link TenantAuthority}: the site superadmin, or a
 * wildcard {@code ADMIN}), whatever was granted:
 * <ul>
 *   <li><b>Downloading a FULL backup</b> — fresh or stored. A full backup is complete by design, so it carries every
 *       account's password hash and the site's {@code ENC(...)} plugin secrets; a delegated holder who could
 *       download one could take those off the server. The routes
 *       answer {@code 403}, and the screen hides their buttons.</li>
 *   <li><b>Restore</b>: it replaces the <em>entire</em> site, so it is an administrator's decision.</li>
 * </ul>
 * A stored backup a delegated holder created stays on the server for an administrator (or the platform operator, on
 * {@code /__app/backups}) to download or restore. Bound-tenancy only — a tenant can only ever back up or restore its
 * own site.
 */
@Controller
public class TenantBackupsController {

    private static final Logger log = LoggerFactory.getLogger(TenantBackupsController.class);

    private final SiteResolver siteResolver;
    private final BackupService backups;
    private final TenantAuthority tenantAuthority;
    private final MessageSource messages;
    private final mx.quest.runway.backup.BackupProperties policy;

    public TenantBackupsController(SiteResolver siteResolver, BackupService backups, TenantAuthority tenantAuthority,
                                   @Qualifier("operatorAwareMessageSource") MessageSource messages,
                                   mx.quest.runway.backup.BackupProperties policy) {
        this.policy = policy;
        this.siteResolver = siteResolver;
        this.backups = backups;
        this.tenantAuthority = tenantAuthority;
        this.messages = messages;
    }

    @GetMapping("/__admin/backups")
    @PreAuthorize("@authz.may('MANAGE_BACKUPS')")
    public String list(Authentication authentication, Model model) {
        Site site = boundTenancy();
        model.addAttribute("backups", backups.list(site.id()));
        // The operator's policy, read-only: whether backups run on their own, and how many are kept for how long.
        model.addAttribute("backupPolicy", new BackupPolicyView(policy.getSchedule(), policy.getRetention()));
        int count = policy.getRetention().getMaxCount();
        int days = policy.getRetention().getMaxAgeDays();
        model.addAttribute("backupPolicyKeepKey", "op.backups.policy.keep."
                + (count > 0 ? "count" : "all") + (days > 0 ? "Days" : ""));
        model.addAttribute("transferOmissions", transferOmissions(site.id()));
        // Restore is destructive + master-only; hide its affordances from a non-master (the controller still enforces).
        boolean admin = tenantAuthority.isTenantAdmin(authentication, site.id());
        model.addAttribute("canRestore", admin);
        // A full backup carries password hashes and plugin secrets: its download buttons are an administrator's
        // (the routes re-enforce it); a delegated MANAGE_BACKUPS holder sees the transfer copy instead.
        model.addAttribute("canDownloadFull", admin);
        model.addAttribute("pageTitleKey", "op.backups.title");
        model.addAttribute("contentTemplate", "operator/admin/backups");
        return "operator/op-layout";
    }

    /**
     * What a transfer copy would leave out as plugin personal data, for the hint under its button. The screen is
     * informational here, so a failure to walk the folder shows no list rather than an error page.
     */
    private java.util.List<String> transferOmissions(String siteId) {
        try {
            return backups.transferOmissions(siteId);
        } catch (IOException e) {
            log.warn("could not list the transfer omissions of {}", siteId, e);
            return java.util.List.of();
        }
    }

    /** Save a new backup on the server. */
    @PostMapping("/__admin/backups/create")
    @PreAuthorize("@authz.may('MANAGE_BACKUPS')")
    public String create(RedirectAttributes redirect) {
        Site site = boundTenancy();
        try {
            String name = backups.create(site.id());
            redirect.addFlashAttribute("flash", message("op.backups.created", "Backup created.") + " " + name);
        } catch (IOException e) {
            log.warn("backup create failed for {}", site.id(), e);
            redirect.addFlashAttribute("error", message("op.backups.failed", "Backup failed."));
        }
        return "redirect:/__admin/backups";
    }

    /**
     * Stream a fresh FULL backup straight to the browser (nothing stored on the server) — the site's administrators
     * only: {@code 403} for a delegated {@code MANAGE_BACKUPS} holder, before anything is written.
     */
    @GetMapping("/__admin/backups/download")
    @PreAuthorize("@authz.may('MANAGE_BACKUPS')")
    public void download(Authentication authentication, HttpServletResponse response) throws IOException {
        Site site = boundTenancy();
        tenantAuthority.require(authentication, site.id());
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + site.id() + "-backup-" + LocalDate.now() + ".zip\"");
        backups.backup(site.id(), response.getOutputStream());
    }


    /**
     * Stream a <b>transfer</b> archive — the same site without its credentials, accounts or hostnames.
     *
     * <p>A separate endpoint rather than a flag on the one above, so the two land in the console as two
     * clearly-labelled buttons. A backup and a hand-over are different acts with opposite requirements — one
     * must be complete, the other must not be — and until now they were the same click, which meant anyone
     * attaching a backup to a bug report or handing one to a contractor was shipping live secrets.
     */
    @GetMapping("/__admin/backups/download-transfer")
    @PreAuthorize("@authz.may('MANAGE_BACKUPS')")
    public void downloadForTransfer(HttpServletResponse response) throws IOException {
        Site site = boundTenancy();
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + site.id() + "-transfer-" + LocalDate.now() + ".zip\"");
        backups.backup(site.id(), response.getOutputStream(), BackupService.BackupPurpose.TRANSFER);
    }

    /**
     * Download a stored backup — a FULL backup, so the site's administrators only ({@code 403} for a delegated
     * {@code MANAGE_BACKUPS} holder, checked before the name is even looked up, so the answer says nothing about
     * which backups exist).
     */
    @GetMapping("/__admin/backups/{name}/download")
    @PreAuthorize("@authz.may('MANAGE_BACKUPS')")
    public ResponseEntity<Resource> downloadStored(@PathVariable String name, Authentication authentication) {
        Site site = boundTenancy();
        tenantAuthority.require(authentication, site.id());
        Path file = backups.file(site.id(), name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such backup"));
        // Name the download from the RESOLVED file, never the raw path variable — the header must not echo
        // attacker-controlled input (CRLF / quotes) even though the store already rejects it as an unsafe id.
        String filename = file.getFileName().toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(new FileSystemResource(file));
    }

    /** Delete a stored backup. */
    @PostMapping("/__admin/backups/{name}/delete")
    @PreAuthorize("@authz.may('MANAGE_BACKUPS')")
    public String delete(@PathVariable String name, RedirectAttributes redirect) {
        Site site = boundTenancy();
        backups.delete(site.id(), name);
        redirect.addFlashAttribute("flash", message("op.backups.deleted", "Backup deleted."));
        return "redirect:/__admin/backups";
    }

    /** Restore the site from a stored backup (destructive — master only). */
    @PostMapping("/__admin/backups/{name}/restore")
    public String restoreStored(@PathVariable String name, Authentication authentication, RedirectAttributes redirect) {
        Site site = boundTenancy();
        tenantAuthority.require(authentication, site.id());
        try {
            backups.restoreStored(site.id(), name);
            redirect.addFlashAttribute("flash", message("op.backups.restored", "Site restored from the backup."));
        } catch (UnsupportedBackupFormatException e) {
            redirect.addFlashAttribute("error", newerFormat(e));
        } catch (IOException | RuntimeException e) {
            log.warn("restore failed for {} from {}", site.id(), name, e);
            redirect.addFlashAttribute("error", message("op.backups.restoreFailed", "Restore failed."));
        }
        return "redirect:/__admin/backups";
    }

    /** Restore the site from an uploaded backup file (destructive — master only). */
    @PostMapping("/__admin/backups/restore")
    public String restoreUpload(@RequestParam("file") MultipartFile file, Authentication authentication,
                                RedirectAttributes redirect) {
        Site site = boundTenancy();
        tenantAuthority.require(authentication, site.id());
        if (file == null || file.isEmpty()) {
            redirect.addFlashAttribute("error", message("op.backups.noFile", "Choose a backup file."));
            return "redirect:/__admin/backups";
        }
        try (InputStream in = file.getInputStream()) {
            backups.restore(site.id(), in);
            redirect.addFlashAttribute("flash", message("op.backups.restored", "Site restored from the backup."));
        } catch (UnsupportedBackupFormatException e) {
            redirect.addFlashAttribute("error", newerFormat(e));
        } catch (IOException | RuntimeException e) {
            log.warn("restore-from-upload failed for {}", site.id(), e);
            redirect.addFlashAttribute("error", message("op.backups.restoreFailed", "Restore failed."));
        }
        return "redirect:/__admin/backups";
    }

    private String message(String code, String fallback) {
        return messages.getMessage(code, null, fallback, LocaleContextHolder.getLocale());
    }

    /** The operator-facing refusal for an archive written by a newer Runway: what it is, and what to do. */
    private String newerFormat(UnsupportedBackupFormatException e) {
        return messages.getMessage("op.backups.newerFormat", new Object[] {e.found(), e.supported()},
                "This backup was made by a newer version of Runway. Ask the platform operator to upgrade Runway, "
                        + "then restore it.", LocaleContextHolder.getLocale());
    }

    private Site boundTenancy() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"));
    }

    /** The backup policy as the screen shows it. */
    public record BackupPolicyView(mx.quest.runway.backup.BackupProperties.Schedule schedule,
                                   mx.quest.runway.backup.BackupProperties.Retention retention) {
    }
}
