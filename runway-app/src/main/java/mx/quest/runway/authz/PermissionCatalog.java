package mx.quest.runway.authz;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mx.quest.runway.spi.model.Realm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The declared permission registry — the single source of truth for "does this permission exist" and
 * "may it be granted". Authorization is <b>fail-closed</b> on this catalog: an unknown permission key is
 * undeclared, so a check for it can never pass (the access check denies it).
 *
 * <p>Every declared permission is either <b>grantable</b> (assignable to roles/users — a holder passes a
 * check via the effective-permission union) or <b>privileged</b> (master/wildcard-only: declared and
 * recognized, but never assignable, so only the master or a wildcard admin passes it). The two sets are
 * disjoint; their union is every declared permission.
 *
 * <p>Plugins contribute further <em>grantable</em> permissions (namespaced {@code <pluginId>.<id>}); the
 * {@code @Autowired} constructor seeds the core baseline then merges them through the same fail-fast
 * {@link #declare} guard, so a plugin permission colliding with a core key (or another plugin's) <b>fails
 * startup</b>. {@code mayAccess} and the bounded-grant admin service are untouched. Thread-safe and immutable
 * after construction.
 */
@Component
public class PermissionCatalog {

    private final Map<String, Permission> byKey = new LinkedHashMap<>();
    private final Set<Permission> grantable = new LinkedHashSet<>();
    private final Set<Permission> privileged = new LinkedHashSet<>();
    private final Map<String, Set<String>> defaultsByRole;

    /** Kernel-baseline only (used by tests and as the no-plugin default). */
    public PermissionCatalog() {
        seedKernelBaseline();
        this.defaultsByRole = Map.of();
    }

    /** Kernel baseline plus the grantable permissions plugins declared (merged with the same dup-key guard). */
    @Autowired
    public PermissionCatalog(PluginPermissionContributions pluginPermissions) {
        seedKernelBaseline();
        for (Permission permission : pluginPermissions.grantable()) {
            grant(permission); // declare() fails fast on a collision with a core or another plugin's key
        }
        this.defaultsByRole = pluginPermissions.defaultsByRole();
    }

    /**
     * Role key → the grantable plugin-permission keys that default to it. The {@code mayAccess} fold grants
     * these to a role unless a scope's {@code roleRevokes} delta removes them. Empty for a bare
     * kernel (no plugin declares a default).
     */
    public Set<String> defaultsForRole(String roleName) {
        return defaultsByRole.getOrDefault(roleName, Set.of());
    }

    /** Every role key that has at least one plugin-permission default (for the permissions matrix). */
    public Set<String> rolesWithDefaults() {
        return Set.copyOf(defaultsByRole.keySet());
    }

    private void seedKernelBaseline() {
        grant(Permissions.EDIT_PAGES, Permissions.DISABLE_PAGES, Permissions.CONFIGURE_SITE,
                Permissions.MAINTENANCE_MODE, Permissions.MANAGE_USERS, Permissions.DELETE_USERS,
                Permissions.MANAGE_DOCUMENTS, Permissions.MANAGE_BACKUPS, Permissions.APPROVE_PAGES,
                Permissions.VIEW_SITE_INSIGHTS,
                Permissions.VIEW_APP_DASHBOARD, Permissions.VIEW_TENANCIES, Permissions.MANAGE_STYLES,
                Permissions.MANAGE_SITE_HOSTS, Permissions.VIEW_BANDWIDTH, Permissions.VIEW_AUDIT);
        reserve(Permissions.MANAGE_TENANCIES, Permissions.MANAGE_TENANT_MASTERS, Permissions.MANAGE_APP_USERS,
                Permissions.CONFIGURE_PLATFORM, Permissions.MANAGE_PLUGINS, Permissions.MANAGE_PROTECTION);
    }

    /** The declared permission for {@code key}, or empty when undeclared (a check for it fails closed). */
    public Optional<Permission> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** Whether {@code key} names a declared permission. */
    public boolean isDeclared(String key) {
        return byKey.containsKey(key);
    }

    /** Whether {@code permission} may be assigned to a role or user (false for master/wildcard-only ones). */
    public boolean isGrantable(Permission permission) {
        return grantable.contains(permission);
    }

    /** The grantable permissions in {@code realm} — the assignable set an admin can hand out. */
    public Set<Permission> grantable(Realm realm) {
        Set<Permission> inRealm = new LinkedHashSet<>();
        for (Permission permission : grantable) {
            if (permission.realm() == realm) {
                inRealm.add(permission);
            }
        }
        return inRealm;
    }

    /** Every declared permission (grantable + master/wildcard-only). */
    public Set<Permission> all() {
        return Set.copyOf(byKey.values());
    }

    private void grant(Permission... permissions) {
        for (Permission permission : permissions) {
            declare(permission);
            grantable.add(permission);
        }
    }

    private void reserve(Permission... permissions) {
        for (Permission permission : permissions) {
            declare(permission);
            privileged.add(permission);
        }
    }

    private void declare(Permission permission) {
        if (byKey.putIfAbsent(permission.key(), permission) != null) {
            throw new IllegalStateException("duplicate permission key: " + permission.key());
        }
    }
}
