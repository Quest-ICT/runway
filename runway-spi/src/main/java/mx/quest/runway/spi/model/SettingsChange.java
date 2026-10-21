package mx.quest.runway.spi.model;

import java.util.Objects;

/**
 * The listening plugin's own per-site settings were saved — what
 * {@link mx.quest.runway.spi.service.SiteEventListener#onSettingsChanged} receives. By the time it is delivered the
 * host's settings cache has been evicted, so {@link mx.quest.runway.spi.service.PluginSettings#site} returns the new
 * values. Immutable.
 *
 * @param siteId   the site whose settings changed (never {@code null})
 * @param pluginId the plugin whose settings changed — always the receiving listener's own plugin (never
 *                 {@code null})
 * @param local    {@code true} on the node where the save happened; {@code false} for a peer's replayed save
 * @since SPI 0.6.0
 */
public record SettingsChange(String siteId, String pluginId, boolean local) {

    public SettingsChange {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(pluginId, "pluginId");
    }
}
