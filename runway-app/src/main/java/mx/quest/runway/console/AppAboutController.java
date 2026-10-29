package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The application-realm <b>About Runway</b> screen ({@code /__app/about}) — a read-only view of the running
 * platform: the version, the curated effective configuration, the runtime environment of the node serving
 * the request, and that request as Runway sees it (the client address behind a proxy). Rendered through the
 * {@code op-layout} operator shell.
 *
 * <p>Unlike the sibling <b>Platform settings</b> page ({@link AppSettingsController}), About mutates nothing and
 * exposes no secret, so it carries <b>no platform-admin gate</b>: the {@code /__app/**} security chain already
 * restricts it to authenticated application operators, which is exactly the intended audience. All values come
 * from {@link RuntimeInfoService} (the controller stays free of business logic).
 */
@Controller
public class AppAboutController {

    private final RuntimeInfoService info;

    public AppAboutController(RuntimeInfoService info) {
        this.info = info;
    }

    @GetMapping("/__app/about")
    public String about(Model model, HttpServletRequest request) {
        model.addAttribute("appVersion", info.version());
        model.addAttribute("buildTime", info.buildTime());
        model.addAttribute("effectiveConfig", info.effectiveConfig());
        model.addAttribute("runtimeInfo", info.runtime());
        model.addAttribute("componentInfo", info.components());
        model.addAttribute("requestInfo", info.request(request));
        model.addAttribute("contentTemplate", "operator/app/about");
        model.addAttribute("pageTitleKey", "op.about.title");
        return "operator/op-layout";
    }
}
