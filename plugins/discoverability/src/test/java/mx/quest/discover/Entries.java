package mx.quest.discover;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.SiteView;

/**
 * Builds the {@link CatalogEntry} the host would list for a {@link ContentRef}, applying the kernel's documented URL
 * rule (its single URL authority, mirrored here so unit tests need no kernel): one URL per <b>available</b> locale —
 * the item's declared locales the site serves, else the site default — each the locale's localized path under
 * {@code /<lang>} (no prefix for the site default); the canonical of each URL is the item's override as given; the
 * {@code x-default} is the primary locale's URL, or — when the site does not serve the primary — the first available
 * locale's (SPI 1.0.0; until then it was clamped to the site default, an address the item may not exist at).
 * The host-it profile checks this mirror against the real kernel ({@code HostCatalogParityTest}).
 */
final class Entries {

    private Entries() {
    }

    static CatalogEntry of(String sourceKey, ContentRef ref, SiteView site) {
        Locale def = site.defaultLocale();
        List<Locale> available = new ArrayList<>();
        for (Locale locale : ref.locales()) {
            if (site.locales().contains(locale)) {
                available.add(locale);
            }
        }
        if (available.isEmpty()) {
            available.add(def);
        }
        List<LocalizedUrl> urls = new ArrayList<>();
        for (Locale locale : available) {
            urls.add(new LocalizedUrl(locale, url(ref, locale, def), ref.canonical()));
        }
        Locale primary = ref.primaryLocale(def);
        if (!site.locales().contains(primary)) {
            primary = available.get(0);
        }
        return new CatalogEntry(sourceKey, ref, urls, url(ref, primary, def));
    }

    static CatalogEntry page(ContentRef ref, SiteView site) {
        return of("pages", ref, site);
    }

    private static String url(ContentRef ref, Locale locale, Locale def) {
        String path = ref.pathFor(locale);
        return locale.equals(def) ? path : "/" + locale.getLanguage() + path;
    }
}
