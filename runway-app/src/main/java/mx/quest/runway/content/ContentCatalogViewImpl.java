package mx.quest.runway.content;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.Sites;
import mx.quest.runway.storage.PathSafety;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The kernel's {@link ContentCatalogView}: a thin, id-keyed face over {@link ContentCatalog}, which keeps the
 * fail-closed re-filter, the per-site cache with its eviction epochs, the per-source failure containment and the
 * last-seen URL index. Every method resolves the site <b>here</b> from its id (review m1) — a plugin can never fill
 * the shared per-site cache from a stale or hand-built {@link SiteView}.
 *
 * <p>Unsafe ids throw {@link IllegalArgumentException} (through {@link Sites#find}); an unknown, well-formed id
 * answers empty/{@code ""}/{@code false}.
 */
@Component
public class ContentCatalogViewImpl implements ContentCatalogView {

    private final ObjectProvider<ContentCatalog> catalogProvider;
    private final Sites sites;
    private final PageStore pages;

    /**
     * @param catalog resolved lazily: a plugin content source may itself depend on this view (to check a prefix with
     *                {@link #servablePath}), while the catalog depends on every source through its registry — an
     *                eager reference would be a construction cycle
     */
    @Autowired
    public ContentCatalogViewImpl(ObjectProvider<ContentCatalog> catalog, Sites sites, PageStore pages) {
        this.catalogProvider = Objects.requireNonNull(catalog, "catalog");
        this.sites = Objects.requireNonNull(sites, "sites");
        this.pages = Objects.requireNonNull(pages, "pages");
    }

    /** Test form over a ready catalog. */
    public ContentCatalogViewImpl(ContentCatalog catalog, Sites sites, PageStore pages) {
        this(new ObjectProvider<>() {
            @Override
            public ContentCatalog getObject() {
                return catalog;
            }
        }, sites, pages);
    }

    private ContentCatalog catalog() {
        return catalogProvider.getObject();
    }

    @Override
    public List<CatalogEntry> entries(String siteId) {
        return sites.find(siteId).map(catalog()::entries).orElse(List.of());
    }

    @Override
    public String indexableText(String siteId, CatalogEntry entry, Locale locale) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(locale, "locale");
        Optional<SiteView> site = sites.find(siteId);
        if (site.isEmpty()) {
            return "";
        }
        // ContentCatalog.indexableText is capability-gated and failure-contained already ("" on a throwing source).
        return catalog().indexableText(site.get(), new RoutableContent(entry.sourceKey(), entry.ref()), locale);
    }

    @Override
    public Optional<String> pageAt(String siteId, String path, Locale locale) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(locale, "locale");
        if (sites.find(siteId).isEmpty()) {
            return Optional.empty();
        }
        // Not PageStore.findByPath: that one THROWS on an ambiguous route (right for serving, where a silent pick
        // would shadow a page). This is an advisory question — "is the path taken?" — and two claimants still mean
        // "taken"; answer the first by slug, deterministically.
        return pages.findAll(siteId).stream()
                .filter(Page::published)
                .filter(page -> path.equals(page.pathFor(locale)))
                .map(Page::slug)
                .min(Comparator.naturalOrder());
    }

    @Override
    public boolean servablePath(String siteId, String path) {
        Objects.requireNonNull(path, "path");
        return sites.find(siteId).isPresent() && catalog().servablePath(path);
    }

    @Override
    public void refresh(String siteId) {
        if (siteId == null || !PathSafety.isSafeId(siteId)) {
            throw new IllegalArgumentException("unsafe site id: " + siteId);
        }
        catalog().evict(siteId);
    }
}
