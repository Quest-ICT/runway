package mx.quest.runway.spi.model;

import java.util.Locale;
import java.util.Objects;

/**
 * One locale's address of a {@link CatalogEntry}, as the host's single URL authority computes it — the same
 * computation the page {@code <head>} alternates, the sitemap and llms.txt use, so a plugin that lists content
 * cannot disagree with them. A plugin receives these values; it never applies the URL scheme itself.
 *
 * @param locale    the locale this address serves (never {@code null})
 * @param path      the <b>root-relative routing URL</b> in that locale ({@code /about}, {@code /es/acerca-de}): the
 *                  localized path under the locale prefix (none for the site's default locale). A real address the
 *                  site serves; never {@code null} or blank
 * @param canonical the canonical address for this locale: the content's own canonical override <b>as given</b>
 *                  (an absolute URL, or a root-relative {@code /…} value), else {@code path}. Never {@code null}.
 *                  An entry whose canonical differs from its {@code path} "points elsewhere" (a syndicated or
 *                  consolidated original) — a sitemap lists the real {@code path}, the {@code <head>} names this
 * @since SPI 0.6.0
 */
public record LocalizedUrl(Locale locale, String path, String canonical) {

    public LocalizedUrl {
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(path, "path");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        canonical = (canonical == null || canonical.isBlank()) ? path : canonical;
    }

    /**
     * The absolute routing URL: {@code origin + path}.
     *
     * @param origin {@code scheme://host[:port]} with no trailing slash (a trailing {@code /} is tolerated and
     *               dropped), or {@code ""} for a root-relative result; never {@code null}
     * @return the absolute URL (never {@code null})
     */
    public String absolute(String origin) {
        return trimOrigin(origin) + path;
    }

    /**
     * This address as an {@code hreflang} {@link Alternate}: {@code (locale, origin + path)} — the real URL, never
     * the canonical override (an alternate must be an address that serves).
     *
     * @param origin as for {@link #absolute(String)}
     * @return the alternate (never {@code null})
     */
    public Alternate alternate(String origin) {
        return new Alternate(locale, absolute(origin));
    }

    private static String trimOrigin(String origin) {
        Objects.requireNonNull(origin, "origin");
        return origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
    }
}
