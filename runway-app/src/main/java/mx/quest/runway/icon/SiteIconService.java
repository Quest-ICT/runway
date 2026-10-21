package mx.quest.runway.icon;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.storage.SiteFileStore;
import mx.quest.runway.storage.StoreCache;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Stores, reads and removes a site's icon files through the shared {@link SiteFileStore}, keyed by the fixed
 * {@link SiteIcon} slots. All the tenant-isolation and crash-safety guarantees live in the store; this service
 * only maps a slot to its storage path and enforces the per-icon size ceiling.
 *
 * <p>Which slots a site has is cached per site — the head contributor consults it on every public render — and
 * evicted on store/remove and on any {@link SiteChangedEvent} (so a restored backup's icons take effect).
 */
@Service
public class SiteIconService {

    /** Icons are tiny; a quarter-megabyte is generous and caps a hostile or accidental over-size upload. */
    static final long MAX_ICON_BYTES = 256 * 1024;

    private final SiteFileStore files;
    private final StoreCache<String, Set<SiteIcon>> presence = new StoreCache<>(512);

    public SiteIconService(SiteFileStore files) {
        this.files = files;
    }

    /** The slots this site has provided (cached) — for building manifest icons and head links. */
    public Set<SiteIcon> presentSlots(String siteId) {
        return presence.get(siteId, this::scan);
    }

    @EventListener
    @Order(KernelEventOrder.STORES)
    public void onSiteChanged(SiteChangedEvent event) {
        presence.invalidate(event.siteId());
    }

    private Set<SiteIcon> scan(String siteId) {
        EnumSet<SiteIcon> found = EnumSet.noneOf(SiteIcon.class);
        for (SiteIcon icon : SiteIcon.values()) {
            if (files.exists(siteId, icon.storagePath())) {
                found.add(icon);
            }
        }
        return found;
    }

    /**
     * Stores {@code bytes} for {@code icon} on {@code siteId}.
     *
     * @throws IllegalArgumentException if the upload exceeds {@link #MAX_ICON_BYTES}
     * @throws IOException              if the write fails
     */
    public void store(String siteId, SiteIcon icon, byte[] bytes) throws IOException {
        files.write(siteId, icon.storagePath(), bytes, MAX_ICON_BYTES);
        presence.invalidate(siteId);
    }

    /** Removes {@code icon} from {@code siteId}; returns whether a file was actually present. */
    public boolean remove(String siteId, SiteIcon icon) throws IOException {
        boolean removed = files.delete(siteId, icon.storagePath());
        presence.invalidate(siteId);
        return removed;
    }

    /** The stored bytes for {@code icon}, or empty when the site has not provided that slot. */
    public Optional<byte[]> read(String siteId, SiteIcon icon) {
        return files.read(siteId, icon.storagePath());
    }

    /** Whether {@code siteId} has provided {@code icon}. */
    public boolean present(String siteId, SiteIcon icon) {
        return files.exists(siteId, icon.storagePath());
    }

    /** The per-icon upload ceiling, in bytes (for the screen's client-side hint and server-side check). */
    public long maxBytes() {
        return MAX_ICON_BYTES;
    }
}
