package mx.quest.runway.console;

import java.io.IOException;
import java.time.LocalDate;
import mx.quest.runway.log.AuditPage;
import mx.quest.runway.log.LogKind;
import mx.quest.runway.log.LogProperties;
import mx.quest.runway.log.SiteLogConfig;
import mx.quest.runway.log.SiteLogConfigService;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.site.SiteResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The tenant console's logs screen ({@code /__admin/logs}): tails the site's access log, shows the site's
 * <b>audit journal</b> as a filtered, paged listing (date range / actor / action, newest first, across the whole
 * retention window — the same {@link AuditView} card as the application Activity log) with a CSV download
 * ({@code /__admin/logs/audit.csv}), and edits the log settings (IP anonymization, retention). A change to those
 * settings is itself recorded in the audit journal.
 *
 * <p>Logs are strictly tenant-private, so every route is gated to the site's own administrators via
 * {@link TenantAuthority} — there is no grantable "view logs" permission and no app-realm surface that reads a
 * tenant's logs. The journal read is bounded ({@link mx.quest.runway.log.AuditJournal.Limits#DEFAULT}).
 */
@Controller
public class LogsController {

    private static final int TAIL_LINES = 200;

    private final SiteResolver siteResolver;
    private final SiteLogService logs;
    private final SiteLogConfigService configs;
    private final LogProperties props;
    private final TenantAuthority tenantAuthority;
    private final MessageSource messages;

    public LogsController(SiteResolver siteResolver, SiteLogService logs, SiteLogConfigService configs,
                          LogProperties props, TenantAuthority tenantAuthority,
                          @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.siteResolver = siteResolver;
        this.logs = logs;
        this.configs = configs;
        this.props = props;
        this.tenantAuthority = tenantAuthority;
        this.messages = messages;
    }

    @GetMapping("/__admin/logs")
    public String view(Authentication authentication,
                       @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                       @RequestParam(required = false) String actor, @RequestParam(required = false) String action,
                       @RequestParam(required = false) Integer page, Model model) {
        String siteId = currentSiteId();
        tenantAuthority.require(authentication, siteId);

        SiteLogConfig cfg = configs.get(siteId);
        model.addAttribute("accessLines", String.join("\n", logs.tail(siteId, LogKind.ACCESS, TAIL_LINES)));
        AuditView.Filter filter = AuditView.filter(from, to, actor, action, page);
        AuditPage audit = logs.queryAudit(siteId, filter.query(), filter.page() * AuditView.PAGE_SIZE,
                AuditView.PAGE_SIZE);
        AuditView.populate(model, audit, filter, "/__admin/logs", "/__admin/logs/audit.csv", "op.logs.audit",
                "runway.site.audit", messages, LocaleContextHolder.getLocale());
        model.addAttribute("anonymize", cfg.anonymize());
        model.addAttribute("retentionDays", cfg.effectiveRetentionDays(props.getMaxRetentionDays()));
        model.addAttribute("maxRetention", props.getMaxRetentionDays());
        // Set above the platform's maximum (the operator lowered it since): say so instead of clamping silently.
        model.addAttribute("retentionClamped", cfg.retentionDays() != null
                && cfg.retentionDays() > props.getMaxRetentionDays() ? cfg.retentionDays() : null);
        model.addAttribute("dropped", logs.droppedCount());
        model.addAttribute("contentTemplate", "operator/admin/logs");
        model.addAttribute("pageTitleKey", "op.logs.title");
        return "operator/op-layout";
    }

    /** The site's audit journal as CSV (the screen's filters, at most {@link AuditView#MAX_EXPORT_ROWS} rows). */
    @GetMapping("/__admin/logs/audit.csv")
    public ResponseEntity<byte[]> exportAudit(Authentication authentication,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to,
                                              @RequestParam(required = false) String actor,
                                              @RequestParam(required = false) String action) {
        String siteId = currentSiteId();
        tenantAuthority.require(authentication, siteId);
        AuditView.Filter filter = AuditView.filter(from, to, actor, action, 0);
        AuditPage audit = logs.queryAudit(siteId, filter.query(), 0, AuditView.MAX_EXPORT_ROWS);
        return AuditView.csv(audit, siteId + "-audit-" + LocalDate.now() + ".csv", messages,
                LocaleContextHolder.getLocale());
    }

    @PostMapping("/__admin/logs")
    public String save(Authentication authentication,
                       @RequestParam(defaultValue = "false") boolean anonymize,
                       @RequestParam(defaultValue = "30") int retentionDays, RedirectAttributes redirect) {
        String siteId = currentSiteId();
        tenantAuthority.require(authentication, siteId);
        try {
            int retention = Math.max(1, retentionDays);
            if (retention > props.getMaxRetentionDays()) {
                // A ceiling refuses, never clamps silently.
                redirect.addFlashAttribute("error", message("op.logs.retention.aboveMax",
                        "The platform keeps logs at most {0} days.", props.getMaxRetentionDays()));
                return "redirect:/__admin/logs";
            }
            configs.save(siteId, new SiteLogConfig(anonymize, retention));
            logs.audit(siteId, authentication.getName(), "logs.settings-changed",
                    "anonymize=" + anonymize + " retentionDays=" + retention);
            redirect.addFlashAttribute("flash", message("op.logs.saved", "Log settings saved."));
        } catch (IOException e) {
            redirect.addFlashAttribute("error", message("op.logs.failed", "Could not save log settings."));
        }
        return "redirect:/__admin/logs";
    }

    private String currentSiteId() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"))
                .id();
    }

    private String message(String key, String fallback, Object... args) {
        return messages.getMessage(key, args.length == 0 ? null : args, fallback, LocaleContextHolder.getLocale());
    }
}
