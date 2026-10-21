package mx.quest.demo;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.spi.RunwaySpi;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.InstallResult;
import mx.quest.runway.spi.model.PluginInfo;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.model.StarterPage;
import mx.quest.runway.spi.service.AccessCheck;
import mx.quest.runway.spi.service.ConsoleScreen;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.RequestContext;
import mx.quest.runway.spi.service.SitePages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The demo's own screen inside the tenant console, {@value #BASE} — a {@code @Controller} annotated
 * {@link ConsoleScreen}. <b>When to use {@code @ConsoleScreen}:</b> whenever a plugin needs more than the settings form
 * and the admin fragment below it — a list to manage, an action to run. The kernel fills the console shell (topbar,
 * menus, language switcher, footer) for every request this class handles, so the plugin needs no
 * {@code @ControllerAdvice} and no host class. A handler sets {@link ConsoleScreen#CONTENT_TEMPLATE} and
 * {@link ConsoleScreen#PAGE_TITLE_KEY} and returns {@link ConsoleScreen#LAYOUT_VIEW}.
 *
 * <p><b>The rules this class follows:</b>
 * <ul>
 *   <li>Every mapping lies under {@code /__admin/runway-demo} (the kernel refuses a plugin controller elsewhere), so
 *       the tenant console's sign-in, realm security and CSRF protection apply unchanged.</li>
 *   <li>Every handler is gated with {@code @PreAuthorize("@authz.may('runway-demo|manage-notes')")} — the
 *       {@link AccessCheck} bean under its expression name. The menu entry's {@code permission:} only hides the
 *       link.</li>
 *   <li>Everything that <b>changes state is a {@code POST}</b> from a form carrying the console's CSRF token
 *       ({@code ${_csrf}}); the {@code GET} only reads, so a link or an {@code <img>} on another web site can never
 *       trigger an action through a signed-in operator's browser. Each {@code POST} redirects back (post/redirect/get)
 *       with a flash message, so a reload never repeats it. Spring MVC binds the form fields, so no SPI type is
 *       needed to read them.</li>
 * </ul>
 *
 * <p>It is also where four services are used in their natural place:
 * <ul>
 *   <li>{@link RequestContext#site()} — <b>which site this console request manages</b>. A console screen has no
 *       {@code RouteRequest}; this is how it learns the tenancy. It is empty on the application console
 *       ({@code /__app}), which this screen does not serve (404).</li>
 *   <li>{@link Messages#console} — console text in the <b>operator's</b> UI language (the flash messages), as opposed
 *       to {@link Messages#site}, which is for what a site's visitors read.</li>
 *   <li>{@link SitePages#install} — "install my starter page" through the site's editorial rules: never overwrites,
 *       never publishes around an Enforced or Reviewed site, and reports one of four outcomes the operator is told in
 *       words. The plugin checks the caller may edit pages first ({@link AccessCheck#may} with the kernel permission
 *       {@code EDIT_PAGES}).</li>
 *   <li>{@link ContentCatalogView} — <b>the site's published content surface</b> with the URLs the host computed:
 *       {@code entries} (what a sitemap or search index lists), {@code pageAt} (which published page owns a path),
 *       {@code servablePath} (may content live at a path at all) and {@code refresh} (a manual reindex).</li>
 * </ul>
 */
@Controller
@ConsoleScreen
@RequestMapping(DemoConsoleController.BASE)
public class DemoConsoleController {

    static final String BASE = "/__admin/runway-demo";
    static final String GATE = "@authz.may('runway-demo|manage-notes')";
    static final String CONTENT = "__plugin__/runway-demo/console";
    static final String TITLE_KEY = "runwaydemo.console.title";
    /** The starter page's slug and path. */
    static final String STARTER_SLUG = "runway-demo";
    static final String STARTER_PATH = "/demo";
    /** The flash attributes this screen renders itself (the shell's own flash region is not promised to plugins). */
    static final String FLASH = "runwayDemoFlash";
    static final String FLASH_ERROR = "runwayDemoError";
    /** The most catalog rows the screen lists. */
    static final int CATALOG_ROWS = 50;

    private static final Logger log = LoggerFactory.getLogger(DemoConsoleController.class);

    /** One row of the content-surface table. */
    public record CatalogRow(String source, String id, String path, boolean own) {
    }

    /** The answer of the path check: servable at all, and the published page that owns it, if any. */
    public record PathCheck(String path, boolean safe, boolean servable, String ownerSlug) {
    }

    /** Everything the screen shows, built per request. */
    public record Screen(SiteView site, String pluginVersion, String builtSpi, String runningSpi,
                         int noteCount, List<Note> notes, String showcaseText, String showcasePath,
                         String starterPath, int catalogTotal, List<CatalogRow> catalog,
                         List<DemoActivity.Entry> activity, PathCheck pathCheck) {
    }

    private final RequestContext request;
    private final AccessCheck access;
    private final Messages messages;
    private final SitePages pages;
    private final ContentCatalogView catalog;
    private final DemoNotesStore notes;
    private final DemoShowcaseStore showcase;
    private final DemoActivity activity;
    private final PluginInfo self;

    public DemoConsoleController(RequestContext request, AccessCheck access, Messages messages, SitePages pages,
                                 ContentCatalogView catalog, DemoNotesStore notes, DemoShowcaseStore showcase,
                                 DemoActivity activity, PluginInfo self) {
        this.request = request;
        this.access = access;
        this.messages = messages;
        this.pages = pages;
        this.catalog = catalog;
        this.notes = notes;
        this.showcase = showcase;
        this.activity = activity;
        this.self = self;
    }

    /** The screen. Read-only: it changes nothing, so it is a {@code GET}. {@code ?path=} runs the path check. */
    @GetMapping
    @PreAuthorize(GATE)
    public String screen(@RequestParam(name = "path", required = false) String path, Model model) {
        SiteView site = boundSite();
        String siteId = site.id();
        List<CatalogEntry> entries = catalog.entries(siteId);
        List<CatalogRow> rows = new ArrayList<>();
        for (CatalogEntry entry : entries.subList(0, Math.min(CATALOG_ROWS, entries.size()))) {
            rows.add(new CatalogRow(entry.sourceKey(), entry.ref().id(), entry.pathFor(site.defaultLocale()),
                    DemoShowcaseSource.SOURCE_KEY.equals(entry.sourceKey())));
        }
        Optional<Showcase> current = showcase.current(siteId);
        model.addAttribute("rd", new Screen(site, self.version(), RunwaySpi.VERSION,
                RunwaySpi.runtimeVersion(), notes.count(siteId), notes.recent(siteId, 10),
                current.map(Showcase::text).orElse(null), DemoShowcaseSource.PATH,
                pages.pathOf(siteId, STARTER_SLUG).orElse(""), entries.size(), rows, activity.recent(siteId),
                path == null || path.isBlank() ? null : check(site, path.strip())));
        model.addAttribute(ConsoleScreen.CONTENT_TEMPLATE, CONTENT);
        model.addAttribute(ConsoleScreen.PAGE_TITLE_KEY, TITLE_KEY);
        return ConsoleScreen.LAYOUT_VIEW;
    }

    private PathCheck check(SiteView site, String path) {
        try {
            boolean servable = catalog.servablePath(site.id(), path);
            String owner = catalog.pageAt(site.id(), path, site.defaultLocale()).orElse(null);
            return new PathCheck(path, true, servable, owner);
        } catch (IllegalArgumentException unsafe) {
            return new PathCheck(path, false, false, null);
        }
    }

    /** Adds an operator note (a field of the form body). */
    @PostMapping("/notes")
    @PreAuthorize(GATE)
    public String addNote(@RequestParam(name = "text", defaultValue = "") String text, RedirectAttributes flash) {
        String siteId = boundSite().id();
        if (text.isBlank()) {
            return back(flash, FLASH_ERROR, console("runwaydemo.console.note.blank", "Write something first."));
        }
        try {
            notes.append(siteId, text, Note.ORIGIN_CONSOLE);
        } catch (UncheckedIOException e) {
            log.warn("runway-demo could not add a note for site {}: {}", siteId, e.toString());
            return back(flash, FLASH_ERROR, console("runwaydemo.console.ioError", "The change could not be saved."));
        }
        return back(flash, FLASH, console("runwaydemo.console.note.added", "Note added."));
    }

    /** Publishes the showcase page; the store announces the change with {@code ContentChanges}. */
    @PostMapping("/showcase")
    @PreAuthorize(GATE)
    public String publishShowcase(@RequestParam(name = "text", defaultValue = "") String text,
                                  RedirectAttributes flash) {
        String siteId = boundSite().id();
        if (text.isBlank()) {
            return back(flash, FLASH_ERROR, console("runwaydemo.console.showcase.blank", "Write something first."));
        }
        try {
            showcase.publish(siteId, text);
        } catch (UncheckedIOException e) {
            log.warn("runway-demo could not publish the showcase of site {}: {}", siteId, e.toString());
            return back(flash, FLASH_ERROR, console("runwaydemo.console.ioError", "The change could not be saved."));
        }
        return back(flash, FLASH, console("runwaydemo.console.showcase.published", "Showcase published at {0}.",
                DemoShowcaseSource.PATH));
    }

    /** Unpublishes the showcase page; announced like a publish. */
    @PostMapping("/showcase/unpublish")
    @PreAuthorize(GATE)
    public String unpublishShowcase(RedirectAttributes flash) {
        String siteId = boundSite().id();
        boolean was;
        try {
            was = showcase.unpublish(siteId);
        } catch (UncheckedIOException e) {
            log.warn("runway-demo could not unpublish the showcase of site {}: {}", siteId, e.toString());
            return back(flash, FLASH_ERROR, console("runwaydemo.console.ioError", "The change could not be saved."));
        }
        return was
                ? back(flash, FLASH, console("runwaydemo.console.showcase.unpublished", "Showcase unpublished."))
                : back(flash, FLASH, console("runwaydemo.console.showcase.none", "No showcase was published."));
    }

    /** Installs the starter page carrying the demo's two widgets, through the site's editorial rules. */
    @PostMapping("/install-page")
    @PreAuthorize(GATE)
    public String installPage(RedirectAttributes flash) {
        SiteView site = boundSite();
        if (!access.may("EDIT_PAGES")) {
            return back(flash, FLASH_ERROR, console("runwaydemo.console.page.forbidden",
                    "Installing a page needs the permission to edit pages."));
        }
        String title = messages.site(site.id(), site.defaultLocale(), "runwaydemo.page.title", "Runway demo");
        StarterPage page = new StarterPage(STARTER_SLUG, STARTER_PATH,
                Map.of(site.defaultLocale().getLanguage(), title), starterHtml(title), starterLogic(), true);
        InstallResult result;
        try {
            result = pages.install(site.id(), page);
        } catch (RuntimeException e) {
            // IllegalStateException: another page serves the path, the master is missing, a write failed (no stray
            // draft is left behind). IllegalArgumentException would mean our own slug/path is unsafe — a bug here.
            log.warn("runway-demo starter page install failed for site {}: {}", site.id(), e.toString());
            return back(flash, FLASH_ERROR, console("runwaydemo.console.page.failed",
                    "The demo page could not be installed."));
        }
        return back(flash, FLASH, describe(result));
    }

    /** Drops the site's cached content listing — the "reindex" button; ordinary changes never need it. */
    @PostMapping("/catalog/refresh")
    @PreAuthorize(GATE)
    public String refreshCatalog(RedirectAttributes flash) {
        catalog.refresh(boundSite().id());
        return back(flash, FLASH, console("runwaydemo.console.catalog.refreshed", "Content listing refreshed."));
    }

    /** The operator's words for each install outcome; an outcome added in a later SPI MINOR gets a neutral line. */
    String describe(InstallResult result) {
        String path = result.path();
        return switch (result.outcome()) {
            case PUBLISHED -> console("runwaydemo.console.page.published", "Demo page published at {0}.", path);
            case PENDING_REVIEW -> console("runwaydemo.console.page.pending",
                    "Demo page created at {0} and waiting in the review queue.", path);
            case DRAFT_BLOCKED -> {
                StringBuilder text = new StringBuilder(console("runwaydemo.console.page.blocked",
                        "Demo page created at {0} as a draft: the quality checks blocked publishing.", path));
                for (Finding finding : result.blocking()) {
                    text.append(' ').append(finding.code()).append(": ").append(finding.message());
                }
                yield text.toString();
            }
            case ALREADY_EXISTS -> console("runwaydemo.console.page.exists",
                    "A demo page already exists at {0}; nothing changed.", path);
            default -> console("runwaydemo.console.page.other", "Demo page install finished: {0} {1}",
                    result.outcome(), path);
        };
    }

    /** The site this console request manages; 404 when there is none (the application console). */
    private SiteView boundSite() {
        return request.site().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String console(String code, String fallback, Object... args) {
        return messages.console(code, fallback, args);
    }

    private static String back(RedirectAttributes flash, String kind, String text) {
        flash.addFlashAttribute(kind, text);
        return "redirect:" + BASE;
    }

    /**
     * The starter page: the master's chrome seams, a heading, and the two widget anchors (an {@code id} and a
     * {@code data-widget} on one element each). An {@code <h1>} keeps the page clear of the demo's own quality check.
     */
    static String starterHtml(String title) {
        String t = Html.escape(title);
        return "<!DOCTYPE html>\n<html>\n<head><title>" + t + "</title></head>\n<body>\n"
                + "<header id=\"site-header\"></header>\n<main id=\"content\">\n"
                + "  <h1 id=\"demo-page-title\">" + t + "</h1>\n"
                + "  <div id=\"demo-notice\" data-widget=\"demo-notice/page\"></div>\n"
                + "  <div id=\"demo-feedback\" data-widget=\"demo-feedback/page\"></div>\n"
                + "</main>\n<footer id=\"site-footer\"></footer>\n</body>\n</html>\n";
    }

    /** The starter page's decoupled logic: the master's chrome by the {@code ${master}} variable, never a literal. */
    static String starterLogic() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<thlogic>\n"
                + "    <attr sel=\"head\" th:replace=\"~{${master} :: head}\"/>\n"
                + "    <attr sel=\"#site-header\" th:replace=\"~{${master} :: #site-header}\"/>\n"
                + "    <attr sel=\"#site-footer\" th:replace=\"~{${master} :: #site-footer}\"/>\n"
                + "    <attr sel=\"#demo-page-title\" th:text=\"${pageTitle}\"/>\n"
                + "</thlogic>\n";
    }
}
