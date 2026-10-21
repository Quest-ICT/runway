package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.log.SiteLogConfigService;
import mx.quest.runway.platform.KernelSettings;
import mx.quest.runway.platform.KernelSettingsService;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.SettingType;
import mx.quest.runway.plugin.SettingValidationException;
import mx.quest.runway.site.SiteStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The kernel's own settings forms ({@code /__app/config/{screen}}): Settings → Server settings, and the policy cards
 * of Backups, Bandwidth, Activity → Logs and Plugins. The same settings form as a plugin's (sections, help, Advanced,
 * show-when), over {@link KernelSettings}; a field the server itself sets is shown locked with where it is set.
 * A platform operation: gated to the master and wildcard admins ({@link PlatformAuthority}), like Platform settings.
 */
@Controller
public class KernelSettingsController {

    private final KernelSettingsService settings;
    private final PlatformAuthority platformAuthority;
    private final AppActivityLog activity;
    private final SiteStore sites;
    private final SiteLogConfigService siteLogs;
    private final MessageSource messages;

    public KernelSettingsController(KernelSettingsService settings, PlatformAuthority platformAuthority,
                                    AppActivityLog activity, SiteStore sites, SiteLogConfigService siteLogs,
                                    @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.messages = messages;
        this.sites = sites;
        this.siteLogs = siteLogs;
        this.settings = settings;
        this.platformAuthority = platformAuthority;
        this.activity = activity;
    }

    @GetMapping("/__app/config/{screen}")
    public String form(@PathVariable String screen, Authentication authentication, Model model) {
        platformAuthority.require(authentication);
        requireScreen(screen);
        Map<String, String> locks = settings.locks(screen);
        Map<String, KernelSettingsService.LockState> states = settings.lockStates(screen);
        List<SettingSection> sections = new ArrayList<>();
        for (SettingSection section : SettingsForm.build(settings.render(screen), KernelSettings.descriptor(),
                key -> null)) {
            sections.add(new SettingSection(section.label(), section.help(), section.helpLink(),
                    locked(section.fields(), locks, states), locked(section.advanced(), locks, states),
                    section.advancedOpen(),
                    section.advancedShowWhen(), section.advancedHidden()));
        }
        model.addAttribute("pluginName", null);
        model.addAttribute("settingsTitleKey", "op.config." + screen + ".title");
        model.addAttribute("sections", sections);
        model.addAttribute("fields", sections.stream().flatMap(s -> s.fields().stream()).toList());
        model.addAttribute("pendingKeys", settings.pendingKeys(screen));
        model.addAttribute("strayKeys", List.of());
        model.addAttribute("settingsAction", "/__app/config/" + screen);
        model.addAttribute("adoptAction", locks.isEmpty() ? null : "/__app/config/" + screen + "/adopt");
        model.addAttribute("settingsBack", backOf(screen));
        model.addAttribute("settingsScope", "app");
        model.addAttribute("contentTemplate", "operator/plugin-settings");
        model.addAttribute("pageTitleKey", "op.config." + screen + ".title");
        return "operator/op-layout";
    }

    @PostMapping("/__app/config/{screen}")
    public String save(@PathVariable String screen, HttpServletRequest request, Authentication authentication,
                       RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        requireScreen(screen);
        Map<String, String> submitted = new LinkedHashMap<>();
        for (PluginSettingDef def : KernelSettings.forScreen(screen)) {
            String value = request.getParameter(def.key());
            // An unchecked checkbox submits nothing: that is "false", not "the default".
            if (value == null && SettingType.of(def.type()) == SettingType.BOOLEAN) {
                value = "false";
            }
            submitted.put(def.key(), value);
        }
        try {
            settings.save(screen, submitted);
            activity.record(authentication.getName(), "platform.kernel-settings-changed", screen);
            redirect.addFlashAttribute("flash", messages.getMessage("op.config.kernel.saved", null,
                    "Saved. The changes apply after the next restart.", LocaleContextHolder.getLocale())
                    + sitesAboveLogCeiling());
        } catch (KernelSettingsService.PlaceholderRefused refused) {
            var locale = LocaleContextHolder.getLocale();
            String label = messages.getMessage("op.ks." + refused.key(), null, refused.key(), locale);
            redirect.addFlashAttribute("error", messages.getMessage("op.config.error.placeholder",
                    new Object[] {label}, label + ": " + refused.getMessage(), locale));
        } catch (SettingValidationException invalid) {
            redirect.addFlashAttribute("error", refusal(invalid));
        }
        return "redirect:/__app/config/" + screen;
    }

