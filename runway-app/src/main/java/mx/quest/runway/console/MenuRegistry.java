package mx.quest.runway.console;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mx.quest.runway.authz.AuthorizationService;
import mx.quest.runway.authz.Permission;
import mx.quest.runway.authz.PermissionCatalog;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginMenuDecl;
import mx.quest.runway.plugin.RunningPlugins;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.spi.model.Realm;
import org.springframework.stereotype.Component;

/**
 * Collects the admin-menu links plugins contribute (a plugin never builds its own admin page — it declares a
 * {@code menu:} entry in its descriptor) and hands the operator console the ones a given principal
 * may see. The kernel knows only this generic collection point; there is zero plugin-specific code in the
 * chrome.
 *
 * <p>Filtered by <b>realm</b> (an application menu never shows in the tenant console or vice versa) and by
 * <b>region</b> (the topbar group a plugin targets: {@code menu} = top-level nav, {@code settings} /
 * {@code activity} = the matching dropdown), ordered ({@code order} → plugin id → declaration order), and gated by
 * <b>permission visibility</b>: an entry naming a permission is hidden from a principal who fails {@code mayAccess}
 * on it (an undeclared permission fails closed). This is UX only — the plugin's own controller still enforces;
 * hiding merely declutters.
 */
@Component
public class MenuRegistry {

    /** The default region for a menu declaration that leaves {@code region} unset — the top-level nav bar. */
    static final String DEFAULT_REGION = "menu";

    private final RunningPlugins runningPlugins;
    private final AuthorizationService authorization;
    private final PermissionCatalog catalog;

    public MenuRegistry(RunningPlugins runningPlugins, AuthorizationService authorization, PermissionCatalog catalog) {
        this.runningPlugins = runningPlugins;
        this.authorization = authorization;
        this.catalog = catalog;
    }

    /** The Font Awesome class a plugin menu entry falls back to when it declares no {@code icon}. */
    static final String DEFAULT_MENU_ICON = "fa-solid fa-puzzle-piece";

    /** One resolved menu link for the topbar. {@code icon} is always a non-blank Font Awesome class. */
    public record MenuEntry(String label, String path, int order, String pluginId, String icon) {
    }

    /**
     * The visible <b>top-level</b> ({@code region: menu}) menu entries for {@code principal} in {@code realm}
     * (+ bound {@code tenancyId} for tenant). Shorthand for {@link #menuFor(Realm, String, RunwayUserDetails, String)}
     * with the default region. A {@code null} principal (or one whose realm differs) sees none of the
     * permission-gated entries.
     */
    public List<MenuEntry> menuFor(Realm realm, RunwayUserDetails principal, String tenancyId) {
        return menuFor(realm, DEFAULT_REGION, principal, tenancyId);
    }

    /**
     * The visible menu entries a plugin targets at {@code region} for {@code principal} in {@code realm} (+ bound
     * {@code tenancyId} for tenant). The region is the topbar group the entry renders into: {@code menu}
     * for the top-level nav, {@code settings} / {@code activity} for the matching dropdown, so a plugin can drop its
     * own console screen into a group instead of adding a top-level item. A declaration that leaves {@code region}
     * blank defaults to {@link #DEFAULT_REGION} — a plugin authored before region targeting keeps its top-level
     * placement. Filtered by realm + region, gated by permission visibility, and ordered ({@code order} → plugin id).
     */
    public List<MenuEntry> menuFor(Realm realm, String region, RunwayUserDetails principal, String tenancyId) {
        String target = normalizeRegion(region);
        List<MenuEntry> entries = new ArrayList<>();
        for (LoadedPlugin plugin : runningPlugins.installed()) {
            for (PluginMenuDecl decl : plugin.descriptor().menu()) {
                if (decl.realm() != realm || !normalizeRegion(decl.region()).equals(target)) {
                    continue;
                }
                if (!visible(decl, principal, tenancyId)) {
                    continue;
                }
                // The label is resolved as an i18n key in the topbar (#{label}); coalesce a null/blank label to
                // the plugin id so a mis-authored menu entry degrades to a harmless literal instead of a render error.
                String label = (decl.label() == null || decl.label().isBlank()) ? plugin.id() : decl.label();
                String icon = (decl.icon() == null || decl.icon().isBlank()) ? DEFAULT_MENU_ICON : decl.icon().strip();
                entries.add(new MenuEntry(label, decl.path(), decl.order(), plugin.id(), icon));
            }
        }
        entries.sort(Comparator.comparingInt(MenuEntry::order).thenComparing(MenuEntry::pluginId));
        return List.copyOf(entries);
    }

    /** A blank/null region coalesces to {@link #DEFAULT_REGION} so an unset region means the top-level nav. */
    private static String normalizeRegion(String region) {
        return (region == null || region.isBlank()) ? DEFAULT_REGION : region;
    }

    private boolean visible(PluginMenuDecl decl, RunwayUserDetails principal, String tenancyId) {
        if (decl.permission() == null || decl.permission().isBlank()) {
            return true; // no gate → always visible
        }
        if (principal == null || principal.realm() != decl.realm()) {
            return false;
        }
        Permission permission = catalog.find(decl.permission()).orElse(null);
        return permission != null && authorization.mayAccess(principal, permission, tenancyId);
    }

    /**
     * The plugin <b>settings</b> entries for the console's Settings menu — one per running plugin that declares a
     * settings schema for {@code realm} ({@code siteSettings:} for TENANT, {@code settings:} for APPLICATION),
     * each linking to the kernel's generic settings form ({@code /__admin/plugins/<id>/settings} or
     * {@code /__app/plugins/<id>/settings}). The {@link MenuEntry#label()} is the plugin's <b>name</b> (a literal,
     * rendered as plain text — not an i18n key like {@link #menuFor}'s). Gated by the plugin's reserved
     * {@code settings} control point when it declares one (undeclared ⇒ visible; the controller still enforces),
     * then ordered by name. This replaces the old standalone "Site settings" landing page.
     */
    public List<MenuEntry> settingsFor(Realm realm, RunwayUserDetails principal, String tenancyId) {
        String prefix = realm == Realm.APPLICATION ? "/__app/plugins/" : "/__admin/plugins/";
        List<MenuEntry> entries = new ArrayList<>();
        for (LoadedPlugin plugin : runningPlugins.installed()) {
            boolean hasSchema = realm == Realm.APPLICATION
                    ? !plugin.descriptor().settings().isEmpty()
                    : !plugin.descriptor().siteSettings().isEmpty();
            if (!hasSchema || !settingsVisible(plugin.id(), realm, principal, tenancyId)) {
                continue;
            }
            String name = plugin.descriptor().name();
            String label = (name == null || name.isBlank()) ? plugin.id() : name;
            entries.add(new MenuEntry(label, prefix + plugin.id() + "/settings", 0, plugin.id(), DEFAULT_MENU_ICON));
        }
        entries.sort(Comparator.comparing(MenuEntry::label, String.CASE_INSENSITIVE_ORDER).thenComparing(MenuEntry::pluginId));
        return List.copyOf(entries);
    }

    private boolean settingsVisible(String pluginId, Realm realm, RunwayUserDetails principal, String tenancyId) {
        Permission permission = catalog.find(mx.quest.runway.storage.PathSafety.compoundKey(pluginId, "settings")).orElse(null);
        if (permission == null) {
            return true; // the plugin declares no settings control point → visible (the controller enforces)
        }
        if (principal == null || principal.realm() != realm) {
            return false;
        }
        return authorization.mayAccess(principal, permission, tenancyId);
    }
}
