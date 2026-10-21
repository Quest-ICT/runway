package mx.quest.runway.console;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.platform.EmbedHostService;
import mx.quest.runway.platform.PlatformSettings.EmbedHost;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteCspService;
import mx.quest.runway.site.SiteStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Settings → <b>Embed hosts</b> ({@code /__app/embed-hosts}): the third-party hosts the operator approves for sites to
 * add to their own public pages' Content Security Policy, each limited to the directives chosen here
 * ({@link EmbedHostService}). A site then switches on the ones it needs on its Site config. A platform operation,
 * gated to the master and wildcard admins like the other Settings screens; every change applies on the next page.
 */
@Controller
public class AppEmbedHostsController {

    private static final String SCREEN = "/__app/embed-hosts";

    private final EmbedHostService hosts;
    private final PlatformAuthority platformAuthority;
    private final SiteStore sites;
    private final SiteCspService siteCsp;
    private final AppActivityLog activity;
    private final MessageSource messages;

    public AppEmbedHostsController(EmbedHostService hosts, PlatformAuthority platformAuthority, SiteStore sites,
                                   SiteCspService siteCsp, AppActivityLog activity,
                                   @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.hosts = hosts;
        this.platformAuthority = platformAuthority;
        this.sites = sites;
        this.siteCsp = siteCsp;
        this.activity = activity;
        this.messages = messages;
    }

    @GetMapping(SCREEN)
    public String list(Authentication authentication, Model model) {
        platformAuthority.require(authentication);
        Map<String, Integer> usage = new LinkedHashMap<>();
        for (Site site : sites.findAll()) {
            for (String id : siteCsp.chosen(site.id())) {
                usage.merge(id, 1, Integer::sum);
            }
        }
        model.addAttribute("hosts", hosts.list());
        model.addAttribute("usage", usage);
        model.addAttribute("directives", EmbedHostService.DIRECTIVES);
        model.addAttribute("contentTemplate", "operator/app/embed-hosts");
        model.addAttribute("pageTitleKey", "op.embed.title");
        return "operator/op-layout";
    }

    /** Approves a host, or replaces the entry with the same id. */
    @PostMapping(SCREEN)
    public String save(@RequestParam(required = false) String id, @RequestParam(required = false) String label,
                       @RequestParam(required = false) String source,
                       @RequestParam(name = "directives", required = false) List<String> directives,
                       Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        try {
            EmbedHost saved = hosts.save(id, label, source, directives);
            activity.record(authentication.getName(), "platform.embed-host-saved",
                    saved.id() + " " + saved.source() + " " + String.join(",", saved.directives()));
            redirect.addFlashAttribute("flash", messages.getMessage("op.embed.saved", new Object[] {saved.label()},
                    "Saved.", LocaleContextHolder.getLocale()));
        } catch (EmbedHostService.Invalid invalid) {
            redirect.addFlashAttribute("error", message("op.embed.invalid." + invalid.code(), invalid.getMessage()));
        }
        return "redirect:" + SCREEN;
    }

    /** Withdraws approval; the sites that had switched it on lose it on their next page. */
    @PostMapping(SCREEN + "/remove")
    public String remove(@RequestParam String id, Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        if (hosts.remove(id)) {
            activity.record(authentication.getName(), "platform.embed-host-removed", id);
            redirect.addFlashAttribute("flash", message("op.embed.removed", "Removed."));
        }
        return "redirect:" + SCREEN;
    }

    private String message(String key, String fallback) {
        return messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }
}
