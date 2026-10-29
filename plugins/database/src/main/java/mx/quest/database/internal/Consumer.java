package mx.quest.database.internal;

import java.util.Objects;
import java.util.Set;
import mx.quest.database.spi.Scope;

/**
 * A consumer plugin as the database plugin knows it: the id its jar proved, its prefix, the scopes it declared and
 * where its scripts are.
 */
public record Consumer(String pluginId, String prefix, Set<Scope.Kind> scopes, ScriptSource scripts) {

    public Consumer {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(prefix, "prefix");
        scopes = Set.copyOf(scopes);
        Objects.requireNonNull(scripts, "scripts");
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("plugin '" + pluginId + "' declares no database scope");
        }
    }

    /** A consumer whose prefix follows from its id. */
    public static Consumer of(String pluginId, Set<Scope.Kind> scopes, ScriptSource scripts) {
        return new Consumer(pluginId, Names.pluginPrefix(pluginId), scopes, scripts);
    }
}
