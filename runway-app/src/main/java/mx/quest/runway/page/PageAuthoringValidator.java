package mx.quest.runway.page;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.PathSafety;
import org.springframework.stereotype.Component;

/**
 * Pre-write, fail-closed validation of an authored {@link Page} — the guard that keeps a bad edit from
 * ever reaching disk and corrupting a tenant's routable page set. It checks only the page's <b>intrinsic
 * and routing</b> properties (the write-context checks — the master file exists for a page that carries
 * decoupled logic — live in {@link PageWriteService}, which has the write parameters):
 *
 * <ul>
 *   <li><b>slug</b> — a {@linkplain PathSafety#isSafeId safe id} (this already excludes traversal,
 *       {@code /}, {@code |}, control chars, and a leading {@code _}, so a page can never shadow a
 *       master), and not under the reserved {@link #SCRATCH_SLUG_PREFIX};</li>
 *   <li><b>default path</b> — a {@linkplain PathSafety#isSafePagePath safe public path} (the {@code
 *       Page} constructor already sanitizes {@code localizedPaths}; the default {@code path} is not, so
 *       it is validated here, matching {@link PageStore}'s fail-closed skip on read);</li>
 *   <li><b>routing collision</b> — for a <b>published</b> page, no other published sibling already claims
 *       the same public path in any locale this page serves. This pre-checks exactly what {@link
 *       PageStore#findByPath} throws at request time, so an ambiguous route is refused at authoring
 *       instead of failing every visitor with a {@code 500}.</li>
 * </ul>
 */
@Component
public class PageAuthoringValidator {

    /**
     * Slug prefix reserved for transient preview scratch pages ({@link PageWriteService}). An author can
     * never create a page under it, so a real page can never collide with a live preview's scratch file.
     */
    public static final String SCRATCH_SLUG_PREFIX = "rw-preview-";

    private final SiteStore sites;
    private final PageStore pages;

    public PageAuthoringValidator(SiteStore sites, PageStore pages) {
        this.sites = sites;
        this.pages = pages;
    }

    /**
     * Validates {@code page} for the given tenancy, or throws {@link PageAuthoringException} (nothing is
     * written on failure).
     *
     * @throws PageAuthoringException if the slug/path is unsafe or reserved, or the page would collide
     *                                with a sibling's route
     */
    public void validate(String siteId, Page page) {
        String slug = page.slug();
        if (slug == null || !PathSafety.isSafeId(slug)) {
            throw new PageAuthoringException("unsafe-slug", "unsafe page id: " + slug);
        }
        if (slug.startsWith(SCRATCH_SLUG_PREFIX)) {
            throw new PageAuthoringException("reserved-slug",
                    "the id prefix '" + SCRATCH_SLUG_PREFIX + "' is reserved for previews");
        }
        if (page.path() != null && !PathSafety.isSafePagePath(page.path())) {
            throw new PageAuthoringException("unsafe-path", "unsafe page path: " + page.path());
        }
        // Defense in depth: the master name is persisted into meta and resolved at render, so reject anything
        // that is not a well-formed master name up front (an unsafe/traversal value is refused fail-closed,
        // regardless of whether this save carries decoupled logic that would trigger the master-exists check).
        if (!MasterTemplates.isMasterName(page.template())) {
            throw new PageAuthoringException("unsafe-master", "unsafe master name: " + page.template());
        }
        if (page.published()) {
            requireNoRouteCollision(siteId, slug, page);
        }
    }

    /**
     * No published sibling may resolve to this page's public path in any locale a request can be looked up
     * under. This mirrors {@link PageStore#findByPath} <b>exactly</b>: the router matches {@code
     * pathFor(lookupLocale)} across <em>all</em> published pages with <b>no</b> {@code hasLocale} filter (the
     * availability check happens later, after the ambiguity throw), so the validation must not gate on a
     * sibling's declared locales either — otherwise two pages sharing a default path but declaring disjoint
     * locales pass here yet make {@code findByPath} throw a {@code 500} for every visitor.
     */
    private void requireNoRouteCollision(String siteId, String slug, Page page) {
        Site site = sites.find(siteId).orElseThrow(() ->
                new PageAuthoringException("no-tenancy", "no such tenancy: " + siteId));
        // The locales a request can be looked up under: the site default (unprefixed URLs) plus every
        // configured locale (prefixed URLs) — the same set the router resolves against.
        Set<Locale> lookupLocales = new LinkedHashSet<>();
        lookupLocales.add(site.defaultLocale());
        lookupLocales.addAll(site.locales());
        List<Page> siblings = pages.findAll(siteId).stream()
                .filter(Page::published)
                .filter(other -> !other.slug().equals(slug))
                .toList();
        for (Locale locale : lookupLocales) {
            String path = page.pathFor(locale);
            if (path == null) {
                continue;
            }
            for (Page sibling : siblings) {
                if (path.equals(sibling.pathFor(locale))) {
                    throw new PageAuthoringException("path-collision", "path '" + path + "' (locale "
                            + locale.getLanguage() + ") already used by page '" + sibling.slug() + "'");
                }
            }
        }
    }
}
