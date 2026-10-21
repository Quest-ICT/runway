package mx.quest.runway.storage;

import java.nio.file.Path;

/**
 * Startup refusal raised by {@link DataLayout#verify} when the external folder's layout marker
 * ({@code layout.yml}) declares a layout this build cannot safely use: a <b>newer</b> one (written by a later
 * Runway — downgrade protection) or an <b>unreadable</b> one. {@link DataLayoutFailureAnalyzer} turns it into the
 * operator-facing "APPLICATION FAILED TO START" report.
 */
public final class DataLayoutException extends IllegalStateException {

    private final transient Path marker;
    private final boolean newer;

    private DataLayoutException(String message, Path marker, boolean newer) {
        super(message);
        this.marker = marker;
        this.newer = newer;
    }

    static DataLayoutException newer(Path marker, int found, int supported) {
        return new DataLayoutException("The data folder's layout marker " + marker + " declares layout version "
                + found + ", but this Runway build supports up to version " + supported + ". The folder was written "
                + "by a newer Runway release; this older one refuses to start rather than write into a layout it "
                + "does not understand.", marker, true);
    }

    static DataLayoutException malformed(Path marker, String why) {
        return new DataLayoutException("The data folder's layout marker " + marker + " cannot be read: " + why
                + ". Runway refuses to start rather than guess which layout the folder is in.", marker, false);
    }

    /** The marker file that caused the refusal. */
    public Path marker() {
        return marker;
    }

    /** True when the marker declares a newer layout; false when it is unreadable or malformed. */
    public boolean isNewer() {
        return newer;
    }
}
