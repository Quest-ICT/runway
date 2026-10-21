package mx.quest.runway.page;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.storage.FileStamp;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StoreCache;
import mx.quest.runway.storage.YamlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Loads tenant pages from {@code sites/<siteId>/templates/<slug>.meta.yml}. The slug (file name) is
 * the stable id; the YAML supplies the rest. Cached by {@code siteId|slug}.
 *
 * <p>A cached record is served only while its {@code .meta.yml} is unchanged on disk ({@link FileStamp}, one
 * {@code stat} per lookup): the page ETag folds that file's bytes, so a record cached from the previous version of
 * a hand-edited file would put the old title/description under the new ETag. Console writes still evict at once.
 */
@Component
public class PageStore {

    private static final Logger log = LoggerFactory.getLogger(PageStore.class);

    private static final String TEMPLATES_DIR = "templates";
    private static final String META_SUFFIX = ".meta.yml";

    private final StorageLayout layout;
    private final YamlStore store;
    private final StoreCache<String, Stamped> cache = new StoreCache<>(4096);

    /** A cached record and the stamp of the {@code .meta.yml} it was read from (taken before the read). */
    private record Stamped(Page page, FileStamp stamp) {
    }

    public PageStore(StorageLayout layout, YamlStore store) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** The page with this slug in the given site, or empty if absent (or the id is unsafe). */
    public Optional<Page> find(String siteId, String slug) {
        if (!PathSafety.isSafeId(siteId) || !PathSafety.isSafeId(slug)) {
            return Optional.empty();
        }
        String key = PathSafety.compoundKey(siteId, slug);
        Stamped hit = cache.peek(key);
        if (hit != null && !hit.stamp().isCurrent()) {
            cache.invalidate(key); // the file changed on disk since it was read: re-read it
        }
        Stamped entry = cache.get(key, k -> load(siteId, slug));
        return Optional.ofNullable(entry).map(Stamped::page);
    }

    /** Every page in the site, ordered by slug. */
    public List<Page> findAll(String siteId) {
        if (!PathSafety.isSafeId(siteId)) {
            return List.of();
        }
        Path templates = layout.siteDir(siteId).resolve(TEMPLATES_DIR);
        if (!Files.isDirectory(templates)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(templates)) {
            return entries.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(META_SUFFIX))
                    .map(name -> name.substring(0, name.length() - META_SUFFIX.length()))
                    // skip masters/error pages (_…) and any transient preview scratch page (rw-preview-…)
                    .filter(slug -> !slug.startsWith("_")
                            && !slug.startsWith(PageAuthoringValidator.SCRATCH_SLUG_PREFIX)
                            && PathSafety.isSafeId(slug))
                    .sorted()
                    .map(slug -> find(siteId, slug))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("listing pages for " + siteId, e);
        }
    }

    /**
     * The published page whose public path <b>for {@code locale}</b> equals {@code requestPath}.
     * Matches only {@link Page#pathFor(Locale)} (not the default {@link Page#path()}), so a localized
     * URL resolves to exactly one (page, locale) and never doubles as another locale's URL.
     *
     * <p><b>Fail-closed on collision:</b> if two pages resolve to the same path for this locale, this
     * throws rather than silently shadowing one — the platform's never-silently-shadow discipline.
     *
     * @throws IllegalStateException if more than one page matches (an ambiguous route to fix)
     */
    public Optional<Page> findByPath(String siteId, String requestPath, Locale locale) {
        List<Page> matches = findAll(siteId).stream()
                .filter(Page::published)
                .filter(page -> requestPath.equals(page.pathFor(locale)))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("ambiguous route '" + requestPath + "' (locale "
                    + locale.getLanguage() + ") matches pages " + matches.stream().map(Page::slug).toList()
                    + " — two pages claim the same path/localizedPaths; fix the collision");
        }
        return matches.stream().findFirst();
    }

    /** Drops a page's cache entry (call when its files change out of band). */
    public void evict(String siteId, String slug) {
        if (PathSafety.isSafeId(siteId) && PathSafety.isSafeId(slug)) {
            cache.invalidate(PathSafety.compoundKey(siteId, slug));
        }
    }

    /**
     * Drops <b>all</b> cached pages — for a wholesale page invalidation where the individual slugs are not
     * known (a future site-wide page-change event). The cache is not partitioned by site, so this clears every
     * entry; that is coarse but correct (a re-read re-loads from disk) and matches {@link #onSiteChanged}'s
     * wholesale-replacement handling. {@code siteId} is accepted for symmetry and logging only.
     */
    public void evictSite(String siteId) {
        cache.invalidateAll();
    }

    /**
     * A site-level change (delete or restore) replaces the site's page files wholesale, so drop the whole
     * cache — no stale {@code siteId|slug} entry may survive a restore. (Mirrors {@code TemplateCacheService}.)
     */
    @EventListener
    @Order(KernelEventOrder.STORES)
    public void onSiteChanged(SiteChangedEvent event) {
        if (event.isWholesaleReplacement()) {
            cache.invalidateAll();
        }
    }

    /** Reads a page's record, stamped before the read; {@code null} (not cached) when absent or unsafe. */
    private Stamped load(String siteId, String slug) {
        Path file = layout.siteDir(siteId).resolve(TEMPLATES_DIR).resolve(slug + META_SUFFIX);
        FileStamp stamp = FileStamp.of(file);
        Page page = read(siteId, slug, file);
        return page == null ? null : new Stamped(page, stamp);
    }

    private Page read(String siteId, String slug, Path file) {
        try {
            Page page = store.read(file, Page.class).map(p -> p.withSlug(slug)).orElse(null);
            if (page != null && !PathSafety.isSafePagePath(page.path())) {
                // Fail closed: an unsafe path must never become a routing key. (Unsafe localized
                // overrides are already dropped by the Page constructor.)
                log.warn("skipping page {}/{}: unsafe path '{}'", siteId, slug, page.path());
                return null;
            }
            return page;
        } catch (IOException e) {
            throw new UncheckedIOException("reading page " + siteId + "/" + slug, e);
        }
    }
}
