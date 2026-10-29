package mx.quest.database.internal;

import java.util.concurrent.Executor;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.service.SiteEventListener;

/**
 * Site creations and deletions. The listener only evicts and enqueues: the work runs on the plugin's executor, and
 * only on the node where the change happened ({@code local}), because every node shares the database. A restore's
 * database work happens in {@link DatabaseBackup#importInto}; nothing is done here for it.
 */
public final class DatabaseSiteEvents implements SiteEventListener {

    private final DatabaseCore core;
    private final Executor executor;

    public DatabaseSiteEvents(DatabaseCore core, Executor executor) {
        this.core = core;
        this.executor = executor;
    }

    @Override
    public void onSiteLifecycle(SiteLifecycleEvent event) {
        switch (event.kind()) {
            case DELETED -> {
                core.schemas().evict(event.siteId());
                core.forgetSite(event.siteId());
                if (event.local()) {
                    String siteId = event.siteId();
                    executor.execute(() -> core.schemas().dropDeleted(siteId));
                }
            }
            case CREATED -> {
                if (event.local()) {
                    String siteId = event.siteId();
                    executor.execute(() -> core.schemas().retireEarlierSite(siteId));
                }
            }
            default -> {
                // RESTORED included: the cached mapping stays, since every borrow re-checks it against the registry
                // and a restore that replaces the data changes the registry. Evicting here would make the next
                // request on every node resolve again while the restored folder may not name the data yet.
            }
        }
    }
}
