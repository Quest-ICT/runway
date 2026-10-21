package mx.quest.runway.icon;

import java.io.IOException;
import java.nio.file.Path;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StoreCache;
import mx.quest.runway.storage.YamlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Reads and writes a site's {@link SiteManifest} ({@code sites/<id>/manifest.yml}). Cached per site (the
 * manifest endpoint and the head contributor read it), evicted on save and on {@link SiteChangedEvent}. A
 * missing or unreadable file degrades to {@link SiteManifest#EMPTY}.
 */
@Service
public class SiteManifestService {

    private static final Logger log = LoggerFactory.getLogger(SiteManifestService.class);
    private static final String FILE = "manifest.yml";

    private final StorageLayout layout;
    private final YamlStore store;
    private final StoreCache<String, SiteManifest> cache = new StoreCache<>(512);

    public SiteManifestService(StorageLayout layout, YamlStore store) {
        this.layout = layout;
        this.store = store;
    }

    /** The site's manifest (cached; {@link SiteManifest#EMPTY} when unset or unreadable). */
    public SiteManifest get(String siteId) {
        return cache.get(siteId, this::load);
    }

    /**
     * Persists {@code manifest} for {@code siteId} and evicts the cache.
     *
     * @throws IOException if the write fails
     */
    public void save(String siteId, SiteManifest manifest) throws IOException {
        store.write(file(siteId), manifest);
        cache.invalidate(siteId);
    }

    @EventListener
    @Order(KernelEventOrder.STORES)
    public void onSiteChanged(SiteChangedEvent event) {
        cache.invalidate(event.siteId());
    }

    private SiteManifest load(String siteId) {
        try {
            return store.read(file(siteId), SiteManifest.class).orElse(SiteManifest.EMPTY);
        } catch (IOException | RuntimeException e) {
            log.warn("could not read {} for site '{}': {}", FILE, siteId, e.toString());
            return SiteManifest.EMPTY;
        }
    }

    private Path file(String siteId) {
        return layout.siteDir(siteId).resolve(FILE);
    }
}
