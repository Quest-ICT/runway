package mx.quest.runway.console;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import mx.quest.runway.authz.AuthorizationService;
import mx.quest.runway.authz.Permission;
import mx.quest.runway.authz.Permissions;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.spi.model.Realm;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Which of the <b>kernel's own</b> topbar entries the signed-in operator may see, so the console never offers a
 * link that answers {@code 403}. Plugin entries already work this way ({@link MenuRegistry} hides an entry whose
 * declared {@code permission} fails); this does the same for the kernel's fixed menu.
 *
 * <h2>One gate per entry — the same check its route makes</h2>
 * Each entry names the check the route behind it enforces, and is evaluated with the same code: a grantable
 * permission through {@link AuthorizationService#mayAccess} (application permissions without a tenancy, tenant
 * permissions in the bound site — exactly what {@code @authz.may('…')} does), the platform gate through
 * {@link PlatformAuthority#isPlatformAdmin}, the site-administrator gate through
 * {@link TenantAuthority#isTenantAdmin}. Entries whose route only requires the realm's sign-in are always shown.
 * When a route's gate changes, its row here must change with it; {@code ConsoleNavigationVisibilityTest}
 * requests every route as an under-privileged operator and fails if a visible entry answers {@code 403} or a
 * hidden one does not.
 *
 * <h2>Groups</h2>
 * A dropdown group is shown when at least one of its kernel entries is visible <em>or</em> a plugin contributes an
 * entry to it — an empty dropdown would be a dead control.
 *
 * <p>UX only: hiding an entry grants or removes nothing; the route still enforces its own gate.
 */
@Component
public class ConsoleNavigation {

    /** How an entry's route is gated. */
    enum Gate { SIGNED_IN, PLATFORM_ADMIN, TENANT_ADMIN, PERMISSION }

    /**
     * One kernel menu entry.
     *
     * @param key     the model key the topbar reads ({@code nav['key']})
     * @param group   the dropdown group it sits in, or {@code null} for a top-level item
     * @param path    the route it links to (for the consistency test and for readers)
     * @param gate    how the route is gated
     * @param anyOf   for {@link Gate#PERMISSION}: the permissions, any one of which opens the route
     */
    record Entry(String key, String group, String path, Gate gate, List<Permission> anyOf) {

        static Entry open(String key, String group, String path) {
            return new Entry(key, group, path, Gate.SIGNED_IN, List.of());
        }

        static Entry gated(String key, String group, String path, Gate gate) {
            return new Entry(key, group, path, gate, List.of());
        }

        static Entry permission(String key, String group, String path, Permission... anyOf) {
            return new Entry(key, group, path, Gate.PERMISSION, List.of(anyOf));
        }
    }

    /**
     * The application console's topbar ({@code app-topbar}). The dashboard is always offered: without
     * {@code VIEW_APP_DASHBOARD} it renders a minimal landing rather than answering {@code 403}, because it is where
     * sign-in lands. The Sites list opens with {@code VIEW_TENANCIES} or {@code MANAGE_SITE_HOSTS} (editing a
     * site's hosts happens on that list).
     */
    static final List<Entry> APP = List.of(
            Entry.open("dashboard", null, "/__app"),
            Entry.permission("sites", "sites", "/__app/sites", Permissions.VIEW_TENANCIES,
                    Permissions.MANAGE_SITE_HOSTS),
            Entry.gated("backups", "sites", "/__app/backups", Gate.PLATFORM_ADMIN),
            Entry.permission("site-templates", "sites", "/__app/site-templates", Permissions.MANAGE_STYLES),
            Entry.open("masters", "sites", "/__app/masters"),
            Entry.open("users", "access", "/__app/users"),
            Entry.gated("permissions", "access", "/__app/permissions", Gate.PLATFORM_ADMIN),
            Entry.permission("bandwidth", "activity", "/__app/bandwidth", Permissions.VIEW_BANDWIDTH),
            Entry.permission("logs", "activity", "/__app/logs", Permissions.VIEW_AUDIT),
            Entry.open("plugins", null, "/__app/plugins"),
            Entry.gated("settings", "settings", "/__app/settings", Gate.PLATFORM_ADMIN),
            Entry.gated("server-settings", "settings", "/__app/config/server", Gate.PLATFORM_ADMIN),
            Entry.gated("embed-hosts", "settings", "/__app/embed-hosts", Gate.PLATFORM_ADMIN),
            Entry.open("about", "settings", "/__app/about"));

    /** A site's console topbar ({@code admin-topbar}). */
    static final List<Entry> TENANT = List.of(
            Entry.open("dashboard", null, "/__admin"),
            Entry.permission("pages", "resources", "/__admin/pages", Permissions.EDIT_PAGES, Permissions.DISABLE_PAGES),
            Entry.permission("review", "resources", "/__admin/review", Permissions.APPROVE_PAGES),
            Entry.permission("fragments", "resources", "/__admin/fragments", Permissions.CONFIGURE_SITE),
            Entry.permission("widgets", "resources", "/__admin/widgets", Permissions.CONFIGURE_SITE),
            Entry.permission("documents", "resources", "/__admin/documents", Permissions.MANAGE_DOCUMENTS),
            Entry.permission("static", "resources", "/__admin/static", Permissions.CONFIGURE_SITE),
            Entry.permission("messages", "resources", "/__admin/messages", Permissions.CONFIGURE_SITE),
            Entry.permission("users", "access", "/__admin/users", Permissions.MANAGE_USERS, Permissions.DELETE_USERS),
            Entry.gated("permissions", "access", "/__admin/permissions", Gate.TENANT_ADMIN),
            Entry.permission("insights", "activity", "/__admin/insights", Permissions.VIEW_SITE_INSIGHTS),
            Entry.gated("bandwidth", "activity", "/__admin/bandwidth", Gate.TENANT_ADMIN),
            Entry.gated("logs", "activity", "/__admin/logs", Gate.TENANT_ADMIN),
            Entry.permission("config", "settings", "/__admin/config", Permissions.CONFIGURE_SITE),
            Entry.permission("seo", "settings", "/__admin/seo", Permissions.CONFIGURE_SITE),
            Entry.permission("icons", "settings", "/__admin/icons", Permissions.CONFIGURE_SITE),
            Entry.permission("manifest", "settings", "/__admin/manifest", Permissions.CONFIGURE_SITE),
            Entry.permission("backups", "settings", "/__admin/backups", Permissions.MANAGE_BACKUPS),
            Entry.permission("maintenance", "settings", "/__admin/maintenance", Permissions.MAINTENANCE_MODE));

    private final AuthorizationService authorization;
    private final PlatformAuthority platformAuthority;
    private final TenantAuthority tenantAuthority;

    public ConsoleNavigation(AuthorizationService authorization, PlatformAuthority platformAuthority,
                             TenantAuthority tenantAuthority) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.platformAuthority = Objects.requireNonNull(platformAuthority, "platformAuthority");
        this.tenantAuthority = Objects.requireNonNull(tenantAuthority, "tenantAuthority");
    }

    /**
     * The visibility map the topbar reads: one boolean per entry key, plus {@code grp.<group>} per dropdown group.
     * Every key of the realm's table is present, so a template lookup never sees {@code null}.
     *
     * @param realm                  which console is rendering
     * @param authentication         the signed-in operator ({@code null} ⇒ only {@link Gate#SIGNED_IN} entries)
     * @param tenancyId              the bound site on the tenant console ({@code null} on the application console)
     * @param groupsWithPluginEntries the groups a plugin contributes at least one visible entry to
     */
    public Map<String, Boolean> visibility(Realm realm, Authentication authentication, String tenancyId,
                                           Set<String> groupsWithPluginEntries) {
        List<Entry> entries = realm == Realm.APPLICATION ? APP : TENANT;
        RunwayUserDetails principal = authentication != null
                && authentication.getPrincipal() instanceof RunwayUserDetails details ? details : null;
        Map<String, Boolean> visible = new LinkedHashMap<>();
        for (Entry entry : entries) {
            boolean shown = allows(entry, authentication, principal, tenancyId);
            visible.put(entry.key(), shown);
            if (entry.group() != null) {
                visible.merge("grp." + entry.group(), shown, Boolean::logicalOr);
            }
        }
        for (String group : groupsWithPluginEntries) {
            if (visible.containsKey("grp." + group)) {
                visible.put("grp." + group, true);
            }
        }
        return visible;
    }

    private boolean allows(Entry entry, Authentication authentication, RunwayUserDetails principal,
                           String tenancyId) {
        return switch (entry.gate()) {
            case SIGNED_IN -> true;
            case PLATFORM_ADMIN -> platformAuthority.isPlatformAdmin(authentication);
            case TENANT_ADMIN -> tenancyId != null && tenantAuthority.isTenantAdmin(authentication, tenancyId);
            case PERMISSION -> principal != null && entry.anyOf().stream().anyMatch(permission ->
                    authorization.mayAccess(principal, permission,
                            permission.realm() == Realm.APPLICATION ? null : tenancyId));
        };
    }
}
