package mx.quest.runway.spi.model;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * An immutable, host-materialized view of the site (tenant) a request is being served for.
 * Carries the site identity and the stable, presentation-relevant facts an SPI commonly
 * needs; it is <b>not</b> the mutable domain model and exposes nothing of other tenants.
 *
 * <p>It deliberately carries only <em>rendering-level</em> facts the kernel owns. Domain-feature
 * configuration — SEO social/verification defaults, analytics ids, and the like — is owned by
 * the relevant plugin (via its own per-site settings), not exposed here.
 *
 * <p>The shape is intentionally minimal and grows additively as extension axes need more.
 *
 * @param id            the site's stable identifier (never {@code null})
 * @param displayName   the site's human-facing name (never {@code null}); used for branding and
 *                      {@code og:site_name}-style markup
 * @param defaultLocale the site's default locale (never {@code null}); drives {@code hreflang}
 *                      {@code x-default} and locale resolution
 * @param locales       the locales the site offers (never {@code null}; defensively copied,
 *                      unmodifiable; may be empty)
 * @param baseUrl       the site's base URL ({@code scheme://host[:port]}) for building absolute
 *                      links in head/endpoint markup, or {@code null} if not resolved
 */
public record SiteView(String id, String displayName, Locale defaultLocale, List<Locale> locales,
                       String baseUrl) {

    public SiteView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(defaultLocale, "defaultLocale");
        locales = (locales == null) ? List.of() : List.copyOf(locales);
    }

    /** A minimal {@link SiteRef} for this site (its id). */
    public SiteRef ref() {
        return new SiteRef(id);
    }
}
