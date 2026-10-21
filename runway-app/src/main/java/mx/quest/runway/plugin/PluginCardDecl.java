package mx.quest.runway.plugin;

import mx.quest.runway.spi.model.Realm;

/**
 * A dashboard-card contribution — a realm-tagged, ordered fragment placed in the console's Work
 * area, with an optional permission gating visibility. The plugin loader parses and models it;
 * {@code DashboardCardRegistry} renders it on the operator/tenant dashboard.
 *
 * @param realm      the realm whose dashboard shows it ({@code application} | {@code tenant}) — required
 * @param order      the sort order (ties broken by plugin id then declaration order)
 * @param fragment   the fragment name the plugin contributes
 * @param permission an optional permission id gating visibility ({@code null} = no gate)
 */
public record PluginCardDecl(Realm realm, int order, String fragment, String permission) {
}
