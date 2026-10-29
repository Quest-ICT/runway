package mx.quest.runway.site;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StoreCache;
import mx.quest.runway.storage.YamlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads and writes the per-site {@code site.yml} records, with a bounded cache over the hot reads.
 * Owns the {@link Site} record persistence only; folder-level lifecycle (creating/removing a
 * site's directory tree) belongs to {@link SiteLifecycleService}.
 */
@Component
public class SiteStore {

    private static final Logger log = LoggerFactory.getLogger(SiteStore.class);
    private static final String SITE_FILE = "site.yml";

    private final StorageLayout layout;
    private final YamlStore store;
    private final StoreCache<String, Site> cache = new StoreCache<>(2048);

    public SiteStore(StorageLayout layout, YamlStore store) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * The stored site with this id, or empty if it does not exist (or the id is unsafe).
     *
     * @throws UncheckedIOException when its record cannot be read, its folder included (a folder without search
     *                              permission, a stalled mount): "cannot tell" is never answered as "no such site"
     */
    public Optional<Site> find(String id) {
        if (!PathSafety.isSafeId(id)) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get(id, this::load));
    }

    /** Every stored site, ordered by id. Sites whose folder lacks a readable {@code site.yml} are skipped. */
    public List<Site> findAll() {
        Path sitesRoot = layout.sites();
        if (!Files.isDirectory(sitesRoot)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(sitesRoot)) {
            return entries.filter(Files::isDirectory)
                    .map(dir -> dir.getFileName().toString())
                    .filter(PathSafety::isSafeId)
                    .map(this::findForListing)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(Site::id))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("listing sites under " + sitesRoot, e);
        }
    }

    /**
     * The ids of every site folder, ordered, without reading any {@code site.yml}: for a caller that must go on
     * past a site whose record cannot be read (see {@link SiteHosts}).
     */
    public List<String> ids() {
        Path sitesRoot = layout.sites();
        if (!Files.isDirectory(sitesRoot)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(sitesRoot)) {
            return entries.filter(Files::isDirectory)
                    .map(dir -> dir.getFileName().toString())
                    .filter(PathSafety::isSafeId)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("listing sites under " + sitesRoot, e);
        }
    }

    /** Atomically writes the site's record and evicts its cache entry. */
    public void save(Site site) {
        Objects.requireNonNull(site, "site");
        try {
            store.write(siteFile(site.id()), site);
        } catch (IOException e) {
            throw new UncheckedIOException("writing site " + site.id(), e);
        }
        cache.invalidate(site.id());
    }

    /** Whether a stored {@code site.yml} exists for this id. */
    public boolean exists(String id) {
        return PathSafety.isSafeId(id) && store.exists(siteFile(id));
    }

    /** Drops this site's cache entry (call after its folder is removed, or its file changes out of band). */
    public void evict(String id) {
        if (id != null) {
            cache.invalidate(id);
        }
    }

    private Optional<Site> findForListing(String id) {
        try {
            return find(id);
        } catch (UncheckedIOException e) {
            log.warn("Site '{}' is left out of the list: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    private Site load(String id) {
        Path file = siteFile(id);
        try {
            // Only a file that is not there means no site: Files.isRegularFile would also say false when the folder
            // cannot be searched, and a plugin would then take a live site for a deleted one.
            if (!Files.readAttributes(file, BasicFileAttributes.class).isRegularFile()) {
                return null;
            }
            return store.read(file, Site.class).orElse(null);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("reading site " + id, e);
        }
    }

    private Path siteFile(String id) {
        return layout.siteDir(id).resolve(SITE_FILE);
    }
}
