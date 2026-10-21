package mx.quest.runway.console;

import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteLifecycleService;
import mx.quest.runway.site.SiteResolver;
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
 * The tenant console's <b>Maintenance</b> screen ({@code /__admin/maintenance}) — a site manager takes the
 * <em>public</em> site offline for maintenance (visitors see a 503 page with an optional message) while the
 * console and login stay reachable, then brings it back.
 *
 * <p>Gated by the grantable {@code MAINTENANCE_MODE} permission. Bound-tenancy only — a manager can only ever
 * toggle maintenance on its own site. The enforcement (the public 503) is {@code MaintenanceSiteFilter}; this
 * controller only flips the stored flag.
 */
@Controller
public class MaintenanceController {

    private final SiteResolver siteResolver;
    private final SiteLifecycleService lifecycle;
    private final MessageSource messages;

    public MaintenanceController(SiteResolver siteResolver, SiteLifecycleService lifecycle,
                                 @org.springframework.beans.factory.annotation.Qualifier("operatorAwareMessageSource")
                                 MessageSource messages) {
        this.siteResolver = siteResolver;
        this.lifecycle = lifecycle;
        this.messages = messages;
    }

    @GetMapping("/__admin/maintenance")
    @PreAuthorize("@authz.may('MAINTENANCE_MODE')")
    public String view(Model model) {
        Site site = boundTenancy();
        model.addAttribute("maintenance", site.maintenance());
        model.addAttribute("maintenanceMessage", site.maintenanceMessage() == null ? "" : site.maintenanceMessage());
        model.addAttribute("pageTitleKey", "op.maint.title");
        model.addAttribute("contentTemplate", "operator/admin/maintenance");
        return "operator/op-layout";
    }

    @PostMapping("/__admin/maintenance")
    @PreAuthorize("@authz.may('MAINTENANCE_MODE')")
    public String toggle(@RequestParam(value = "enabled", defaultValue = "false") boolean enabled,
                         @RequestParam(value = "message", required = false) String message,
                         RedirectAttributes redirect) {
        Site site = boundTenancy();
        lifecycle.setMaintenance(site.id(), enabled, message);
        String key = enabled ? "op.maint.flash.on" : "op.maint.flash.off";
        redirect.addFlashAttribute("flash", message(key, enabled ? "Maintenance mode is on." : "Site is live again."));
        return "redirect:/__admin/maintenance";
    }

    private String message(String code, String fallback) {
        return messages.getMessage(code, null, fallback, LocaleContextHolder.getLocale());
    }

    private Site boundTenancy() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"));
    }
}
