package mx.quest.search;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.search.spi.SearchCorpus;

/**
 * The search plugin's {@link SearchCorpus}: the kernel's {@link ContentCatalogView} filtered by this plugin's own
 * per-site {@code exclude} setting (read through {@link PluginSettings}, bound to this plugin), and without the
 * site's <b>results page</b> (since 2.1.1): the page at the {@code resultsPath} setting carries the search box and
 * describes search, so it matched queries about the site's own subjects and was listed among its own results.
 * It caches nothing —
 * the kernel caches the listing and the settings view — so a settings save or a content change is visible on the
 * very next call.
 */
public class SiteSearchCorpus implements SearchCorpus {

    private final ContentCatalogView catalog;
    private final PluginSettings settings;

    public SiteSearchCorpus(ContentCatalogView catalog, PluginSettings settings) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public List<CatalogEntry> corpus(String siteId) {
        List<CatalogEntry> entries = catalog.entries(siteId);
        Exclusions excluded = excluded(siteId);
        return entries.stream().filter(entry -> !excluded.matches(entry)).toList();
    }

    @Override
    public String indexableText(String siteId, CatalogEntry entry, Locale locale) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(locale, "locale");
        if (excluded(siteId).matches(entry)) {
            return "";
        }
        return catalog.indexableText(siteId, entry, locale);
    }

    @Override
    public void refresh(String siteId) {
        catalog.refresh(siteId);
    }

    private Exclusions excluded(String siteId) {
        SearchSiteConfig cfg = SearchSiteConfig.of(settings.site(siteId));
        return new Exclusions(Set.copyOf(cfg.excludedIds()), normalizePath(cfg.resultsPath()));
    }

    /** Lowercase, leading {@code /}, no trailing {@code /} (except the root itself). */
    static String normalizePath(String path) {
        String p = path == null ? "" : path.strip().toLowerCase(Locale.ROOT);
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * What a site leaves out: the {@code exclude} ids, and the page at {@code resultsPath} in any of its languages
     * (a page whose address in some locale is the results path is the results page; its other localized addresses
     * go with it). A results path of {@code /} never excludes the home page.
     */
    private record Exclusions(Set<String> ids, String resultsPath) {

        boolean matches(CatalogEntry entry) {
            if (ids.contains(entry.ref().id().toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (!entry.isPage() || "/".equals(resultsPath)) {
                return false;
            }
            for (LocalizedUrl url : entry.urls()) {
                if (resultsPath.equals(normalizePath(url.path()))) {
                    return true;
                }
            }
            return false;
        }
    }
}
