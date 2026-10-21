package mx.quest.runway.plugin;

import mx.quest.runway.spi.model.Realm;

/**
 * An admin-menu contribution — a realm-tagged, region-targeted, ordered link, with an optional
 * permission that hides the entry from users who fail it (UX only — the controller still enforces). The plugin
 * loader parses and models it; {@code MenuRegistry} renders it into the operator/tenant topbar.
 *
 * @param realm      the realm whose console shows it ({@code application} | {@code tenant}) — required
 * @param region     the target region (e.g. {@code menu} or {@code user})
 * @param order      the sort order within the region (ties broken by plugin id then declaration order)
 * @param label      an i18n label key
 * @param path       the link target
 * @param permission an optional permission id gating visibility ({@code null} = no gate)
 * @param icon       an optional Font Awesome icon class for the entry (e.g. {@code fa-solid fa-feather-pointed});
 *                   {@code null}/blank falls back to the generic plugin glyph — see {@code MenuRegistry}
 */
public record PluginMenuDecl(Realm realm, String region, int order, String label, String path, String permission,
                             String icon) {
}
