package mx.quest.database.internal;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import mx.quest.runway.spi.service.RequestContext;
import mx.quest.runway.spi.service.Sites;

/**
 * The parts every consumer handle shares: the runtime, the site schemas, the migrator, the kernel services, and the
 * per-site in-flight caps that keep one busy site from taking the whole pool.
 */
public final class DatabaseCore {

    private final DatabaseRuntime runtime;
    private final SiteSchemas schemas;
    private final Migrator migrator;
    private final Sites sites;
    private final RequestContext requestContext;
    private final Map<String, Semaphore> perSite = new ConcurrentHashMap<>();
    private final Semaphore app = new Semaphore(Integer.MAX_VALUE);

    public DatabaseCore(DatabaseRuntime runtime, SiteSchemas schemas, Migrator migrator, Sites sites,
                        RequestContext requestContext) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        this.migrator = Objects.requireNonNull(migrator, "migrator");
        this.sites = Objects.requireNonNull(sites, "sites");
        this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
    }

    public DatabaseRuntime runtime() {
        return runtime;
    }

    public SiteSchemas schemas() {
        return schemas;
    }

    public Migrator migrator() {
        return migrator;
    }

    public Sites sites() {
        return sites;
    }

    public RequestContext requestContext() {
        return requestContext;
    }

    /** The in-flight cap for a site: half the pool (at least one). */
    Semaphore siteSlots(String siteId) {
        DatabaseConfig c = runtime.config();
        int permits = c == null ? 1 : Math.max(1, c.poolMax() / 2);
        return perSite.computeIfAbsent(siteId, id -> new Semaphore(permits, true));
    }

    /** The app scope is not capped beyond the pool itself. */
    Semaphore appSlots() {
        return app;
    }

    /** Forgets a deleted site's cap. */
    void forgetSite(String siteId) {
        perSite.remove(siteId);
    }
}
