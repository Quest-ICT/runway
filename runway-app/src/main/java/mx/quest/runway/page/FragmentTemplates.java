package mx.quest.runway.page;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import mx.quest.runway.storage.AtomicFiles;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.web.TemplateCacheService;
import org.springframework.stereotype.Component;

/**
 * The list / read / write / delete surface for a tenant's <b>chrome fragments</b> — the reusable template
 * partials under {@code sites/<siteId>/templates/} that the site's masters and pages pull by name (the
 * shared layout {@code _master}, and any partial such as {@code _head}, {@code _scripts}, {@code _nav}).
 *
 * <p><b>What is a fragment.</b> Any {@code _}-prefixed template slug is a chrome fragment — never routable, never
 * listed as a page — and the Fragments admin area manages that whole set (the master included). Runway keeps that scope,
 * expressed against its file model where a <em>page</em> is anchored by a {@code <slug>.meta.yml} and chrome
 * templates carry none:
 *
 * <ul>
 *   <li>a fragment is an {@code .html} file whose name starts with {@code _};</li>
 *   <li><b>error pages are excluded</b> ({@code _} followed by only digits — {@code _404}, {@code _500}):
 *       those have their own lifecycle through the error controller, and editing them as free-form chrome
 *       here would blur that boundary.</li>
 * </ul>
 *
 * A fragment may carry a paired {@code .th.xml} (decoupled logic) exactly like a master or page, because a
 * chrome partial routinely binds data ({@code ${site.name()}}, {@code ${params.*}}) and pulls other seams;
 * the editor round-trips both files.
 *
 * <p><b>Cache coherence — the site's whole cache, and only that site's.</b> Every mutation evicts every parsed
 * template of <em>this site</em> via {@link TemplateCacheService#evictSite(String)}, not the single fragment's name.
 * A fragment is inlined into its consumers (a master {@code th:replace}s it, and a page {@code th:replace}s the
 * master), so the parsed fragment lives inside <em>their</em> cache entries (keyed by the consuming template as
 * owner); evicting only the fragment's own name would leave every consumer serving the stale copy. Other sites keep
 * their warm cache: a site manager's chrome edit must not cold-start every tenant on the node.
 * (Each cached parse also checks its own files on every hit — {@code FileStampValidity} — so the eviction frees the
 * memory at once rather than being what makes the edit visible.)
 *
 * <p><b>The ETag moves with it.</b> Every page validator folds the site's non-page templates by content
 * ({@code ChromeTemplateFold}), so a fragment saved here, or edited on disk, moves the ETag of every page of the
 * site together with the body — a browser or CDN revalidating gets the new chrome, never a {@code 304}.
 *
 * <p><b>Isolation.</b> Every path is resolved through {@link PathSafety#resolveConfined} under the site's own
 * {@code templates/} directory, so a fragment name can never traverse to another site or to application
 * config. The {@code siteId} is always the caller's <em>bound</em> tenancy (never a client-supplied id); this
 * store additionally validates it. Atomicity is per-file ({@link AtomicFiles}), the same discipline the page
 * writer uses — this is the filesystem, not JDBC.
 */
@Component
public class FragmentTemplates {

    private static final String TEMPLATES_DIR = "templates";
    private static final String HTML = ".html";
    private static final String THXML = ".th.xml";
    private static final String META = ".meta.yml";

    /** {@code _} followed by only digits — an error-page template ({@code _404}, {@code _500}), not a fragment. */
    private static final Pattern ERROR_PAGE = Pattern.compile("_\\d+");
    /** An error page the console manages: {@code _} and an HTTP error status, 400 to 599. */
    private static final Pattern MANAGED_ERROR_PAGE = Pattern.compile("_[45]\\d\\d");

    private final StorageLayout layout;
    private final TemplateCacheService templateCache;

