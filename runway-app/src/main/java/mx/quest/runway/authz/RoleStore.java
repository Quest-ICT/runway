package mx.quest.runway.authz;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StoreCache;
import mx.quest.runway.storage.YamlStore;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Reads and writes the per-realm role files and exposes them as validated {@link RoleGraph}s:
 * {@code external/app-roles.yml} (the platform-wide application roles) and {@code sites/<id>/roles.yml}
 * (each tenancy's own roles, which travel in its backup and are invisible across tenancies). The graph is
 * cached per scope and rebuilt on save.
 *
 * <p>Loading a graph is <b>fail-closed</b>: {@link RoleGraph#of} validates the file (seal, missing refs,
 * cycles) and throws on any violation, so an invalid file cannot produce a usable graph. The store is part
 * of the trusted computing base — these files live under the external root / inside the tenant folder,
 * never under a web-served path.
 */
@Component
public class RoleStore {

    private static final String APP_FILE = "app-roles.yml";
    private static final String SITE_FILE = "roles.yml";
    private static final String APP_SCOPE = "app";

    private final StorageLayout layout;
    private final YamlStore yaml;
    private final StoreCache<String, RoleGraph> graphs = new StoreCache<>(512);

    public RoleStore(StorageLayout layout, YamlStore yaml) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.yaml = Objects.requireNonNull(yaml, "yaml");
    }

    /** The application-realm role graph (validated; throws if the file is invalid). */
    public RoleGraph appRoles() {
        return graphs.get(APP_SCOPE, key -> RoleGraph.of(read(appFile())));
    }

    /** The tenancy's role graph (validated; throws if the file is invalid or the id is unsafe). */
    public RoleGraph siteRoles(String siteId) {
        return graphs.get(siteScope(siteId), key -> RoleGraph.of(read(siteFile(siteId))));
    }

    /** The raw application role list (for editing — the admin service reads, mutates, and re-saves it). */
    public List<Role> appRoleList() {
        return read(appFile());
    }

    /** The raw role list for a tenancy (for editing). */
    public List<Role> siteRoleList(String siteId) {
        return read(siteFile(siteId));
    }

    /**
     * Atomically replaces the application roles and rebuilds the cached graph. Validates first (rejected
     * on save as well as on load), so an invalid graph is never persisted.
     *
     * @throws IllegalStateException if the roles form an invalid graph (seal, missing ref, cycle)
     */
    public void saveAppRoles(List<Role> roles) {
        RoleGraph.of(roles); // validate before persisting
        write(appFile(), roles);
        graphs.invalidate(APP_SCOPE);
    }

    /**
     * Atomically replaces a tenancy's roles and rebuilds the cached graph (validated first).
     *
     * @throws IllegalStateException if the roles form an invalid graph
     */
    public void saveSiteRoles(String siteId, List<Role> roles) {
        RoleGraph.of(roles); // validate before persisting
        write(siteFile(siteId), roles);
        graphs.invalidate(siteScope(siteId));
    }

    /** Drops a tenancy's cached graph (call when its folder changes out of band). */
    public void evictSite(String siteId) {
        graphs.invalidate(siteScope(siteId));
    }

    /** A site delete/restore rewrites its {@code roles.yml} wholesale — drop its cached role graph. */
    @EventListener
    @Order(KernelEventOrder.STORES)
    public void onSiteChanged(SiteChangedEvent event) {
        if (event.isWholesaleReplacement()) {
            evictSite(event.siteId());
        }
    }

    private List<Role> read(Path file) {
        try {
            return yaml.read(file, RoleFile.class).orElseGet(RoleFile::empty).roles();
        } catch (IOException e) {
            throw new UncheckedIOException("reading role file " + file, e);
        }
    }

    private void write(Path file, List<Role> roles) {
        try {
            yaml.write(file, new RoleFile(roles));
        } catch (IOException e) {
            throw new UncheckedIOException("writing role file " + file, e);
        }
    }

    private Path appFile() {
        return layout.external().resolve(APP_FILE);
    }

    private Path siteFile(String siteId) {
        return layout.siteDir(siteId).resolve(SITE_FILE); // siteDir validates the id
    }

    private static String siteScope(String siteId) {
        return "site|" + siteId; // printable separator
    }
}
