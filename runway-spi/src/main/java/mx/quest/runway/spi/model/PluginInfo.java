package mx.quest.runway.spi.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Who the calling plugin is, as the host loaded it. Injectable into plugin code (it is bound to the plugin
 * whose jar the injecting class came from — see {@link mx.quest.runway.spi.service.PluginSettings}) and handed
 * to {@link mx.quest.runway.spi.service.PluginLifecycle#started(PluginInfo)}.
 *
 * @param id         the plugin id from {@code plugin.yml} (= its install folder name)
 * @param version    the plugin version from {@code plugin.yml} (plain {@code MAJOR.MINOR.PATCH})
 * @param installDir the plugin's install folder ({@code external/plugins/<id>/}) — read-only by convention: it is
 *                   replaced wholesale on upgrade, so a plugin keeps nothing it writes there
 * @since SPI 0.4.0
 */
public record PluginInfo(String id, String version, Path installDir) {

    public PluginInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(installDir, "installDir");
    }
}
