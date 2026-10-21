package mx.quest.runway.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.page.ContentUrls;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.spi.model.SiteView;
import org.springframework.stereotype.Component;

/**
 * Answers "<b>who serves this URL?</b>" across both content worlds — real pages and content sources — and
 * derives the per-site <b>conflict report</b> behind the visibility triple:
 * the page-editor advisory ("this path is also served by a source"), a source's entry-save reverse advisory
 * ("this path is owned by a page — your entry will be unreachable"), and the conflicts panel. The precedence
 * itself is structural (real page wins; two sources = loud 500); this service exists so no collision is ever
 * <em>silent</em>.
 *
 * <p>Read-only and derived on demand from the same authorities routing uses ({@link PageStore},
 * {@link ContentCatalog}) — never cached, so an advisory is always current for the operator screen asking.
 */
@Component
public class PathOwnership {

    /** How a conflicting URL plays out at request time. */
    public enum Kind {
        /** A real page owns the URL; the source's content is unreachable there (real page wins). */
        PAGE_SHADOWS_CONTENT,
        /** Two sources claim the URL; requests to it are a loud 500 until an operator resolves it. */
        CONTENT_CLASH
    }

    /**
     * One conflicting URL on a site.
     *
     * @param path      the contested public path (locale-specific)
     * @param localeTag the locale whose URL space the conflict is in
     * @param pageSlug  the winning page's slug ({@code null} for a content-vs-content clash)
     * @param claims    every content claim on the path, as {@code <sourceKey>:<id>} composites (never empty)
     * @param kind      how the conflict plays out at request time
     */
    public record Conflict(String path, String localeTag, String pageSlug, List<String> claims, Kind kind) {
    }

    private final PageStore pages;
    private final ContentCatalog catalog;

    public PathOwnership(PageStore pages, ContentCatalog catalog) {
        this.pages = pages;
        this.catalog = catalog;
    }

    /**
     * The content sources (never the pages projection) serving {@code path} in {@code locale}, as
     * {@code <sourceKey>:<id>} composites — the page-editor advisory's question: "if I keep this page at this
     * path, whose content am I shadowing?" Empty when no source claims it.
     */
    public List<String> contentClaims(SiteView site, String path, Locale locale) {
        List<String> claims = new ArrayList<>(1);
        for (RoutableContent item : catalog.enumerateContributed(site)) {
            if (item.hasLocale(locale, site.defaultLocale()) && path.equals(item.pathFor(locale))) {
                claims.add(item.compositeId());
            }
        }
        return claims;
    }

    /**
     * Every contributed claim on the site keyed {@code <lang>|<path>} — ONE enumeration pass for callers that
     * probe many (path, locale) pairs (the page editor checks every site locale; probing this map costs
     * nothing per additional locale).
     */
    public Map<String, List<String>> claimsByPath(SiteView site) {
        Map<String, List<String>> claims = new HashMap<>();
        for (RoutableContent item : catalog.enumerateContributed(site)) {
            for (Locale locale : ContentUrls.availableLocales(item, site)) {
                claims.computeIfAbsent(locale.getLanguage() + '|' + item.pathFor(locale),
                        k -> new ArrayList<>(1)).add(item.compositeId());
            }
        }
        return claims;
    }

    /**
     * Whether a real page owns {@code path} in {@code locale} — a source's entry-save reverse advisory: the
     * page wins, so the entry would be unreachable at this URL.
     */
    public Optional<String> owningPage(SiteView site, String path, Locale locale) {
        return pages.findByPath(site.id(), path, locale).map(page -> page.slug());
    }

    /**
     * Every URL conflict on the site, across all locales: pages shadowing source content, and source-vs-source
     * clashes. Derived fresh on each call (an operator screen, not a hot path).
     */
    public List<Conflict> conflicts(SiteView site) {
        // Group every contributed claim by (locale, path) — the pages projection is never enumerated here.
        Map<String, List<RoutableContent>> claims = new HashMap<>();
        for (RoutableContent item : catalog.enumerateContributed(site)) {
            for (Locale locale : ContentUrls.availableLocales(item, site)) {
                claims.computeIfAbsent(locale.toLanguageTag() + '|' + item.pathFor(locale),
                        k -> new ArrayList<>(1)).add(item);
            }
        }
        // One pages pass builds the per-locale path→slugs index probed below — O(pages), not
        // O(claims × locales) directory scans — and lets an AMBIGUOUS page route (two pages, one path: the
        // store's own fail-closed 500 at request time) surface as a REPORTED row instead of crashing this
        // operator screen.
        Map<String, List<String>> pagePaths = new HashMap<>();
        for (var page : pages.findAll(site.id())) {
            if (page.published()) {
                for (Locale locale : ContentUrls.availableLocales(page, site)) {
                    pagePaths.computeIfAbsent(locale.toLanguageTag() + '|' + page.pathFor(locale),
                            k -> new ArrayList<>(1)).add(page.slug());
                }
            }
        }
        List<Conflict> conflicts = new ArrayList<>();
        claims.forEach((key, items) -> {
            int sep = key.indexOf('|');
            String localeTag = key.substring(0, sep);
            String path = key.substring(sep + 1);
            List<String> composites = items.stream().map(RoutableContent::compositeId).sorted().toList();
            List<String> owners = pagePaths.get(key);
            if (owners != null && !owners.isEmpty()) {
                conflicts.add(new Conflict(path, localeTag, String.join(", ", owners), composites,
                        Kind.PAGE_SHADOWS_CONTENT));
            } else if (items.size() > 1) {
                conflicts.add(new Conflict(path, localeTag, null, composites, Kind.CONTENT_CLASH));
            }
        });
        conflicts.sort((a, b) -> {
            int byPath = a.path().compareTo(b.path());
            return byPath != 0 ? byPath : a.localeTag().compareTo(b.localeTag());
        });
        return conflicts;
    }
}