    public FragmentTemplates(StorageLayout layout, TemplateCacheService templateCache) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.templateCache = Objects.requireNonNull(templateCache, "templateCache");
    }

    /**
     * One fragment as the list surfaces it: its {@code name} (e.g. {@code _head}), whether it carries decoupled
     * logic ({@code .th.xml} present), and the {@code .html} file's {@code size} in bytes and last-modified
     * time in epoch millis (both {@code 0} if unreadable — the row still renders).
     */
    public record FragmentInfo(String name, boolean hasLogic, long size, long modified) {
    }

    /**
     * The site's fragment names, ordered — every {@code _}-prefixed {@code .html} that is not an error page.
     * A malformed or unreadable directory yields an empty list, never an error (the screen stays usable).
     */
    public List<FragmentInfo> list(String siteId) {
        if (!PathSafety.isSafeId(siteId)) {
            return List.of();
        }
        Path templates = layout.siteDir(siteId).resolve(TEMPLATES_DIR);
        if (!Files.isDirectory(templates)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(templates)) {
            List<String> names = entries.map(p -> p.getFileName().toString())
                    .filter(fileName -> fileName.endsWith(HTML))
                    .map(fileName -> fileName.substring(0, fileName.length() - HTML.length()))
                    .filter(FragmentTemplates::isFragmentName)
                    .sorted()
                    .toList();
            List<FragmentInfo> out = new ArrayList<>(names.size());
            for (String name : names) {
                out.add(describe(templates, name));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("listing fragments for " + siteId, e);
        }
    }

    /** The raw {@code <name>.html} source (for the editor), or empty if the name is unsafe or the file absent. */
    public Optional<String> readHtml(String siteId, String name) {
        return read(siteId, name, HTML);
    }

    /** The raw {@code <name>.th.xml} source (for the editor), or empty if the fragment has no decoupled logic. */
    public Optional<String> readLogic(String siteId, String name) {
        return read(siteId, name, THXML);
    }

    /** Whether a fragment named {@code name} exists (its {@code .html} is present) in the site. */
    public boolean exists(String siteId, String name) {
        if (!PathSafety.isSafeId(siteId) || !isFragmentName(name)) {
            return false;
        }
        return Files.isRegularFile(resolve(templatesDir(siteId), name + HTML));
    }

    /**
     * Writes (creates or overwrites) a fragment: the {@code <name>.html} body, plus the {@code <name>.th.xml}
     * logic when non-blank (a blank/{@code null} value removes any existing logic file — a plain include
     * fragment). Then clears the whole site's chrome cache so the change is live on the next render.
     *
     * @param name  the fragment name; must be a valid fragment id ({@code _}-prefixed, not an error page)
     * @param html  the fragment body ({@code <name>.html}); required
     * @param thxml the decoupled logic ({@code <name>.th.xml}); {@code null}/blank removes it
     * @throws FragmentException if {@code name} is not a valid fragment id (unsafe or an error-page name)
     */
    public void save(String siteId, String name, String html, String thxml) {
        requireFragmentName(name);
        write(siteId, name, html, thxml);
    }

    private void write(String siteId, String name, String html, String thxml) {
        Objects.requireNonNull(html, "html");
        Path templates = templatesDir(siteId);
        try {
            Files.createDirectories(templates);
            AtomicFiles.write(resolve(templates, name + HTML), html.getBytes(StandardCharsets.UTF_8));
            Path logic = resolve(templates, name + THXML);
            if (thxml != null && !thxml.isBlank()) {
                AtomicFiles.write(logic, thxml.getBytes(StandardCharsets.UTF_8));
            } else {
                Files.deleteIfExists(logic);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("writing fragment " + siteId + "/" + name, e);
        }
        templateCache.evictSite(siteId); // chrome change → this site's parses (a fragment feeds masters/pages)
    }

    /**
     * Creates a new fragment, refusing to overwrite one that already exists.
     *
     * @throws FragmentException if the name is invalid or a fragment with that name already exists
     */
    public void create(String siteId, String name, String html, String thxml) {
        requireFragmentName(name);
        if (exists(siteId, name)) {
            throw new FragmentException("already-exists", "a fragment already exists: " + name);
        }
        save(siteId, name, html, thxml);
    }

    /**
     * Deletes a fragment's {@code .html} and any {@code .th.xml}, then clears the site's chrome cache.
     *
     * @return {@code true} if an {@code .html} file was present and removed
     */
    public boolean delete(String siteId, String name) {
        if (!PathSafety.isSafeId(siteId) || !isFragmentName(name)) {
            return false;
        }
        return remove(siteId, name);
    }

    private boolean remove(String siteId, String name) {
        Path templates = templatesDir(siteId);
        boolean removed;
        try {
            removed = Files.deleteIfExists(resolve(templates, name + HTML));
            Files.deleteIfExists(resolve(templates, name + THXML));
        } catch (IOException e) {
            throw new UncheckedIOException("deleting fragment " + siteId + "/" + name, e);
        }
        templateCache.evictSite(siteId);
        return removed;
    }

    /**
     * Re-reads a fragment: there is no per-fragment cache of its own, so "reload" simply evicts this site's
     * parsed templates so the on-disk source is re-parsed on the next render — the recovery action for an edit
     * made out of band.
     */
    public void reload(String siteId, String name) {
        // The name is validated for a clean audit trail, but eviction is site-wide regardless.
        if (PathSafety.isSafeId(siteId)) {
            templateCache.evictSite(siteId);
        }
    }

    // --- error pages -------------------------------------------------------------------------------
    // The site's own pages for an error status (_404.html, optionally with _404.th.xml), rendered through its
    // master by ErrorPages. They live beside the fragments in templates/ but are not fragments: nothing includes
    // them. The same files and the same cache eviction, under their own name rule.

    /** The site's error pages, ordered by status. */
    public List<FragmentInfo> errorPages(String siteId) {
        if (!PathSafety.isSafeId(siteId)) {
            return List.of();
        }
        Path templates = layout.siteDir(siteId).resolve(TEMPLATES_DIR);
        if (!Files.isDirectory(templates)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(templates)) {
            return entries.map(p -> p.getFileName().toString())
                    .filter(fileName -> fileName.endsWith(HTML))
                    .map(fileName -> fileName.substring(0, fileName.length() - HTML.length()))
                    .filter(FragmentTemplates::isErrorPageName)
                    .sorted()
                    .map(name -> describe(templates, name))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("listing error pages for " + siteId, e);
        }
    }

    /** Whether the site has its own page for the error {@code name} ({@code _404}). */
    public boolean errorPageExists(String siteId, String name) {
        return PathSafety.isSafeId(siteId) && isErrorPageName(name)
                && Files.isRegularFile(resolve(templatesDir(siteId), name + HTML));
    }

    /** The error page's {@code .html} source, or empty. */
    public Optional<String> readErrorPageHtml(String siteId, String name) {
        return isErrorPageName(name) ? readFile(siteId, name, HTML) : Optional.empty();
    }

    /** The error page's {@code .th.xml} source, or empty. */
    public Optional<String> readErrorPageLogic(String siteId, String name) {
        return isErrorPageName(name) ? readFile(siteId, name, THXML) : Optional.empty();
    }

    /**
     * Writes an error page, as {@link #save} writes a fragment: the body, plus the logic when non-blank.
     *
     * @throws FragmentException if {@code name} is not {@code _} and a status from 400 to 599
     */
    public void saveErrorPage(String siteId, String name, String html, String thxml) {
        if (!isErrorPageName(name)) {
            throw new FragmentException("unsafe-name", "not an error page name: " + name);
        }
        write(siteId, name, html, thxml);
    }

    /**
     * Creates an error page, refusing to overwrite one.
     *
     * @throws FragmentException if the name is invalid or the page exists
     */
    public void createErrorPage(String siteId, String name, String html, String thxml) {
        if (errorPageExists(siteId, name)) {
            throw new FragmentException("already-exists", "an error page already exists: " + name);
        }
        saveErrorPage(siteId, name, html, thxml);
    }

    /** Deletes an error page; the status then gets the platform's plain page. */
    public boolean deleteErrorPage(String siteId, String name) {
        if (!PathSafety.isSafeId(siteId) || !isErrorPageName(name)) {
            return false;
        }
        return remove(siteId, name);
    }

    /** Whether {@code name} is an error page the console manages: {@code _} and a status from 400 to 599. */
    public static boolean isErrorPageName(String name) {
        return name != null && MANAGED_ERROR_PAGE.matcher(name).matches();
    }

    // --- internals ---------------------------------------------------------------------------------

    private FragmentInfo describe(Path templates, String name) {
        Path html = resolve(templates, name + HTML);
        boolean hasLogic = Files.isRegularFile(resolve(templates, name + THXML));
        long size = 0;
        long modified = 0;
        try {
            size = Files.size(html);
            FileTime time = Files.getLastModifiedTime(html);
            modified = time.toMillis();
        } catch (IOException ignored) {
            // A file that vanished between listing and stat is reported with zeros — the row still renders.
        }
        return new FragmentInfo(name, hasLogic, size, modified);
    }

    private Optional<String> read(String siteId, String name, String suffix) {
        if (!isFragmentName(name)) {
            return Optional.empty();
        }
        return readFile(siteId, name, suffix);
    }

    private Optional<String> readFile(String siteId, String name, String suffix) {
        if (!PathSafety.isSafeId(siteId)) {
            return Optional.empty();
        }
        Path file = resolve(templatesDir(siteId), name + suffix);
        try {
            return Files.isRegularFile(file)
                    ? Optional.of(Files.readString(file, StandardCharsets.UTF_8))
                    : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("reading fragment " + siteId + "/" + name + suffix, e);
        }
    }

    private Path templatesDir(String siteId) {
        if (!PathSafety.isSafeId(siteId)) {
            throw new FragmentException("unsafe-tenancy", "unsafe tenancy id: " + siteId);
        }
        return layout.siteDir(siteId).resolve(TEMPLATES_DIR);
    }

    private static Path resolve(Path templates, String fileName) {
        return PathSafety.resolveConfined(templates, fileName);
    }

    private static void requireFragmentName(String name) {
        if (!isFragmentName(name)) {
            throw new FragmentException("unsafe-name", "not a valid fragment name: " + name);
        }
    }

    /**
     * Whether {@code name} is a well-formed fragment name: {@code _} then a safe id (so it can never traverse
     * or contain a path separator), and <em>not</em> an error-page name ({@code _} + digits). A {@code null},
     * a bare {@code _}, a page slug (no leading {@code _}), or {@code _404} all return {@code false}.
     */
    public static boolean isFragmentName(String name) {
        return name != null && name.length() > 1 && name.charAt(0) == '_'
                && PathSafety.isSafeId(name.substring(1)) && !ERROR_PAGE.matcher(name).matches();
    }
}
