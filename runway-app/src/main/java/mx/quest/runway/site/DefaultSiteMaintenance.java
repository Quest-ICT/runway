package mx.quest.runway.site;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.platform.PlatformSettings;
import mx.quest.runway.platform.PlatformSettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Keeps the platform's catch-all {@linkplain PlatformSettingsStore default-site} pointer consistent with the
 * site lifecycle: when the default site is <b>deleted</b>, its {@code defaultSiteId} is cleared so the pointer
 * never dangles (unmatched hosts then 404 cleanly, and the Settings screen no longer offers a phantom
 * selection). Suspension needs no cleanup — {@link SiteResolver} only falls back to an <i>active</i> default,
 * so a suspended default simply stops catching unbound hosts and resumes when the site does.
 *
 * <p>A deleted site's own bandwidth cap ({@link PlatformSettings#siteQuotas}) and storage ceiling
 * ({@link PlatformSettings#siteStorageCeilings}) are removed too, so a new site created later with the same id starts
 * from the platform defaults rather than inheriting them.
 *
 * <p>Lives in the {@code site} package (which already depends on {@code platform} via {@link SiteResolver}) so
 * no {@code platform → site} back-edge is introduced.
 */
@Component
public class DefaultSiteMaintenance {

    private static final Logger log = LoggerFactory.getLogger(DefaultSiteMaintenance.class);

    private final PlatformSettingsStore platform;

    public DefaultSiteMaintenance(PlatformSettingsStore platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    @EventListener
    @Order(KernelEventOrder.KERNEL_LATE)
    public void onSiteChanged(SiteChangedEvent event) {
        if (event.kind() != SiteChangedEvent.Kind.DELETED) {
            return;
        }
        AtomicBoolean wasDefault = new AtomicBoolean();
        // One atomic change: the quota, the storage ceiling and the default pointer go together, decided against the
        // latest settings (not a snapshot taken before the lock). Nothing of this site's there returns them
        // unchanged: no write.
        platform.update(current -> {
            PlatformSettings next = current;
            if (next.siteQuotas().containsKey(event.siteId())) {
                next = next.withSiteQuota(event.siteId(), null);
            }
            if (next.siteStorageCeilings().containsKey(event.siteId())) {
                next = next.withSiteStorageCeiling(event.siteId(), null);
            }
            if (isDefault(next, event.siteId())) {
                next = next.withDefaultSiteId(null);
                wasDefault.set(true);
            }
            return next;
        });
        if (wasDefault.get()) {
            log.warn("cleared the catch-all default site '{}' — it was deleted; unmatched hosts now 404 "
                    + "until a new default is set", event.siteId());
        }
    }

    private static boolean isDefault(PlatformSettings settings, String siteId) {
        return settings.defaultSite().filter(id -> id.equals(siteId)).isPresent();
    }
}
