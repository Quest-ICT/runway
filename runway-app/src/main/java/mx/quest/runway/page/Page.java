package mx.quest.runway.page;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.storage.PathSafety;

/**
 * A tenant page, loaded from {@code <slug>.meta.yml}. The {@code slug} is the page's stable id (its
 * file name), set by {@link PageStore} from the file — it is not part of the YAML, so a freshly
 * deserialized page carries a {@code null} slug until {@link #withSlug(String)} is applied.
 *
 * <p>Per-locale fields ({@code title}, {@code description}, {@code localizedPaths}) are keyed by
 * language code ({@code "en"}, {@code "es"}). {@code locales} is the page's available set; an empty
 * set means "the site default locale only". {@code template} is the master that provides the page's
 * chrome (default {@code _master}).
 *
 * @param slug           stable id / file name (null until {@link #withSlug} is applied)
 * @param path           default public URL path (defaults to {@code /<slug>} when applied)
 * @param localizedPaths per-locale URL overrides (language code → path)
 * @param locales        the page's available locales (empty ⇒ site default only)
 * @param title          per-locale titles (language code → title)
 * @param description    per-locale descriptions (language code → description)
 * @param activeNav      the nav item this page marks active (may be empty)
 * @param template       the master name (default {@code _master})
 * @param draft          whether the page is a draft (default {@code false} ⇒ published)
 * @param nosnippet      the author's "do not show a text snippet of this page" setting. Distinct from
 *                       {@link #noindex}: the page stays in the index and keeps its ranking, but engines are
 *                       asked not to quote it — which is also the documented control over whether generative
 *                       features may use the page's text. Without it a tenant had only two positions,
 *                       fully usable or out of the index entirely, with nothing in between
 * @param noindex        the author's "do not index" setting — when {@code true} the platform emits a
 *                       {@code noindex} robots directive for this page (the site's default is
 *                       {@code false}, i.e. indexable)
 * @param canonical      an explicit canonical-URL override, or {@code null} for the auto-computed
 *                       canonical (a blank value normalizes to {@code null} so it is omitted from the
 *                       YAML). A leading-{@code /} value is resolved against the site's base URL; an
 *                       absolute URL is used verbatim.
 * @param cacheControl   an explicit {@code Cache-Control} header override for the served public page, or
 *                       {@code null} for the platform default ({@code runway.http-cache.public-pages}). A
 *                       blank value normalizes to {@code null} so it is omitted from the YAML. Used verbatim
 *                       as the header value when present.
 * @param params         per-page template parameters — a flat map of static {@code key → value} strings the
 *                       page's templates read as {@code ${params.key}}. Empty by default; the render model
 *                       overlays these on the site-level params (the page wins on a key collision), so a page
 *                       can specialize or add a value without editing markup. Never {@code null} (a
 *                       {@code null} argument sanitizes to an empty immutable map), so a page always exposes a
 *                       {@code params} map even when it defines none.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Page(String slug, String path, Map<String, String> localizedPaths, List<Locale> locales,
                   Map<String, String> title, Map<String, String> description, String activeNav,
                   String template, boolean draft, boolean noindex, String canonical, String cacheControl,
                   Map<String, String> params, String author, Instant publishedAt,
                   boolean nosnippet, boolean gone) implements Routable {

    public Page {
        path = (path == null || path.isBlank()) ? null : path;
        localizedPaths = sanitizeLocalizedPaths(localizedPaths);
        locales = (locales == null) ? List.of() : List.copyOf(locales);
        title = (title == null) ? Map.of() : Map.copyOf(title);
        description = (description == null) ? Map.of() : Map.copyOf(description);
        activeNav = (activeNav == null) ? "" : activeNav;
        template = (template == null || template.isBlank()) ? "_master" : template;
        // A blank override is "no override": normalize to null so NON_NULL omits it and the render path
        // falls back to the auto-computed canonical (never persist an empty canonical: "").
        canonical = (canonical == null || canonical.isBlank()) ? null : canonical.trim();
        // Same "blank ⇒ no override" rule for the per-page Cache-Control: null so NON_NULL omits it and the
        // page falls back to the configured runway.http-cache.public-pages default.
        cacheControl = (cacheControl == null || cacheControl.isBlank()) ? null : cacheControl.trim();
        params = sanitizeParams(params);
        // Blank byline == no byline, so NON_NULL omits it rather than persisting author: "" into every page file.
        author = (author == null || author.isBlank()) ? null : author.trim();
    }

    /**
     * The pre-provenance 13-arg form, kept so the many {@code new Page(...)} call sites that predate authorship
     * keep compiling: a page with no byline and no publication date.
     */
    public Page(String slug, String path, Map<String, String> localizedPaths, List<Locale> locales,
                Map<String, String> title, Map<String, String> description, String activeNav,
                String template, boolean draft, boolean noindex, String canonical, String cacheControl,
                Map<String, String> params) {
        this(slug, path, localizedPaths, locales, title, description, activeNav, template, draft, noindex,
                canonical, cacheControl, params, null, null, false, false);
    }

    /** The pre-{@code nosnippet} 15-arg form, kept so provenance-aware call sites keep compiling. */
    public Page(String slug, String path, Map<String, String> localizedPaths, List<Locale> locales,
                Map<String, String> title, Map<String, String> description, String activeNav,
                String template, boolean draft, boolean noindex, String canonical, String cacheControl,
                Map<String, String> params, String author, Instant publishedAt) {
        this(slug, path, localizedPaths, locales, title, description, activeNav, template, draft, noindex,
                canonical, cacheControl, params, author, publishedAt, false, false);
    }

    /** The pre-{@code gone} 16-arg form, kept so {@code nosnippet}-aware call sites keep compiling. */
    public Page(String slug, String path, Map<String, String> localizedPaths, List<Locale> locales,
                Map<String, String> title, Map<String, String> description, String activeNav,
                String template, boolean draft, boolean noindex, String canonical, String cacheControl,
                Map<String, String> params, String author, Instant publishedAt, boolean nosnippet) {
        this(slug, path, localizedPaths, locales, title, description, activeNav, template, draft, noindex,
                canonical, cacheControl, params, author, publishedAt, nosnippet, false);
    }

    /**
     * Pre-params shape — the 12-arg canonical form. Kept so the many {@code new Page(...)} call sites that
     * predate template parameters keep compiling and construct a page with an empty {@link #params()} map;
     * it simply delegates with a {@code null} params (which the compact constructor normalizes to empty).
     */
    public Page(String slug, String path, Map<String, String> localizedPaths, List<Locale> locales,
                Map<String, String> title, Map<String, String> description, String activeNav,
                String template, boolean draft, boolean noindex, String canonical, String cacheControl) {
        this(slug, path, localizedPaths, locales, title, description, activeNav, template, draft, noindex,
                canonical, cacheControl, null);
    }

    /**
     * The <b>original</b> 9-arg shape (pre on-page SEO — before {@code noindex}/{@code canonical}/
     * {@code cacheControl}/{@code params}). Retained as a stable, binary-compatible public constructor so a
     * plugin compiled against the earlier {@code Page} keeps loading (the "keep old-arity constructors" rule):
     * it constructs a page that is indexable, with no canonical/cache-control override and no template params.
     */
    public Page(String slug, String path, Map<String, String> localizedPaths, List<Locale> locales,
                Map<String, String> title, Map<String, String> description, String activeNav,
                String template, boolean draft) {
        this(slug, path, localizedPaths, locales, title, description, activeNav, template, draft,
                false, null, null, null);
    }

    /**
     * The <b>single</b> positional rebuild of this record in the whole codebase — every {@code with…}
     * transformation goes through it, varying only the three fields any of them can change.
     *
     * <h2>Why it is the only one</h2>
     * A record rebuilt field by field silently drops whatever the author of that line forgot, and the compiler
     * cannot help: a compatibility constructor at the old arity absorbs the short call and the missing field
     * takes its default. That has bitten this record three times — provenance was lost on rename and on the
     * visibility toggle, and the same two lines would have lost {@code nosnippet} and {@code gone}. Each time
     * the fix was to add the field to four separate rebuilds and hope the fifth is remembered next time.
     *
     * <p>Collapsing them here removes the class of bug rather than one instance of it: adding a component to
     * this record is now a one-line edit <em>in this file</em>, and no caller enumerates fields at all.
     */
    private Page rebuild(String newSlug, String newPath, boolean newDraft) {
        return new Page(newSlug, newPath, localizedPaths, locales, title, description, activeNav, template,
                newDraft, noindex, canonical, cacheControl, params, author, publishedAt, nosnippet, gone);
    }

    /** This page with its stable slug applied (and its default path derived if none was set). */
    public Page withSlug(String slug) {
        String safe = PathSafety.requireSafeId(slug);
        return rebuild(safe, path == null ? "/" + safe : path, draft);
    }

    /**
     * This page moved to a new slug, keeping everything else — the shape a rename needs.
     *
     * <p>A rename is a change of <em>address</em>, not of identity: authorship, publication date and every
     * indexing choice travel with the page.
     *
     * @param slug the new stable slug
     * @param path the route to keep or derive (the caller decides whether the old path was slug-derived)
     */
    public Page renamedTo(String slug, String path) {
        return rebuild(PathSafety.requireSafeId(slug), path, draft);
    }

    /** This page with its published/draft state set, everything else untouched. */
    public Page withDraft(boolean draft) {
        return rebuild(slug, path, draft);
    }

    /**
     * This page with its slug cleared — the form in which it is <b>serialized</b> to {@code
     * <slug>.meta.yml}. The slug is the file name (the stable id), never part of the YAML body
     * ({@link PageStore} re-applies it from the file on read), so the persisted document omits it;
     * paired with the record's {@link JsonInclude.Include#NON_NULL} inclusion the key is absent, not
     * {@code slug: null}. Everything else is preserved verbatim.
     */
    public Page withoutSlug() {
        return rebuild(null, path, draft);
    }

    /** Whether the page is published (the inverse of {@link #draft()}). */
    public boolean published() {
        return !draft;
    }

    /** The title for {@code locale}, falling back to any present title, else empty. */
    public String titleFor(Locale locale) {
        return localized(title, locale);
    }

    /** The description for {@code locale}, falling back to any present value, else empty. */
    public String descriptionFor(Locale locale) {
        return localized(description, locale);
    }

    /** The public path for {@code locale} (a localized override if any, else the default {@link #path}). */
    public String pathFor(Locale locale) {
        String localized = localizedPaths.get(locale.getLanguage());
        return localized != null ? localized : path;
    }

    /** Whether the page is available in {@code locale} (an empty {@link #locales} means site-default only). */
    public boolean hasLocale(Locale locale, Locale siteDefault) {
        return locales.isEmpty() ? locale.equals(siteDefault) : locales.contains(locale);
    }

    /** The page's primary locale: the site default if it is available, else the first declared locale. */
    public Locale primaryLocale(Locale siteDefault) {
        return (locales.isEmpty() || locales.contains(siteDefault)) ? siteDefault : locales.get(0);
    }

    /** The page's chrome: its own {@link #template()} (the {@link Routable} dispatch-surface accessor). */
    @Override
    public String masterTemplate() {
        return template;
    }

    /** The SPI view of this page in {@code locale} (the {@link Routable} accessor; see {@link PageViews}). */
    @Override
    public PageMeta meta(Locale locale, Locale siteDefault) {
        return PageViews.toView(this, locale, siteDefault);
    }

    /**
     * Keeps only the localized-path overrides that are non-blank and {@linkplain
     * PathSafety#isSafePagePath path-safe}; a blank or unsafe value is dropped (the locale then falls
     * back to {@link #path}) rather than becoming an unroutable or traversal-prone routing key.
     */
    private static Map<String, String> sanitizeLocalizedPaths(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        raw.forEach((lang, value) -> {
            if (lang != null && value != null) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty() && PathSafety.isSafePagePath(trimmed)) {
                    safe.put(lang, trimmed);
                }
            }
        });
        return Map.copyOf(safe);
    }

    /**
     * Normalizes the per-page parameter map: {@code null} → empty, each key trimmed, blank-keyed entries
     * dropped (a value cannot be addressed as {@code ${params.}}), and a {@code null} value coerced to the
     * empty string (an author who leaves a value blank still gets the key, with an empty value). The
     * insertion order is preserved (a {@link LinkedHashMap}) so an author's editing order survives the YAML
     * round-trip, and the result is an immutable copy.
     */
    private static Map<String, String> sanitizeParams(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> clean = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) {
                String trimmedKey = key.trim();
                if (!trimmedKey.isEmpty()) {
                    clean.put(trimmedKey, value == null ? "" : value);
                }
            }
        });
        // Collections.unmodifiableMap over a LinkedHashMap (not Map.copyOf, which does not keep order).
        return Collections.unmodifiableMap(clean);
    }

    private static String localized(Map<String, String> map, Locale locale) {
        String value = map.get(locale.getLanguage());
        if (value != null) {
            return value;
        }
        return map.isEmpty() ? "" : map.values().iterator().next();
    }
}
