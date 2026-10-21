package mx.quest.runway.console;

import java.time.LocalDate;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.log.AuditPage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The application console's <b>Activity → Logs</b> screen ({@code /__app/logs}) — the operator audit journal
 * from {@link AppActivityLog} (sign-ins, password recovery, sign-outs, and every platform mutation including
 * role and permission changes). Read-only.
 *
 * <p>The journal is parsed into rows (time, actor, action, detail) and can be filtered by a date range, an actor
 * and an action (both case-insensitive substrings), paged newest-first through the <b>whole retention window</b>,
 * and downloaded as CSV ({@code /__app/logs/export.csv}, at most {@link AuditView#MAX_EXPORT_ROWS} rows). Every
 * request reads within {@link mx.quest.runway.log.AuditJournal.Limits#DEFAULT}; when that budget ends a search
 * early the screen says so.
 *
 * <p><b>Gate:</b> both routes require the grantable {@code VIEW_AUDIT} application permission, enforced in
 * {@code AppSecurityConfig} (the master and wildcard administrators pass; an ordinary operator only once granted
 * it). The journal names who did what to every site and account, so the coarse operator role band is not enough.
 * Retention is a platform setting ({@code runway.app-activity.retention-days}), not editable here.
 */
@Controller
public class AppLogsController {

    private final AppActivityLog activity;
    private final MessageSource messages;

    public AppLogsController(AppActivityLog activity,
                             @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.activity = activity;
        this.messages = messages;
    }

    @GetMapping("/__app/logs")
    public String view(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                       @RequestParam(required = false) String actor, @RequestParam(required = false) String action,
                       @RequestParam(required = false) Integer page, Model model) {
        AuditView.Filter filter = AuditView.filter(from, to, actor, action, page);
        AuditPage result = activity.query(filter.query(), filter.page() * AuditView.PAGE_SIZE, AuditView.PAGE_SIZE);
        AuditView.populate(model, result, filter, "/__app/logs", "/__app/logs/export.csv",
                "op.applog.audit", "runway.app.audit", messages,
                LocaleContextHolder.getLocale());
        model.addAttribute("contentTemplate", "operator/app/logs");
        model.addAttribute("pageTitleKey", "op.applog.title");
        return "operator/op-layout";
    }

    @GetMapping("/__app/logs/export.csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) String actor,
                                         @RequestParam(required = false) String action) {
        AuditView.Filter filter = AuditView.filter(from, to, actor, action, 0);
        AuditPage result = activity.query(filter.query(), 0, AuditView.MAX_EXPORT_ROWS);
        return AuditView.csv(result, "runway-activity-" + LocalDate.now() + ".csv", messages,
                LocaleContextHolder.getLocale());
    }
}
