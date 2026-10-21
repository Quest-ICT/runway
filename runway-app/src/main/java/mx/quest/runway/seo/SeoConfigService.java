package mx.quest.runway.seo;

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
 * Reads and writes a site's {@link SiteSeoConfig} ({@code sites/<id>/seo.yml}).
 *
 * <p>The configuration is read on <b>every public page render</b> (the head contributor needs it), so it is
 * cached per site; the cache is evicted on save and whenever the site changes ({@link SiteChangedEvent} —
 * notably {@code DELETED} and {@code RESTORED}, so a restored backup's SEO takes effect at once). A missing
 * or unreadable file degrades to {@link SiteSeoConfig#EMPTY} rather than failing a page render.
 */
@Service
public class SeoConfigService {

    private static final Logger log = LoggerFactory.getLogger(SeoConfigService.class);
    private static final String FILE = "seo.yml";

    private final StorageLayout layout;
    private final YamlStore store;
    private final StoreCache<String, SiteSeoConfig> cache = new StoreCache<>(512);

    public SeoConfigService(StorageLayout layout, YamlStore store) {
        this.layout = layout;
        this.store = store;
    }

    /** The site's SEO configuration (cached; {@link SiteSeoConfig#EMPTY} when unset or unreadable). */
    public SiteSeoConfig get(String siteId) {
        return cache.get(siteId, this::load);
    }

    /**
     * Persists {@code config} for {@code siteId} and evicts the cache so the next render sees it.
     *
     * @throws IOException if the write fails
     */
    public void save(String siteId, SiteSeoConfig config) throws IOException {
        store.write(file(siteId), config);
        cache.invalidate(siteId);
    }

    /** Evict the affected site's entry on any lifecycle change (delete/restore/rename/etc.). */
    @EventListener
    @Order(KernelEventOrder.STORES)
    public void onSiteChanged(SiteChangedEvent event) {
        cache.invalidate(event.siteId());
    }

    private SiteSeoConfig load(String siteId) {
        try {
            return store.read(file(siteId), SiteSeoConfig.class).orElse(SiteSeoConfig.EMPTY);
        } catch (IOException | RuntimeException e) { // RuntimeException covers UncheckedIOException/parse errors
            log.warn("could not read {} for site '{}' — serving with no SEO tags: {}", FILE, siteId, e.toString());
            return SiteSeoConfig.EMPTY;
        }
    }

    private Path file(String siteId) {
        return layout.siteDir(siteId).resolve(FILE);
    }
}
