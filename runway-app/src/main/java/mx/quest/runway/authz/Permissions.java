package mx.quest.runway.authz;

import mx.quest.runway.spi.model.Realm;

/**
 * The core (kernel-declared) permission keys — the baseline catalog the {@link PermissionCatalog} seeds.
 * Each is realm-tagged. Two classes (see the catalog for the distinction):
 *
 * <ul>
 *   <li><b>Grantable</b> — assignable to roles and users (the tenant baseline + the application
 *       grantable set); a holder passes a {@code mayAccess} check for it via the effective-permission
 *       union.</li>
 *   <li><b>Master/wildcard-only</b> — declared (so they are auditable and recognized) but <b>never
 *       grantable</b>: an ordinary user can never be assigned them, so only the master or a wildcard
 *       admin passes them (via the access-precedence short-circuit, not the union). They name the
 *       privileged platform operations.</li>
 * </ul>
 *
 * <p>Plugins add further grantable permissions (control points); this is only the kernel seed.
 */
public final class Permissions {

    // --- TENANT realm — grantable baseline ---
    public static final Permission EDIT_PAGES = tenant("EDIT_PAGES");
    public static final Permission DISABLE_PAGES = tenant("DISABLE_PAGES");
    public static final Permission CONFIGURE_SITE = tenant("CONFIGURE_SITE");
    public static final Permission MAINTENANCE_MODE = tenant("MAINTENANCE_MODE");
    public static final Permission MANAGE_USERS = tenant("MANAGE_USERS");
    public static final Permission DELETE_USERS = tenant("DELETE_USERS");
    public static final Permission MANAGE_DOCUMENTS = tenant("MANAGE_DOCUMENTS");
    /** Create / download / delete the site's backups. Restore is a separate, master-only destructive action. */
    public static final Permission MANAGE_BACKUPS = tenant("MANAGE_BACKUPS");
    /** Reviewer permission gating draft→review→publish when a site opts into the editorial workflow. */
    public static final Permission APPROVE_PAGES = tenant("APPROVE_PAGES");
    /**
     * Read the site's insights report ({@code /__admin/insights}: top 404s and most-visited pages, aggregated from
     * the access log). Grantable but default-granted to no role — like the logs screen, only the site's master and
     * wildcard admins see it until someone delegates it. The report is an aggregate of paths and counts; it never
     * exposes a client IP, which is why it can be delegated where the raw logs cannot.
     */
    public static final Permission VIEW_SITE_INSIGHTS = tenant("VIEW_SITE_INSIGHTS");

    // --- APPLICATION realm — grantable ---
    public static final Permission VIEW_APP_DASHBOARD = app("VIEW_APP_DASHBOARD");
    public static final Permission VIEW_TENANCIES = app("VIEW_TENANCIES");
    public static final Permission MANAGE_STYLES = app("MANAGE_STYLES");
    /**
     * Edit a site's routing hosts (the {@link mx.quest.runway.site.Site#hosts} set) from the app Sites screen.
     * Grantable — so an operator can delegate host management to a role or user without handing over the
     * master-only tenancy lifecycle (create / suspend / delete stay {@link #MANAGE_TENANCIES}). A host change is
     * a routing edit only; it can never move data or cross tenant isolation.
     */
    public static final Permission MANAGE_SITE_HOSTS = app("MANAGE_SITE_HOSTS");
    public static final Permission VIEW_BANDWIDTH = app("VIEW_BANDWIDTH");
    public static final Permission VIEW_AUDIT = app("VIEW_AUDIT");

    // --- APPLICATION realm — master/wildcard-only (declared, NOT grantable) ---
    public static final Permission MANAGE_TENANCIES = app("MANAGE_TENANCIES");
    public static final Permission MANAGE_TENANT_MASTERS = app("MANAGE_TENANT_MASTERS");
    public static final Permission MANAGE_APP_USERS = app("MANAGE_APP_USERS");
    public static final Permission CONFIGURE_PLATFORM = app("CONFIGURE_PLATFORM");
    public static final Permission MANAGE_PLUGINS = app("MANAGE_PLUGINS");
    public static final Permission MANAGE_PROTECTION = app("MANAGE_PROTECTION");

    private Permissions() {
    }

    private static Permission tenant(String key) {
        return new Permission(key, Realm.TENANT);
    }

    private static Permission app(String key) {
        return new Permission(key, Realm.APPLICATION);
    }
}
