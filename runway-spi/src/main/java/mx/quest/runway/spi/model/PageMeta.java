package mx.quest.runway.spi.model;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable metadata about a page, independent of the locale a given request renders it in
 * (the request locale is passed separately to each SPI). Carries the author-level facts an SPI
 * needs to reason about a page without touching the page store.
 *
 * <p>These are the kernel-owned page facts. Domain-feature SEO content (social-card images,
 * structured data, verification tags) is owned by the SEO/GEO plugin, not carried here; the
 * plugin composes it from these facts plus its own per-page configuration.
 *
 * @param slug             the page's stable slug / identifier within its site (never {@code null})
 * @param title            the page title ({@code null} normalized to empty)
 * @param description      the page meta description ({@code null} normalized to empty)
 * @param availableLocales the locales this page is published in (never {@code null};
 *                         defensively copied, unmodifiable; may be empty). Canonical/hreflang
 *                         and locale listings derive from this set, not the site's.
 * @param primaryLocale    the page's primary locale — the site default if it is in
 *                         {@code availableLocales}, else the first declared — used for
 *                         {@code hreflang x-default}; {@code null} when the site default applies
 * @param published        whether the page is published (versus draft-only)
 * @param noindex          the author's "do not index" setting for this page (the <em>effective</em>
 *                         indexability for a given render, which also accounts for preview, is
 *                         carried by {@link HeadContext#noindex()})
 * @param nosnippet        whether engines are asked not to quote this page's text ({@code nosnippet}).
 *                         Independent of {@link #noindex}: the page stays indexed and keeps its ranking,
 *                         it simply is not excerpted — which is also the documented control over whether
 *                         generative features may use its text
 * @param provenance       who wrote this and when — never {@code null} ({@link Provenance#NONE} when nothing
 *                         is known). The carrier that lets a {@code HeadContributor} emit a byline or a
 *                         publication date; before it existed, no contributor could emit either at all
 * @param sourceKey        the {@link mx.quest.runway.spi.ContentSource#key()} serving this content, or
 *                         {@code null} for a kernel page. When set, {@link #slug()} is the composite
 *                         {@code <sourceKey>:<id>} identity — an identity, never a file/template name; an SPI
 *                         that needs the current page's real URL must use its view's request path, not build
 *                         one from the slug
 */
public record PageMeta(String slug, String title, String description, List<Locale> availableLocales,
                       Locale primaryLocale, boolean published, boolean noindex, String sourceKey,
                       Provenance provenance, boolean nosnippet) {

    public PageMeta {
        Objects.requireNonNull(slug, "slug");
        title = (title == null) ? "" : title;
        description = (description == null) ? "" : description;
        availableLocales = (availableLocales == null) ? List.of() : List.copyOf(availableLocales);
        provenance = (provenance == null) ? Provenance.NONE : provenance;
    }

    /**
     * The pre-{@code provenance} constructor, kept for binary compatibility (SPI evolution rule): content with
     * no known author or dates, i.e. {@link Provenance#NONE}.
     */
    public PageMeta(String slug, String title, String description, List<Locale> availableLocales,
                    Locale primaryLocale, boolean published, boolean noindex, String sourceKey) {
        this(slug, title, description, availableLocales, primaryLocale, published, noindex, sourceKey,
                Provenance.NONE, false);
    }

    /** The pre-{@code nosnippet} 9-arg form, kept for binary compatibility (SPI evolution rule). */
    public PageMeta(String slug, String title, String description, List<Locale> availableLocales,
                    Locale primaryLocale, boolean published, boolean noindex, String sourceKey,
                    Provenance provenance) {
        this(slug, title, description, availableLocales, primaryLocale, published, noindex, sourceKey,
                provenance, false);
    }

    /**
     * The pre-{@code sourceKey} constructor, kept for binary compatibility so an extension or host compiled
     * against an earlier SPI keeps loading on this one — a kernel page ({@code sourceKey} {@code null}).
     */
    public PageMeta(String slug, String title, String description, List<Locale> availableLocales,
                    Locale primaryLocale, boolean published, boolean noindex) {
        this(slug, title, description, availableLocales, primaryLocale, published, noindex, null,
                Provenance.NONE, false);
    }

    /** Whether this view describes a content source's page rather than a kernel page. */
    public boolean isVirtual() {
        return sourceKey != null;
    }
}
