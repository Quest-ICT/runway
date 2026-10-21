package mx.quest.runway.log;

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
 * Reads and writes a site's {@link SiteLogConfig} ({@code sites/<id>/log.yml}). Cached per site (the capture
 * daemon reads it while writing), evicted on save and on {@link SiteChangedEvent}.
 */
@Service
public class SiteLogConfigService {

    private static final Logger log = LoggerFactory.getLogger(SiteLogConfigService.class);
    private static final String FILE = "log.yml";

    private final StorageLayout layout;
    private final YamlStore store;
    private final StoreCache<String, SiteLogConfig> cache = new StoreCache<>(512);

    public SiteLogConfigService(StorageLayout layout, YamlStore store) {
        this.layout = layout;
        this.store = store;
    }

    public SiteLogConfig get(String siteId) {
        return cache.get(siteId, this::load);
    }

    public void save(String siteId, SiteLogConfig config) throws IOException {
        store.write(file(siteId), config);
        cache.invalidate(siteId);
    }

    @EventListener
    @Order(KernelEventOrder.STORES)
    public void onSiteChanged(SiteChangedEvent event) {
        // Only a wholesale rewrite (delete/restore) changes log.yml out of band; save() self-evicts. Evicting on
        // every kind (e.g. suspend/resume) is a needless re-read.
        if (event.isWholesaleReplacement()) {
            cache.invalidate(event.siteId());
        }
    }

    private SiteLogConfig load(String siteId) {
        try {
            return store.read(file(siteId), SiteLogConfig.class).orElse(SiteLogConfig.DEFAULT);
        } catch (IOException | RuntimeException e) {
            log.warn("could not read {} for site '{}': {}", FILE, siteId, e.toString());
            return SiteLogConfig.DEFAULT;
        }
    }

    private Path file(String siteId) {
        return layout.siteDir(siteId).resolve(FILE);
    }
}
