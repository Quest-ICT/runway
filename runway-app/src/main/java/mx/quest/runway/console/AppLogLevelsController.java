package mx.quest.runway.console;

import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.platform.LogLevelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Activity → Logs → <b>Log levels</b> ({@code /__app/logs/levels}): how much each logger writes to the server log,
 * changed at once and kept across restarts ({@link LogLevelService}). Platform administrators only, and every change
 * is recorded in the activity log, because DEBUG and TRACE can write request details to the server log.
 */
@Controller
public class AppLogLevelsController {

    private static final Logger log = LoggerFactory.getLogger(AppLogLevelsController.class);

    private static final String SCREEN = "/__app/logs/levels";

    private final LogLevelService levels;
    private final PlatformAuthority platformAuthority;
    private final AppActivityLog activity;
    private final MessageSource messages;

    public AppLogLevelsController(LogLevelService levels, PlatformAuthority platformAuthority,
                                  AppActivityLog activity,
                                  @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.levels = levels;
        this.platformAuthority = platformAuthority;
        this.activity = activity;
        this.messages = messages;
    }

    @GetMapping(SCREEN)
    public String list(Authentication authentication, Model model) {
        platformAuthority.require(authentication);
        model.addAttribute("rows", levels.rows());
        model.addAttribute("levels", LogLevelService.LEVELS);
        model.addAttribute("contentTemplate", "operator/app/log-levels");
        model.addAttribute("pageTitleKey", "op.loglevels.title");
        return "operator/op-layout";
    }

    /** Sets one logger's level ({@code level} blank: back to Default). */
    @PostMapping(SCREEN)
    public String set(@RequestParam(required = false) String logger, @RequestParam(required = false) String level,
                      Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        try {
            levels.set(logger, level);
            String shown = level == null || level.isBlank() ? "default" : level.strip().toUpperCase();
            activity.record(authentication.getName(), "platform.log-level-changed", logger.strip() + " " + shown);
            redirect.addFlashAttribute("flash", messages.getMessage("op.loglevels.saved",
                    new Object[] {logger.strip(), shown}, "Saved.", LocaleContextHolder.getLocale()));
        } catch (LogLevelService.Invalid invalid) {
            redirect.addFlashAttribute("error", messages.getMessage("op.loglevels.invalid." + invalid.code(), null,
                    invalid.getMessage(), LocaleContextHolder.getLocale()));
        } catch (RuntimeException failed) {
            // The logging system or the settings file refused: say so instead of a 500.
            log.warn("could not set the log level of {} to {}: {}", logger, level, failed.toString());
            redirect.addFlashAttribute("error", messages.getMessage("op.loglevels.failed", null,
                    "The log level could not be set. The server log says why.", LocaleContextHolder.getLocale()));
        }
        return "redirect:" + SCREEN;
    }
}
