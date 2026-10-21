package mx.quest.runway.plugin;

/**
 * Published when a tenancy's per-plugin config is saved — kernel-internal: the per-tenant settings cache evicts on it,
 * the cluster relay forwards it, and the SPI event bridge turns it into a
 * {@link mx.quest.runway.spi.model.SettingsChange} for the plugin's own
 * {@link mx.quest.runway.spi.service.SiteEventListener}, so the plugin can react <b>immediately</b> (no restart).
 * (Until SPI 1.0.0 plugins listened to this host type directly; one that links it is now refused.)
 *
 * @param siteId   the tenancy whose config changed
 * @param pluginId the plugin whose per-tenant config changed
 */
public record SitePluginConfigChangedEvent(String siteId, String pluginId) {
}
