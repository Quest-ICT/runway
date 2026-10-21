package mx.quest.runway.console;

import java.io.IOException;
import java.io.InputStream;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.plugin.PluginAdminService;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.wizard.Restarter;
import org.springframework.security.core.Authentication;
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
 * The plugin-management screen (the operator console's headline) and its actions, app-realm only
 * ({@code /__app/plugins}). The screen lists every install folder with its drift badge and the restart-required
 * banner, each plugin's health this boot (contained failures, refused contributions, compliance warnings, the
 * "uses host internals" badge); the actions install (upload), enable/disable, retry a quarantined plugin,
 * uninstall, and restart-to-apply.
 *
 * <p><b>Authority.</b> Plugin management is a <b>platform operation</b> reserved to the application master or a
 * wildcard admin — checked here through {@link PlatformAuthority} (the shared master/wildcard gate), <b>not</b>
 * a grantable permission (the master-only ops are a target-based guard, not a permission anyone can be
 * granted). The read-only listing is open to any authenticated app operator (the chain already restricts to
 * {@code SYSTEM_*}); every <b>mutation</b> (and the restart) re-checks the gate and answers {@code 403}
 * otherwise — proven by test.
 *
 * <p>Mutations change only the {@code external/plugins} tree and apply on the <b>next restart</b> (the locked
 * restart-to-install decision); the restart action triggers the supervised relaunch ({@link Restarter}) and
 * returns a standalone interstitial that meta-refreshes to the console once the fresh JVM is up.
 */
@Controller
@RequestMapping("/__app/plugins")
public class PluginAdminController {

    private final PluginAdminService plugins;
    private final PlatformAuthority platformAuthority;
    private final Restarter restarter;
    private final AppActivityLog activity;

    public PluginAdminController(PluginAdminService plugins, PlatformAuthority platformAuthority,
                                 Restarter restarter, AppActivityLog activity) {
        this.plugins = plugins;
        this.platformAuthority = platformAuthority;
        this.restarter = restarter;
        this.activity = activity;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("plugins", plugins.list());
        model.addAttribute("restartRequired", plugins.restartRequired());
        model.addAttribute("contentTemplate", "operator/app/plugins");
        model.addAttribute("pageTitleKey", "op.plugins.title");
        return "operator/op-layout";
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file, Authentication authentication,
                         RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        if (file == null || file.isEmpty()) {
            redirect.addFlashAttribute("error", "No file was uploaded.");
            return "redirect:/__app/plugins";
        }
        try (InputStream in = file.getInputStream()) {
            PluginDescriptor descriptor = plugins.install(in);
            activity.record(authentication.getName(), "plugin.installed", descriptor.id() + " v" + descriptor.version());
            redirect.addFlashAttribute("flash",
                    "Installed " + descriptor.id() + " v" + descriptor.version() + " — restart to apply.");
        } catch (IOException | RuntimeException e) {
            redirect.addFlashAttribute("error", "Install failed: " + rootMessage(e));
        }
        return "redirect:/__app/plugins";
    }

    @PostMapping("/{id}/enable")
    public String enable(@PathVariable String id, Authentication authentication, RedirectAttributes redirect) {
        return setEnabled(id, true, authentication, redirect);
    }

    @PostMapping("/{id}/disable")
    public String disable(@PathVariable String id, Authentication authentication, RedirectAttributes redirect) {
        return setEnabled(id, false, authentication, redirect);
    }

    private String setEnabled(String id, boolean enabled, Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        try {
            plugins.setEnabled(id, enabled);
            activity.record(authentication.getName(), enabled ? "plugin.enabled" : "plugin.disabled", id);
            redirect.addFlashAttribute("flash",
                    "Plugin '" + id + "' " + (enabled ? "enabled" : "disabled") + " — restart to apply.");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", rootMessage(e));
        }
        return "redirect:/__app/plugins";
    }

    /**
     * Clears a quarantined plugin's marker so the next boot tries it again. A platform
     * operation like every other mutation here; applies on restart.
     */
    @PostMapping("/{id}/retry")
    public String retry(@PathVariable String id, Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        try {
            if (plugins.retry(id)) {
                activity.record(authentication.getName(), "plugin.retried", id);
                redirect.addFlashAttribute("flash", "Quarantine cleared for '" + id
                        + "' — it will be tried again on the next restart.");
            } else {
                redirect.addFlashAttribute("flash", "'" + id + "' was not quarantined.");
            }
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", rootMessage(e));
        }
        return "redirect:/__app/plugins";
    }

    @PostMapping("/{id}/uninstall")
    public String uninstall(@PathVariable String id, Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        try {
            plugins.uninstall(id);
            activity.record(authentication.getName(), "plugin.uninstalled", id);
            redirect.addFlashAttribute("flash", "Uninstalled '" + id + "' — restart to apply.");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", rootMessage(e));
        }
        return "redirect:/__app/plugins";
    }

    @PostMapping("/restart")
    public String restart(Authentication authentication, Model model) {
        platformAuthority.require(authentication);
        activity.record(authentication.getName(), "platform.restart", "");
        restarter.restart(); // schedules a supervised relaunch on a background thread after this response flushes
        model.addAttribute("pageTitleKey", "op.plugins.restart.title");
        return "operator/restarting";
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }
}
