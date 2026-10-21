package mx.quest.search.spi;

import java.util.Locale;
import java.util.Objects;

/**
 * An immutable search request handed to a {@link SiteSearchEngine}: the bound site, the raw query text, the
 * request locale (so a locale-aware engine can prefer that language), and the maximum number of hits to return.
 *
 * @param siteId the bound tenancy the search is scoped to (never cross-site)
 * @param text   the user's query, trimmed and non-blank (the surface rejects blanks before calling an engine)
 * @param locale the request locale
 * @param limit  the maximum number of hits to return (always {@code >= 1})
 */
public record SearchQuery(String siteId, String text, Locale locale, int limit) {

    public SearchQuery {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(text, "text");
        locale = (locale == null) ? Locale.ENGLISH : locale;
        limit = Math.max(1, limit);
    }
}
