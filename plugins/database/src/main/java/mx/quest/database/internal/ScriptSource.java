package mx.quest.database.internal;

import java.io.IOException;
import java.util.Optional;

/** A consumer plugin's migration scripts, by entry name ({@code db/<pluginId>/<dialect>/<app|site>/…}). */
public interface ScriptSource {

    /** The plugin the scripts belong to. */
    String pluginId();

    /** An entry's bytes, or empty when the entry does not exist. */
    Optional<byte[]> read(String entry) throws IOException;
}
