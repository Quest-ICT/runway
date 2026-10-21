package mx.quest.runway.console;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.page.FragmentException;
import mx.quest.runway.page.FragmentTemplates;
import mx.quest.runway.site.SiteResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The tenant console's <b>Fragments</b> screen ({@code /__admin/fragments}): a site manager lists, creates,
 * edits, reloads and deletes the site's reusable chrome partials — the shared layout {@code _master} and
 * any include such as {@code _head}, {@code _scripts}, {@code _nav} — over {@link FragmentTemplates}. These
 * are the {@code _}-prefixed templates the masters and pages pull by name; they are never routable and never
 * appear in the Pages list.
 *
 * <p><b>Error pages.</b> The same screen manages the site's own pages for an error status ({@code _404},
 * {@code _500}: {@code _} and a status from 400 to 599), which {@link mx.quest.runway.web.ErrorPages} renders
 * through the master. They are created from the site's {@code _master} with starter text for the status, and
 * edited, saved and deleted through the same editor and URLs as a fragment; the name decides which rule applies.
 * A fragment can still never be named like an error page.
 *
 * <p><b>Editor reuse.</b> The edit view reuses the page editor's self-hosted CodeMirror bundle (an HTML pane
 * over {@code textarea[data-cm]} and an XML pane for the optional {@code .th.xml}) via the layout's
 * {@code contentScripts} seam, so fragment authoring gets the same editing surface as page authoring without
 * a second bundle.
 *
 * <p><b>Immediate apply.</b> Every mutation ({@code save}/{@code create}/{@code delete}/{@code reload}) clears
 * the whole site chrome cache in {@link FragmentTemplates} — a fragment is inlined into its consuming masters
 * and pages, so a full clear is what actually refreshes them — and the change is live
 * on the next render.
 *
 * <p><b>Isolation.</b> The edited tenancy is <em>always</em> the bound one ({@link SiteResolver#current()}),
 * never a path/param id, and the fragment name is confined under the site's own {@code templates/} directory
 * (traversal-safe, error pages rejected). A crafted request can therefore only ever touch the current site's
 * chrome, never another site's or the application's files.
 *
 * <p>Gated by the grantable {@code CONFIGURE_SITE} permission — the gate Site config / SEO / Icons already use
 * here.
 */
@Controller
public class TenantFragmentsController {

    private final SiteResolver siteResolver;
    private final FragmentTemplates fragments;
    private final MessageSource messages;
    private final mx.quest.runway.page.MasterTemplates masters;

    /** The statuses a site most often wants its own page for, offered when creating one. */
    static final List<Integer> SUGGESTED_ERROR_CODES = List.of(404, 410, 403, 500);

    public TenantFragmentsController(SiteResolver siteResolver, FragmentTemplates fragments,
                                     @Qualifier("operatorAwareMessageSource") MessageSource messages,
                                     mx.quest.runway.page.MasterTemplates masters) {
        this.masters = masters;
        this.siteResolver = siteResolver;
        this.fragments = fragments;
        this.messages = messages;
    }

    @GetMapping("/__admin/fragments")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String list(Model model) {
        String siteId = currentSiteId();
        model.addAttribute("fragments", fragments.list(siteId));
        List<FragmentTemplates.FragmentInfo> errorPages = fragments.errorPages(siteId);
        Map<String, String> reasons = new LinkedHashMap<>();
        for (FragmentTemplates.FragmentInfo page : errorPages) {
            int code = Integer.parseInt(page.name().substring(1));
            HttpStatus status = HttpStatus.resolve(code);
            reasons.put(page.name(), code + (status == null ? "" : " " + status.getReasonPhrase()));
        }
        model.addAttribute("errorPages", errorPages);
        model.addAttribute("errorReasons", reasons);
        model.addAttribute("suggestedErrorCodes", SUGGESTED_ERROR_CODES.stream()
                .filter(code -> !reasons.containsKey("_" + code)).toList());
        model.addAttribute("contentTemplate", "operator/admin/fragments");
        model.addAttribute("pageTitleKey", "op.fragments.title");
        return "operator/op-layout";
    }

    /** The blank create form (a fresh fragment name + empty editors). */
    @GetMapping("/__admin/fragments/new")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String newForm(Model model) {
        editModel(model, "", "", "", true);
        return "operator/op-layout";
    }

    /** The edit form for an existing fragment; 404 if the name is unsafe or the fragment is absent. */
    @GetMapping("/__admin/fragments/{name}/edit")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String edit(@PathVariable String name, Model model) {
        String siteId = currentSiteId();
        if (FragmentTemplates.isErrorPageName(name)) {
            if (!fragments.errorPageExists(siteId, name)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such error page");
            }
            editModel(model, name, fragments.readErrorPageHtml(siteId, name).orElse(""),
                    fragments.readErrorPageLogic(siteId, name).orElse(""), false);
            return "operator/op-layout";
        }
        if (!fragments.exists(siteId, name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such fragment");
        }
        editModel(model, name, fragments.readHtml(siteId, name).orElse(""),
                fragments.readLogic(siteId, name).orElse(""), false);
        return "operator/op-layout";
    }

    /**
     * Creates the site's page for error {@code code} (400–599) from its {@code _master}, with starter text for the
     * status, and opens it in the editor. Without a master it is a plain standalone page. An existing one is
     * refused.
     */
    @PostMapping("/__admin/fragments/error-pages")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String createErrorPage(@RequestParam(required = false) String code, RedirectAttributes redirect) {
        String siteId = currentSiteId();
        String name = "_" + (code == null ? "" : code.trim());
        try {
            if (!FragmentTemplates.isErrorPageName(name)) {
                throw new FragmentException("bad-status", "not an error status: " + code);
            }
            int status = Integer.parseInt(name.substring(1));
            String[] text = starterText(status);
            String content = "  <h1>" + text[0] + "</h1>\n  <p>" + text[1] + "</p>\n"
                    + "  <p><a href=\"/\">" + text[2] + "</a></p>\n";
            Optional<String> master = masters.readHtml(siteId, "_master");
            String html;
            String thxml;
            if (master.isPresent()) {
                mx.quest.runway.page.PageScaffold.Scaffold scaffold =
                        mx.quest.runway.page.PageScaffold.fromMaster(master.get(), status + " " + text[0], content);
                html = scaffold.html();
                thxml = scaffold.thxml();
            } else {
                html = "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<title>" + status + " "
                        + text[0] + "</title>\n</head>\n<body>\n<main>\n" + content + "</main>\n</body>\n</html>\n";
                thxml = null;
            }
            fragments.createErrorPage(siteId, name, html, thxml);
            redirect.addFlashAttribute("flash", message("op.fragments.errors.created", "Error page created."));
            return "redirect:/__admin/fragments/" + name + "/edit";
        } catch (FragmentException e) {
            redirect.addFlashAttribute("error", errorMessage(e));
            return "redirect:/__admin/fragments";
        }
    }

    /**
     * Starter heading, sentence and home-link text for an error page, in plain English for the site manager to
     * rewrite in the site's own words and languages (they are the site's content, not console text).
     */
    static String[] starterText(int status) {
        return switch (status) {
            case 404 -> new String[] {"Page not found",
                    "The page you are looking for is not here. It may have moved, or the address may be mistyped.",
                    "Go to the home page"};
            case 410 -> new String[] {"Page removed", "This page has been removed and is not coming back.",
                    "Go to the home page"};
            case 403 -> new String[] {"Access denied", "You do not have access to this page.", "Go to the home page"};
            case 500 -> new String[] {"Something went wrong",
                    "We could not show this page. Please try again in a few minutes.", "Go to the home page"};
            default -> {
                HttpStatus known = HttpStatus.resolve(status);
                yield new String[] {known == null ? "Error" : known.getReasonPhrase(),
                        "This page cannot be shown right now.", "Go to the home page"};
            }
        };
    }

    /** Creates a new fragment (rejects an existing name); on success redirects to its editor. */
    @PostMapping("/__admin/fragments")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String create(@RequestParam String name, @RequestParam(required = false) String html,
                         @RequestParam(required = false) String thxml, RedirectAttributes redirect) {
        String siteId = currentSiteId();
        try {
            fragments.create(siteId, name, html == null ? "" : html, thxml);
            redirect.addFlashAttribute("flash", message("op.fragments.created", "Fragment created."));
            return "redirect:/__admin/fragments/" + name + "/edit";
        } catch (FragmentException e) {
            redirect.addFlashAttribute("error", errorMessage(e));
            return "redirect:/__admin/fragments/new";
        }
    }

    /** Saves (overwrites) an existing fragment's source; a bad name is a flash error, never a 500. */
    @PostMapping("/__admin/fragments/{name}")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String save(@PathVariable String name, @RequestParam(required = false) String html,
                       @RequestParam(required = false) String thxml, RedirectAttributes redirect) {
        String siteId = currentSiteId();
        try {
            if (FragmentTemplates.isErrorPageName(name)) {
                if (!fragments.errorPageExists(siteId, name)) {
                    throw new FragmentException("no-such-fragment", "no such error page: " + name);
                }
                fragments.saveErrorPage(siteId, name, html == null ? "" : html, thxml);
            } else {
                fragments.save(siteId, name, html == null ? "" : html, thxml);
            }
            redirect.addFlashAttribute("flash", message("op.fragments.saved", "Fragment saved."));
            return "redirect:/__admin/fragments/" + name + "/edit";
        } catch (FragmentException e) {
            redirect.addFlashAttribute("error", errorMessage(e));
            return "redirect:/__admin/fragments";
        }
    }

    /** Deletes a fragment, then returns to the list. */
    @PostMapping("/__admin/fragments/{name}/delete")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String delete(@PathVariable String name, RedirectAttributes redirect) {
        boolean removed = FragmentTemplates.isErrorPageName(name)
                ? fragments.deleteErrorPage(currentSiteId(), name)
                : fragments.delete(currentSiteId(), name);
        redirect.addFlashAttribute(removed ? "flash" : "error", removed
                ? message("op.fragments.deleted", "Fragment deleted.")
                : message("op.fragments.deleteFailed", "No such fragment."));
        return "redirect:/__admin/fragments";
    }

    /** Reload: clears the site's chrome cache so an out-of-band edit takes effect on the next render. */
    @PostMapping("/__admin/fragments/{name}/reload")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String reload(@PathVariable String name, RedirectAttributes redirect) {
        fragments.reload(currentSiteId(), name);
        redirect.addFlashAttribute("flash", message("op.fragments.reloaded", "Fragment reloaded."));
        return "redirect:/__admin/fragments";
    }

    /**
     * Populates the shared model the fragment editor needs (name, sources, create-vs-edit flag). The form
     * action is computed <b>here</b> and passed as a value: a {@code /__admin/…} literal inside a Thymeleaf
     * expression would trip the {@code __…__} preprocessing pass, so the template
     * only ever binds the string value, never a literal it must concatenate.
     */
    private void editModel(Model model, String name, String html, String thxml, boolean creating) {
        model.addAttribute("name", name);
        model.addAttribute("body", html);
        model.addAttribute("logic", thxml);
        model.addAttribute("creating", creating);
        model.addAttribute("formAction", creating ? "/__admin/fragments" : "/__admin/fragments/" + name);
        model.addAttribute("contentTemplate", "operator/admin/fragment-editor");
        model.addAttribute("contentScripts", "operator/admin/fragment-editor");
        model.addAttribute("pageTitleKey", creating ? "op.fragments.new.title" : "op.fragments.edit.title");
    }

    private String errorMessage(FragmentException e) {
        return message("op.fragments.error." + e.code(), e.getMessage());
    }

    private String currentSiteId() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy")).id();
    }

    private String message(String key, String fallback) {
        return messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }
}
