package mx.quest.blog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.ConsoleScreen;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.ContentChanges;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The blog's tenant-console authoring screens ({@code /__admin/blog}): list, create, edit, publish/unpublish
 * and delete entries — a plugin-owned console surface rendered inside the operator shell through
 * {@link ConsoleScreen} (the kernel fills the shell's model; the {@code contentTemplate} resolves through this
 * plugin's own {@code __plugin__/blog/…} namespace).
 *
 * <p>Four platform disciplines enforced here:
 * <ul>
 *   <li><b>tenancy is always the bound one</b> ({@link RequestContext#site()}), never a path/param id;</li>
 *   <li><b>every mutation is announced</b> through {@link ContentChanges#changed} — the single freshness trigger
 *       for the plugin's own URL index (delivered back to {@link BlogContentSource} as a SOURCE listener) and for
 *       every kernel consumer (routing, sitemap, llms.txt, search), relayed cluster-wide by the host;</li>
 *   <li><b>every state change is a CSRF-checked {@code POST}</b> (the console chain checks the token; the
 *       templates put it in every form) — the {@code GET}s only render;</li>
 *   <li><b>the reverse advisory</b>: the edit screen asks the kernel's
 *       {@link ContentCatalogView#pageAt} whether a published page owns any of the entry's URLs — the page would
 *       win, so the author is warned exactly where they type the slug.</li>
 * </ul>
 *
 * <p>Gated by this plugin's own grantable control point ({@code blog|manage}, checked by the kernel's
 * {@code AccessCheck}, bean {@code authz}); the per-site settings editor is gated separately by the reserved
 * {@code settings} point (host-rendered from the descriptor schema).
 */
@Controller
@ConsoleScreen
public class BlogAdminController {

    private static final Logger log = LoggerFactory.getLogger(BlogAdminController.class);

    private static final String PERMISSION = "@authz.may('blog|manage')";

    /** Ids the routing table owns — an entry named like an action verb would be uneditable/unreachable. */
    private static final Set<String> RESERVED_IDS = Set.of("create", "new");
    private static final Pattern SAFE_SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,98}[a-z0-9]|[a-z0-9]");

    private final RequestContext request;
    private final BlogStore store;
    private final BlogContentSource source;
    private final ContentCatalogView catalog;
    private final ContentChanges changes;
    private final Messages messages;

    public BlogAdminController(RequestContext request, BlogStore store, BlogContentSource source,
                               ContentCatalogView catalog, ContentChanges changes, Messages messages) {
        this.request = request;
        this.store = store;
        this.source = source;
        this.catalog = catalog;
        this.changes = changes;
        this.messages = messages;
    }

    private String message(String key, String fallback) {
        return messages.console(key, fallback);
    }

    /** One list row: the entry plus its resolved public path (under the current prefix settings). */
    public record EntryRow(BlogEntry entry, String publicPath) {
    }

    @GetMapping("/__admin/blog")
    @PreAuthorize(PERMISSION)
    public String list(Model model) {
        SiteView site = boundTenancy();
        List<EntryRow> rows = new ArrayList<>();
        for (BlogEntry entry : store.list(site.id())) {
            rows.add(new EntryRow(entry, source.publicPath(site.id(), entry)));
        }
        model.addAttribute("rows", rows);
        model.addAttribute("blogEnabled", source.settings(site.id()).enabled());
        model.addAttribute(ConsoleScreen.PAGE_TITLE_KEY, "blog.admin.title");
        model.addAttribute(ConsoleScreen.CONTENT_TEMPLATE, "__plugin__/blog/admin-list");
        return ConsoleScreen.LAYOUT_VIEW;
    }

    @GetMapping("/__admin/blog/new")
    @PreAuthorize(PERMISSION)
    public String createForm(Model model) {
        SiteView site = boundTenancy();
        editorModel(model, site, new BlogEntry("new-entry", Map.of(), Map.of(), List.of(), null, Map.of(),
                null, false, null, null, null), Map.of(), true);
        return ConsoleScreen.LAYOUT_VIEW;
    }

    @PostMapping("/__admin/blog/create")
    @PreAuthorize(PERMISSION)
    public String create(@RequestParam("id") String id, @RequestParam MultiValueMap<String, String> params,
                         Model model, RedirectAttributes redirect) {
        SiteView site = boundTenancy();
        String entryId = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        String error = null;
        try {
            BlogStore.requireSafeId(entryId);
            if (RESERVED_IDS.contains(entryId)) {
                error = message("blog.error.reservedId", "That id is reserved — pick another.");
            } else if (store.read(site.id(), entryId).isPresent()) {
                error = message("blog.error.duplicate", "An entry with that id already exists.");
            } else {
                validateForm(site, params);
                save(site, entryId, params);
                redirect.addFlashAttribute("flash",
                        message("blog.flash.created", "Entry created."));
                return "redirect:/__admin/blog/" + entryId + "/edit";
            }
        } catch (IllegalArgumentException invalid) {
            error = message("blog.error.invalid", "Some fields are invalid: ") + invalid.getMessage();
        } catch (IOException io) {
            log.warn("creating blog entry {}/{} failed", site.id(), entryId, io);
            error = message("blog.error.ioError", "The entry could not be written.");
        }
        // NEVER blank the form on failure: re-render the editor with exactly what the author typed.
        editorModel(model, site, parseEntry(site, entryId.isEmpty() ? "new-entry" : entryId, params),
                parseBodies(site, params), true);
        model.addAttribute("error", error);
        return ConsoleScreen.LAYOUT_VIEW;
    }

    @GetMapping("/__admin/blog/{id}/edit")
    @PreAuthorize(PERMISSION)
    public String editForm(@PathVariable("id") String id, Model model) {
        SiteView site = boundTenancy();
        BlogEntry entry = store.read(site.id(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such entry"));
        Map<String, String> bodies = new LinkedHashMap<>();
        for (Locale locale : site.locales()) {
            bodies.put(locale.getLanguage(), store.readBody(site.id(), id, locale.getLanguage()));
        }
        editorModel(model, site, entry, bodies, false);
        return ConsoleScreen.LAYOUT_VIEW;
    }

    @PostMapping("/__admin/blog/{id}")
    @PreAuthorize(PERMISSION)
    public String update(@PathVariable("id") String id, @RequestParam MultiValueMap<String, String> params,
                         RedirectAttributes redirect) {
        SiteView site = boundTenancy();
        store.read(site.id(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such entry"));
        try {
            validateForm(site, params);
            save(site, id, params);
            redirect.addFlashAttribute("flash", message("blog.flash.saved", "Saved."));
        } catch (IllegalArgumentException invalid) {
            redirect.addFlashAttribute("error",
                    message("blog.error.invalid", "Some fields are invalid: ") + invalid.getMessage());
        } catch (IOException io) {
            log.warn("saving blog entry {}/{} failed", site.id(), id, io);
            redirect.addFlashAttribute("error", message("blog.error.ioError", "The entry could not be written."));
        }
        return "redirect:/__admin/blog/" + id + "/edit";
    }

    @PostMapping("/__admin/blog/{id}/publish")
    @PreAuthorize(PERMISSION)
    public String togglePublished(@PathVariable("id") String id, RedirectAttributes redirect) {
        SiteView site = boundTenancy();
        BlogEntry entry = store.read(site.id(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such entry"));
        try {
            // Carry author and publishedOn through: toggling visibility is not an editorial change, and this
            // rebuild lists every field explicitly, so anything forgotten here is silently erased on publish.
            store.save(site.id(), new BlogEntry(entry.id(), entry.title(), entry.description(),
                    entry.locales(), entry.slug(), entry.localizedSlugs(), entry.customPath(),
                    !entry.published(), entry.publishedOn(), entry.author(), null), null);
            changes.changed(site.id(), BlogContentSource.KEY, id);
            redirect.addFlashAttribute("flash", entry.published()
                    ? message("blog.flash.unpublished", "Entry unpublished.")
                    : message("blog.flash.published", "Entry published."));
        } catch (IOException io) {
            log.warn("toggling blog entry {}/{} failed", site.id(), id, io);
            redirect.addFlashAttribute("error", message("blog.error.ioError", "The entry could not be written."));
        }
        return "redirect:/__admin/blog";
    }

    @PostMapping("/__admin/blog/{id}/delete")
    @PreAuthorize(PERMISSION)
    public String delete(@PathVariable("id") String id, RedirectAttributes redirect) {
        SiteView site = boundTenancy();
        try {
            store.delete(site.id(), id);
            changes.changed(site.id(), BlogContentSource.KEY, id);
            redirect.addFlashAttribute("flash", message("blog.flash.deleted", "Entry deleted."));
        } catch (IOException io) {
            log.warn("deleting blog entry {}/{} failed", site.id(), id, io);
            redirect.addFlashAttribute("error", message("blog.error.ioError", "The entry could not be deleted."));
        }
        return "redirect:/__admin/blog";
    }

    // --- shared ------------------------------------------------------------------------------------------------

    /** Reads the editor form into the entry + bodies, persists, and announces the change. */
    private void save(SiteView site, String entryId, MultiValueMap<String, String> params) throws IOException {
        store.save(site.id(), parseEntry(site, entryId, params), parseBodies(site, params));
        changes.changed(site.id(), BlogContentSource.KEY, entryId);
    }

    /** The editor form → an entry (the default language's slug field doubles as the base slug). */
    private static BlogEntry parseEntry(SiteView site, String entryId, MultiValueMap<String, String> params) {
        Map<String, String> title = perLang(site, params, "title_");
        Map<String, String> description = perLang(site, params, "description_");
        Map<String, String> localizedSlugs = perLang(site, params, "slug_");
        List<String> locales = new ArrayList<>();
        for (Locale locale : site.locales()) {
            if (params.containsKey("locales_" + locale.getLanguage())) {
                locales.add(locale.getLanguage());
            }
        }
        String defaultLang = site.defaultLocale().getLanguage();
        String slug = localizedSlugs.remove(defaultLang);
        return new BlogEntry(entryId, title, description, locales, slug, localizedSlugs,
                first(params, "customPath"), params.containsKey("published"), first(params, "publishedOn"),
                first(params, "author"), null);
    }

    private static Map<String, String> parseBodies(SiteView site, MultiValueMap<String, String> params) {
        Map<String, String> bodies = new LinkedHashMap<>();
        for (Locale locale : site.locales()) {
            String lang = locale.getLanguage();
            bodies.put(lang, first(params, "body_" + lang));
        }
        return bodies;
    }

    /**
     * Rejects URL inputs the kernel's fail-closed backstop would otherwise DISCARD SILENTLY (the console
     * would flash "saved" while the entry quietly vanished from routing and listings): slugs must match the
     * slug grammar; a custom path must be absolute, free of the index separator, and a path the kernel would
     * serve content at ({@link ContentCatalogView#servablePath}: safe, not reserved, not an endpoint route).
     */
    private void validateForm(SiteView site, MultiValueMap<String, String> params) {
        for (Locale locale : site.locales()) {
            String slug = first(params, "slug_" + locale.getLanguage());
            if (slug != null && !slug.isBlank() && !SAFE_SLUG.matcher(slug.trim()).matches()) {
                throw new IllegalArgumentException("slug (" + locale.getLanguage() + ")");
            }
        }
        String customPath = first(params, "customPath");
        if (customPath != null && !customPath.isBlank()) {
            String p = customPath.trim();
            if (!p.startsWith("/") || p.contains("|") || p.contains(":")
                    || !catalog.servablePath(site.id(), p)) {
                throw new IllegalArgumentException("customPath");
            }
        }
    }

    /** Populates the editor model, including the reverse advisory (which pages own this entry's URLs). */
    private void editorModel(Model model, SiteView site, BlogEntry entry, Map<String, String> bodies,
                             boolean creating) {
        List<String> shadowedBy = new ArrayList<>();
        if (!creating) {
            for (Locale locale : site.locales()) {
                String lang = locale.getLanguage();
                // The REAL per-language URL (localized slug when set) — mirrors routing's own semantics.
                String path = source.publicPath(site.id(), entry, lang);
                catalog.pageAt(site.id(), path, locale)
                        .ifPresent(slug -> shadowedBy.add(slug + " (" + lang + ")"));
            }
        }
        // Thymeleaf trap: a literal containing TWO '__' sequences inside a th.xml expression trips Thymeleaf's
        // __…__ preprocessing — so the form action (two '/__admin' literals in a ternary) is computed HERE and
        // passed as a plain model value the template reads with one ${formAction}.
        model.addAttribute("formAction", creating ? "/__admin/blog/create" : "/__admin/blog/" + entry.id());
        model.addAttribute("entry", entry);
        model.addAttribute("bodies", bodies);
        model.addAttribute("creating", creating);
        model.addAttribute("shadowedBy", shadowedBy);
        model.addAttribute("siteLocaleTags",
                site.locales().stream().map(Locale::getLanguage).toList());
        model.addAttribute("defaultLocaleTag", site.defaultLocale().getLanguage());
        model.addAttribute(ConsoleScreen.PAGE_TITLE_KEY, "blog.admin.title");
        model.addAttribute(ConsoleScreen.CONTENT_TEMPLATE, "__plugin__/blog/admin-edit");
    }

    private static Map<String, String> perLang(SiteView site, MultiValueMap<String, String> params, String prefix) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Locale locale : site.locales()) {
            String lang = locale.getLanguage();
            String value = first(params, prefix + lang);
            if (value != null && !value.isBlank()) {
                values.put(lang, value.trim());
            }
        }
        return values;
    }

    private static String first(MultiValueMap<String, String> params, String name) {
        List<String> values = params.get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    /**
     * The site this console request is bound to. Empty on the application console (there is no tenancy there) —
     * a {@code 400}.
     */
    private SiteView boundTenancy() {
        return request.site()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"));
    }
}
