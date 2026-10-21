package mx.quest.runway.plugin;

import java.nio.file.Path;

/**
 * A discovered plugin: its parsed {@link PluginDescriptor} and the install directory it was found in
 * ({@code external/plugins/<id>/}). Produced by discovery before any code is loaded; carried through
 * resolution, classloading and registration.
 *
 * @param descriptor the parsed {@code plugin.yml}
 * @param installDir the plugin's own directory (holds its jar(s), and optional {@code lib/ templates/ static/ i18n/})
 */
public record LoadedPlugin(PluginDescriptor descriptor, Path installDir) {

    /** The plugin id (the descriptor's, = the install-dir name). */
    public String id() {
        return descriptor.id();
    }

    /** Whether this is a code-less library/asset provider (no {@code mainClass}). */
    public boolean isLibraryOnly() {
        return descriptor.isLibraryOnly();
    }
}
