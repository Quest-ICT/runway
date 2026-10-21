package mx.quest.runway.spi.model;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The immutable input to a {@link mx.quest.runway.spi.HeadContributor} — everything resolved
 * for the current request that a contributor needs to produce {@code <head>} markup, without
 * touching the request or any store. The host injects the rendered markup before
 * {@code </head>} at the response edge, independently of the site's templates.
 *
 * <p>Facts available through {@link #site()} (for example its base URL and full locale set) and
 * through {@link #page()} (title, description, available locales) are not duplicated here.
 *
 * @param site       the site being served (never {@code null})
 * @param page       the page being served (never {@code null})
 * @param locale     the resolved request locale (never {@code null})
 * @param canonical  the resolved canonical URL for this page+locale, or {@code null} when not
 *                   applicable
 * @param xDefault   the resolved {@code hreflang x-default} URL (the page's primary-locale URL),
 *                   or {@code null} when not applicable
 * @param alternates the page's localized alternates for {@code hreflang} (never {@code null};
 *                   defensively copied, unmodifiable; may be empty)
 * @param preview    whether this is a draft/preview render (preview surfaces are never indexable)
 * @param noindex    the <em>effective</em> indexability for this render — the host has already
 *                   folded in the page's setting and preview, so a contributor can emit the
 *                   robots directive directly from this flag
 * @param seo        the site's core SEO tags (social cards + verification + extra head), resolved by
 *                   the host; never {@code null} (an unconfigured site yields {@link SeoTags#empty()})
 * @param icons      the site's icon/manifest discovery hints, resolved by the host; never {@code null}
 *                   (a site with no icons yields {@link IconHints#NONE})
 * @param origin     the <b>trusted origin</b> the page is served under — {@code scheme://host[:port]}, no
 *                   trailing slash, default port omitted — for building absolute URLs (JSON-LD {@code @id}s, social
 *                   card images) that must never be relative: the site's base URL when it has one (configured,
 *                   or derived from its primary host — {@link SiteView#baseUrl()}), else — a host-less site, such as
 *                   the platform's catch-all default — the request's own origin as the host's trusted-proxy gate
 *                   left it (a configured trusted
 *                   proxy's forwarded scheme and host applied, raw values otherwise — never a raw
 *                   {@code X-Forwarded-*} header). Never {@code null}; {@code ""} when unknown (a context built
 *                   with an older constructor, or a render with no request). {@link #canonical()},
 *                   {@link #xDefault()} and {@link #alternates()} keep their own rule (prefixed with the configured
 *                   base URL only), so a host-less site's head links stay root-relative as before; {@code ""}
 *                   for a draft preview; since SPI 1.0.0
 */
public record HeadContext(SiteView site, PageMeta page, Locale locale, String canonical,
                          String xDefault, List<Alternate> alternates, boolean preview, boolean noindex,
                          SeoTags seo, IconHints icons, String origin) {

    public HeadContext {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(locale, "locale");
        alternates = (alternates == null) ? List.of() : List.copyOf(alternates);
        seo = (seo == null) ? SeoTags.empty() : seo;
        icons = (icons == null) ? IconHints.NONE : icons;
        origin = (origin == null) ? "" : (origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin);
    }

    /**
     * The SPI 0.6.0 form, before the {@code origin} component: {@link #origin()} is {@code ""}. (Kept per the SPI
     * evolution rule.)
     */
    public HeadContext(SiteView site, PageMeta page, Locale locale, String canonical, String xDefault,
                       List<Alternate> alternates, boolean preview, boolean noindex, SeoTags seo, IconHints icons) {
        this(site, page, locale, canonical, xDefault, alternates, preview, noindex, seo, icons, "");
    }

    /**
     * Backward-compatible constructor for callers predating the {@code icons} component: defaults it to
     * {@link IconHints#NONE}. (Kept per the SPI evolution rule.)
     */
    public HeadContext(SiteView site, PageMeta page, Locale locale, String canonical, String xDefault,
                       List<Alternate> alternates, boolean preview, boolean noindex, SeoTags seo) {
        this(site, page, locale, canonical, xDefault, alternates, preview, noindex, seo, IconHints.NONE, "");
    }

    /**
     * Backward-compatible constructor for callers predating the {@code seo} and {@code icons} components:
     * defaults both. (Kept per the SPI evolution rule — an extension compiled against the earlier shape
     * keeps constructing a valid context.)
     */
    public HeadContext(SiteView site, PageMeta page, Locale locale, String canonical, String xDefault,
                       List<Alternate> alternates, boolean preview, boolean noindex) {
        this(site, page, locale, canonical, xDefault, alternates, preview, noindex, SeoTags.empty(), IconHints.NONE,
                "");
    }
}
