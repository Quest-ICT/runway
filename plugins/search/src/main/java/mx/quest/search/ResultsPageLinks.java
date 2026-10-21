package mx.quest.search;

import java.util.Locale;
import java.util.Objects;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.service.ContentCatalogView;

/**
 * Where the search box submits in a given language (since 2.2.0). The {@code resultsPath} setting names the results
 * page by one of its addresses; a box on a Spanish page must submit to that page's Spanish address, so the results
 * — and the documents searched — are Spanish. Before 2.2.0 every box submitted to the setting as written, which
 * sent a visitor on {@code /es/…} to the default-language results.
 */
class ResultsPageLinks {

    private final ContentCatalogView catalog;

    ResultsPageLinks(ContentCatalogView catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * The results page's address in {@code locale}'s language: the page whose address in some language is
     * {@code resultsPath} (compared as {@link SiteSearchCorpus#normalizePath} does), at its address in that
     * language. {@code resultsPath} as written when no page has that address, or the page does not exist in
     * that language.
     *
     * @param siteId      the site
     * @param resultsPath the {@code resultsPath} setting
     * @param locale      the language of the page the box is on
     * @return the address to submit to; never {@code null}
     */
    String pathFor(String siteId, String resultsPath, Locale locale) {
        String wanted = SiteSearchCorpus.normalizePath(resultsPath);
        try {
            for (CatalogEntry entry : catalog.entries(siteId)) {
                if (entry.isPage() && hasAddress(entry, wanted)) {
                    for (LocalizedUrl url : entry.urls()) {
                        if (url.locale().getLanguage().equals(locale.getLanguage())) {
                            return url.path();
                        }
                    }
                    return resultsPath;
                }
            }
        } catch (RuntimeException e) {
            // The listing is the kernel's; if it cannot be read the box still submits to the setting as written.
        }
        return resultsPath;
    }

    private static boolean hasAddress(CatalogEntry entry, String wanted) {
        for (LocalizedUrl url : entry.urls()) {
            if (wanted.equals(SiteSearchCorpus.normalizePath(url.path()))) {
                return true;
            }
        }
        return false;
    }
}
