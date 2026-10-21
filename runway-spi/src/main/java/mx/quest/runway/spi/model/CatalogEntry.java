package mx.quest.runway.spi.model;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One item of a site's published content surface — a real page or a content source's item — as
 * {@link mx.quest.runway.spi.service.ContentCatalogView} lists it: the item's metadata plus the URLs the host
 * computed for it. Immutable.
 *
 * @param sourceKey    the {@link mx.quest.runway.spi.ContentSource#key()} that contributed the item;
 *                     {@code "pages"} ({@link mx.quest.runway.spi.service.ContentCatalogView#PAGES_SOURCE}) for a
 *                     real page. Never {@code null}
 * @param ref          the item's metadata as its source declared it (for a page, the id is the page's slug).
 *                     Never {@code null}
 * @param urls         one {@link LocalizedUrl} per <b>available</b> locale — the locales the item declares that the
 *                     site also serves, or the site's default locale alone when none survive. Never empty for an
 *                     entry the host built; copied and unmodifiable
 * @param xDefaultPath the root-relative {@code x-default} address: the URL of the item's primary locale, or — when
 *                     the site does not serve the primary — of the item's first <b>available</b> locale (the first
 *                     of {@code urls}), so it is always an address the site serves for this item. Never
 *                     {@code null}. (Before SPI 1.0.0 the fallback was the site's default locale, which could name
 *                     an unprefixed path the item does not exist at.)
 * @since SPI 0.6.0
 */
public record CatalogEntry(String sourceKey, ContentRef ref, List<LocalizedUrl> urls, String xDefaultPath) {

    public CatalogEntry {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(ref, "ref");
        urls = (urls == null) ? List.of() : List.copyOf(urls);
        if (xDefaultPath == null || xDefaultPath.isBlank()) {
            xDefaultPath = urls.isEmpty() ? ref.path() : urls.get(0).path();
        }
    }

    /**
     * The item's identity across sources: {@code <sourceKey>:<id>} ({@code pages:<slug>} for a page). Stable,
     * collision-free (a {@code :} never appears in a page slug or a source id), and the key the change events of
     * {@link mx.quest.runway.spi.service.SiteEventListener} name items by. Never a file name.
     */
    public String compositeId() {
        return sourceKey + ":" + ref.id();
    }

    /** Whether this entry is a real page (its source is {@code "pages"}). */
    public boolean isPage() {
        return "pages".equals(sourceKey);
    }

    /**
     * The root-relative URL to link this item from content rendered in {@code locale}: that locale's address when
     * the item is available in it, else {@link #xDefaultPath()} when that is one of {@link #urls()} (always so for
     * an entry the host built), else the first of {@link #urls()}. An item that does not exist in the requested
     * locale is therefore linked where it does exist, never to a URL that 404s — even for an entry built by hand
     * with an {@code xDefaultPath} outside its own URLs. With no URLs at all, {@link #xDefaultPath()}.
     *
     * @param locale the locale of the linking context (never {@code null})
     * @return a root-relative URL (never {@code null})
     */
    public String pathFor(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        for (LocalizedUrl url : urls) {
            if (url.locale().equals(locale)) {
                return url.path();
            }
        }
        if (urls.isEmpty()) {
            return xDefaultPath;
        }
        for (LocalizedUrl url : urls) {
            if (url.path().equals(xDefaultPath)) {
                return xDefaultPath;
            }
        }
        return urls.get(0).path();
    }
}
