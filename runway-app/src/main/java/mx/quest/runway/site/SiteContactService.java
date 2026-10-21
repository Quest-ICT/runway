package mx.quest.runway.site;

import java.io.IOException;
import java.nio.file.Path;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StoreCache;
import mx.quest.runway.storage.YamlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Reads and writes a site's {@link SiteContact} ({@code sites/<id>/contact.yml}).
 *
 * <p>Mirrors {@link SeoConfigService}/{@link SiteParamsService} exactly: a per-site {@link StoreCache} over the
 * hot read, evicted on {@link #save} and on any {@link SiteChangedEvent} (notably {@code DELETED}/{@code
 * RESTORED}, so a restored backup's contact takes effect at once), and the same tenant-scoped, fail-soft IO —
 * the id resolves only its own {@link StorageLayout#siteDir(String) folder}, and a missing/unreadable/malformed
 * file degrades to {@link SiteContact#EMPTY} rather than failing a page render.
 */
@Service
public class SiteContactService {

    private static final Logger log = LoggerFactory.getLogger(SiteContactService.class);
    private static final String FILE = "contact.yml";

    private final StorageLayout layout;
    private final YamlStore store;
    private final StoreCache<String, SiteContact> cache = new StoreCache<>(512);

    public SiteContactService(StorageLayout layout, YamlStore store) {
        this.layout = layout;
        this.store = store;
    }

    /** The site's contact information (cached; {@link SiteContact#EMPTY} when unset or unreadable). */
    public SiteContact get(String siteId) {
        return cache.get(siteId, this::load);
    }

    /**
     * Persists {@code contact} for {@code siteId} and evicts the cache so the next read sees it. An all-unset
     * contact deletes the file (the "no contact" state), so a cleared form does not leave an empty document.
     *
     * @throws IOException if the write (or delete) fails
     */
    public void save(String siteId, SiteContact contact) throws IOException {
        if (contact == null || contact.isEmpty()) {
            store.delete(file(siteId));
        } else {
            store.write(file(siteId), contact);
        }
        cache.invalidate(siteId);
    }

    /** Evict the affected site's entry on any lifecycle change (delete/restore/rename/etc.). */
    @EventListener
    @Order(KernelEventOrder.STORES)
    public void onSiteChanged(SiteChangedEvent event) {
        cache.invalidate(event.siteId());
    }

    private SiteContact load(String siteId) {
        try {
            return store.read(file(siteId), SiteContact.class).orElse(SiteContact.EMPTY);
        } catch (IOException | RuntimeException e) { // RuntimeException covers UncheckedIOException/parse errors
            log.warn("could not read {} for site '{}' — serving with no contact: {}", FILE, siteId, e.toString());
            return SiteContact.EMPTY;
        }
    }

    private Path file(String siteId) {
        return layout.siteDir(siteId).resolve(FILE);
    }
}