    /**
     * "Use the file's value" on a locked field: copies the value the server runs with for {@code key} into the
     * console's store ({@link KernelSettingsService#adopt}), so the operator can then remove the host's line and
     * restart once with nothing changing. Same gate and CSRF protection as a save, and recorded in the activity log
     * by key (never the value). The field stays locked: the host's value still wins until its line is removed.
     */
    @PostMapping("/__app/config/{screen}/adopt")
    public String adopt(@PathVariable String screen, @RequestParam("key") String key, Authentication authentication,
                        RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        requireScreen(screen);
        if (KernelSettings.forScreen(screen).stream().noneMatch(def -> def.key().equals(key))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such setting on this screen");
        }
        var locale = LocaleContextHolder.getLocale();
        String label = messages.getMessage("op.ks." + key, null, key, locale);
        String origin = settings.locks(screen).getOrDefault(key, "");
        try {
            KernelSettingsService.Adopted adopted = settings.adopt(key);
            activity.record(authentication.getName(), "platform.kernel-setting-adopted", key);
            String code = adopted == KernelSettingsService.Adopted.STORED ? "op.config.adopt.stored"
                    : "op.config.adopt.default";
            redirect.addFlashAttribute("flash", messages.getMessage(code, new Object[] {label, origin},
                    label + ": copied.", locale));
        } catch (KernelSettingsService.PlaceholderRefused refused) {
            redirect.addFlashAttribute("error", messages.getMessage("op.config.error.placeholder",
                    new Object[] {label}, label + ": " + refused.getMessage(), locale));
        } catch (SettingValidationException invalid) {
            redirect.addFlashAttribute("error", refusal(invalid));
        }
        return "redirect:/__app/config/" + screen;
    }

    /**
     * Why a value was refused, in the operator's language: the field's label, then the detail from the refusal's
     * message code ({@code op.config.invalid.*}). A refusal without a code, or a code with no message, shows its
     * English text, so the operator always learns which field and why.
     */
    private String refusal(SettingValidationException invalid) {
        var locale = LocaleContextHolder.getLocale();
        String label = messages.getMessage("op.ks." + invalid.key(), null, invalid.key(), locale);
        String detail = invalid.code() == null ? invalid.getMessage()
                : messages.getMessage(invalid.code(), invalid.args(), invalid.getMessage(), locale);
        return messages.getMessage("op.config.kernel.invalid", new Object[] {label, detail}, label + ": " + detail,
                locale);
    }

    /**
     * When the sites' log retention ceiling is below what some sites chose, says how many: they keep their value and
     * are held to the ceiling, and each sees a note on its own Logs screen (a ceiling warns,
     * clamps and never deletes on the save itself).
     */
    private String sitesAboveLogCeiling() {
        int ceiling;
        try {
            ceiling = Integer.parseInt(String.valueOf(settings.upcoming("runway.logs.max-retention-days")).trim());
        } catch (NumberFormatException e) {
            return "";
        }
        long above = sites.findAll().stream()
                .map(site -> siteLogs.get(site.id()).retentionDays())
                .filter(days -> days != null && days > ceiling)
                .count();
        return above == 0 ? "" : " " + messages.getMessage("op.config.kernel.logCeiling",
                new Object[] {above, String.valueOf(ceiling)}, "Some sites keep logs longer than the ceiling.",
                LocaleContextHolder.getLocale());
    }

    /**
     * {@code fields} with the host-set ones locked: each says where the host sets it and what removing that would do,
     * and, when removing it would change something, offers to copy the server's value into the console first.
     */
    private static List<SettingField> locked(List<SettingField> fields, Map<String, String> locks,
                                             Map<String, KernelSettingsService.LockState> states) {
        List<SettingField> out = new ArrayList<>(fields.size());
        for (SettingField field : fields) {
            String origin = locks.get(field.key());
            if (origin == null) {
                out.add(field);
                continue;
            }
            KernelSettingsService.LockState state = states.get(field.key());
            boolean offer = state == KernelSettingsService.LockState.NONE
                    || state == KernelSettingsService.LockState.DIFFERS;
            String button = !offer ? null
                    : HOST_FILE.equals(origin) ? "op.settings.adopt.file" : "op.settings.adopt.server";
            out.add(field.lockedBy(origin, state == null ? null : state.key(), button));
        }
        return out;
    }

    /** How {@code HostSettings} names the host's own file; any other origin is "the server". */
    private static final String HOST_FILE = "external/application.yml";

    private static void requireScreen(String screen) {
        if (!KernelSettings.screens().contains(screen)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such settings screen");
        }
    }

    /** Where the form's Back link goes: the screen the card belongs to. */
    private static String backOf(String screen) {
        return switch (screen) {
            case "backups" -> "/__app/backups";
            case "bandwidth" -> "/__app/bandwidth";
            case "activity" -> "/__app/logs";
            case "plugins" -> "/__app/plugins";
            default -> "/__app/settings";
        };
    }
}
