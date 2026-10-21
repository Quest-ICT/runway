package mx.quest.runway.console;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mx.quest.runway.authz.AuthorizationService;
import mx.quest.runway.authz.Permission;
import mx.quest.runway.authz.PermissionCatalog;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.PluginCardDecl;
import mx.quest.runway.plugin.RunningPlugins;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.spi.model.Realm;
import org.springframework.stereotype.Component;

/**
 * Collects the dashboard cards plugins contribute ({@code dashboardCards:} in the descriptor) for a realm and
 * principal, resolving each to its {@code __plugin__/<id>/<fragment>} template so the console dashboard can pull
 * it (via {@link PluginTemplateResolver}). Realm-filtered, ordered ({@code order} → plugin id), and
 * permission-gated exactly like {@link MenuRegistry} — a card naming a permission the principal lacks is
 * hidden; UX only (the fragment's own data still comes from the plugin's beans).
 */
@Component
public class DashboardCardRegistry {

    private final RunningPlugins runningPlugins;
    private final AuthorizationService authorization;
    private final PermissionCatalog catalog;

    public DashboardCardRegistry(RunningPlugins runningPlugins, AuthorizationService authorization,
                                 PermissionCatalog catalog) {
        this.runningPlugins = runningPlugins;
        this.authorization = authorization;
        this.catalog = catalog;
    }

    /** A resolved card: the template the dashboard pulls, its order, and the contributing plugin. */
    public record Card(String template, int order, String pluginId) {
    }

    /** The visible dashboard cards for {@code principal} in {@code realm} (+ bound {@code tenancyId}). */
    public List<Card> cardsFor(Realm realm, RunwayUserDetails principal, String tenancyId) {
        List<Card> cards = new ArrayList<>();
        for (LoadedPlugin plugin : runningPlugins.installed()) {
            for (PluginCardDecl decl : plugin.descriptor().dashboardCards()) {
                if (decl.realm() != realm || !visible(decl, principal, tenancyId)) {
                    continue;
                }
                cards.add(new Card(PluginTemplateResolver.PREFIX + plugin.id() + "/" + decl.fragment(),
                        decl.order(), plugin.id()));
            }
        }
        cards.sort(Comparator.comparingInt(Card::order).thenComparing(Card::pluginId));
        return List.copyOf(cards);
    }

    private boolean visible(PluginCardDecl decl, RunwayUserDetails principal, String tenancyId) {
        if (decl.permission() == null || decl.permission().isBlank()) {
            return true;
        }
        if (principal == null || principal.realm() != decl.realm()) {
            return false;
        }
        Permission permission = catalog.find(decl.permission()).orElse(null);
        return permission != null && authorization.mayAccess(principal, permission, tenancyId);
    }
}
