package mx.quest.runway.spi.service;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a plugin {@code @Controller} whose screens render <b>inside the operator console shell</b> — the topbar,
 * the menus, the language switcher and the footer, exactly as a kernel screen. The host's own advice fills the shell's
 * model for every request handled by a class carrying this annotation; the plugin needs no
 * {@code @ControllerAdvice} of its own and no host class.
 *
 * <h2>How a screen uses it</h2>
 * <pre>{@code
 * @Controller
 * @ConsoleScreen
 * public class BlogAdminController {
 *     @GetMapping("/__admin/blog")
 *     @PreAuthorize("@authz.may('blog|manage')")
 *     public String list(Model model) {
 *         model.addAttribute(ConsoleScreen.CONTENT_TEMPLATE, "__plugin__/blog/admin-list");
 *         model.addAttribute(ConsoleScreen.PAGE_TITLE_KEY, "blog.admin.title");
 *         return ConsoleScreen.LAYOUT_VIEW;
 *     }
 * }
 * }</pre>
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Every mapping lies under {@code /__admin/<pluginId>} (the tenant console) or {@code /__app/<pluginId>} (the
 *       application console), so the console's realm security and CSRF protection apply unchanged. Gate each handler
 *       with {@link AccessCheck} ({@code @PreAuthorize("@authz.may('…')")}).</li>
 *   <li>The content template ({@code __plugin__/<pluginId>/<name>}) may rely on these model variables:
 *       {@code tenantSiteId} (the bound site's id on the tenant console, {@code null} on the application console)
 *       and {@code _csrf}; on the expressions {@code ${@<own bean>…}} and {@code ${@authz.may('…')}}; and on
 *       {@code #{…}} messages from the plugin's bundle. Nothing else in the shell's model is promised.</li>
 * </ul>
 *
 * @since SPI 0.6.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConsoleScreen {

    /** The view name a console screen returns: the shell layout that wraps the content template. */
    String LAYOUT_VIEW = "operator/op-layout";

    /** The model attribute naming the screen's content template ({@code __plugin__/<pluginId>/<template>}). */
    String CONTENT_TEMPLATE = "contentTemplate";

    /** The model attribute naming the message key of the screen's title (the browser tab and the heading). */
    String PAGE_TITLE_KEY = "pageTitleKey";
}
