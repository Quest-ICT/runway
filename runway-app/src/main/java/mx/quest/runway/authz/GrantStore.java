package mx.quest.runway.authz;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StoreCache;
import mx.quest.runway.storage.YamlStore;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Reads and writes the per-scope grant files (role assignments + direct permission grants): the
 * application-wide {@code external/app-grants.yml} and each tenancy's {@code sites/<id>/permissions.yml}.
 * Cached per scope; part of the trusted computing base (external root / inside the tenant folder, never
 * web-served). An absent file is an {@linkplain GrantFile#empty() empty} grant set.
 *
 * <h2>One scope, one lock: a change goes through {@link #updateAppGrants} / {@link #updateSiteGrants}</h2>
 * Every console change to a grant file is a read-modify-write (assign a role, grant a permission, a role delta). Run
 * as {@code save(get().withX(..))}, two administrators writing the same scope at the same moment each read the old
 * file, and the second write silently drops the first. The update methods read the latest file from disk, apply the
 * change and write it under that scope's lock, so both survive. A scope is the application realm or one site; two
 * sites never wait for each other.
 */
@Component
public class GrantStore {

    private static final String APP_FILE = "app-grants.yml";
    private static final String SITE_FILE = "permissions.yml";
    private static final String APP_SCOPE = "app";

    private final StorageLayout layout;
    private final YamlStore yaml;
    private final StoreCache<String, GrantFile> cache = new StoreCache<>(512);
    /** One monitor per scope ({@link #APP_SCOPE} or {@code site|<id>}), held across each read-modify-write. */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public GrantStore(StorageLayout layout, YamlStore yaml) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.yaml = Objects.requireNonNull(yaml, "yaml");
    }

    /** The application-realm grants. */
    public GrantFile appGrants() {
        return cache.get(APP_SCOPE, key -> read(appFile()));
    }

    /** A tenancy's grants. */
    public GrantFile siteGrants(String siteId) {
        return cache.get(siteScope(siteId), key -> read(siteFile(siteId)));
    }

    /**
     * Replaces the application grants wholesale and evicts the cache (seeding, tests). A change to the current grants
     * goes through {@link #updateAppGrants}, which cannot lose another writer's change.
     */
    public void saveAppGrants(GrantFile grants) {
        synchronized (lock(APP_SCOPE)) {
            write(appFile(), grants);
            cache.invalidate(APP_SCOPE);
        }
    }

    /** Replaces a tenancy's grants wholesale and evicts the cache; see {@link #saveAppGrants}. */
    public void saveSiteGrants(String siteId, GrantFile grants) {
        synchronized (lock(siteScope(siteId))) {
            write(siteFile(siteId), grants);
            cache.invalidate(siteScope(siteId));
        }
    }

    /**
     * Atomically changes the application grants: {@code change} receives the latest grants, read from disk, and returns
     * the new ones, which are written before the scope's lock is released. If it throws, nothing is written. Keep it
     * pure and cheap: it runs under the lock.
     *
     * @return the grants now in force
     */
    public GrantFile updateAppGrants(UnaryOperator<GrantFile> change) {
        return update(APP_SCOPE, appFile(), change);
    }

    /** Atomically changes a tenancy's grants; see {@link #updateAppGrants}. */
    public GrantFile updateSiteGrants(String siteId, UnaryOperator<GrantFile> change) {
        return update(siteScope(siteId), siteFile(siteId), change);
    }

    /**
     * The read-modify-write of one scope. It reads the file, not the cache: a cached copy loaded before another
     * writer's change could otherwise be the base of this one.
     */
    private GrantFile update(String scope, Path file, UnaryOperator<GrantFile> change) {
        Objects.requireNonNull(change, "change");
        synchronized (lock(scope)) {
            GrantFile current = read(file);
            GrantFile next = Objects.requireNonNull(change.apply(current), "change result");
            if (!next.equals(current)) {
                write(file, next);
            }
            cache.invalidate(scope);
            return next;
        }
    }

    /**
     * The monitor of {@code scope}, shared with {@link AuthorizationAdminService} so that a change spanning the roles
     * file and the grants file of one scope (deleting a role) is one critical section.
     */
    Object lock(String scope) {
        return locks.computeIfAbsent(scope, key -> new Object());
    }

    /** The scope key of the application realm ({@code tenancyId == null}) or of a tenancy, for {@link #lock}. */
    static String scopeOf(String tenancyId) {
        return tenancyId == null ? APP_SCOPE : siteScope(tenancyId);
    }

    /** Drops a tenancy's cached grants (call when its folder changes out of band). */
    public void evictSite(String siteId) {
        cache.invalidate(siteScope(siteId));
    }

    /** A site delete/restore rewrites its {@code permissions.yml} wholesale — drop its cached grants. */
    @EventListener
    @Order(KernelEventOrder.STORES)
    public void onSiteChanged(SiteChangedEvent event) {
        if (event.isWholesaleReplacement()) {
            evictSite(event.siteId());
        }
    }

    private GrantFile read(Path file) {
        try {
            return yaml.read(file, GrantFile.class).orElseGet(GrantFile::empty);
        } catch (IOException e) {
            throw new UncheckedIOException("reading grant file " + file, e);
        }
    }

    private void write(Path file, GrantFile grants) {
        try {
            yaml.write(file, grants);
        } catch (IOException e) {
            throw new UncheckedIOException("writing grant file " + file, e);
        }
    }

    private Path appFile() {
        return layout.external().resolve(APP_FILE);
    }

    private Path siteFile(String siteId) {
        return layout.siteDir(siteId).resolve(SITE_FILE); // siteDir validates the id
    }

    private static String siteScope(String siteId) {
        return "site|" + siteId;
    }
}
