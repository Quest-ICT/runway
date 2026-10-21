package mx.quest.runway.backup;

import java.io.IOException;

/**
 * A restore (or create-site-from-backup) refused because the archive's {@code manifest.yml} declares a
 * {@link BackupManifest#formatVersion() formatVersion} newer than this build reads — it was written by a later
 * Runway. Thrown <b>before</b> anything is swapped into place, so the live site is untouched. A subtype of
 * {@link IOException} so every caller that already handles "not a valid backup" still handles it; the consoles
 * catch it first to tell the operator what to do (upgrade, then restore).
 */
public final class UnsupportedBackupFormatException extends IOException {

    private final int found;
    private final int supported;

    public UnsupportedBackupFormatException(int found, int supported) {
        super("backup archive format " + found + " is newer than this Runway reads (up to " + supported
                + ") — restore it on the Runway release that wrote it, or a later one");
        this.found = found;
        this.supported = supported;
    }

    /** The format version the archive declares. */
    public int found() {
        return found;
    }

    /** The highest format version this build restores. */
    public int supported() {
        return supported;
    }
}
