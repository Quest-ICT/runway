package mx.quest.runway.spi.model;

import java.io.IOException;
import java.io.OutputStream;

/**
 * A host-provided sink a {@link mx.quest.runway.spi.BackupParticipant} writes its per-site
 * data into during a backup. The host owns the archive and scopes every entry under the
 * participant's own namespace, so participants cannot collide or read each other's data.
 *
 * <p>The participant writes one or more named entries and is responsible for closing each
 * stream it opens (use try-with-resources). Closing a per-entry stream finalizes that entry
 * only; the host manages the lifecycle of the outer archive (and the safe handling that keeps
 * a multi-entry container from being closed prematurely).
 */
public interface BackupWriter {

    /**
     * Opens a stream for one named entry of this participant's backup data.
     *
     * @param entry a participant-relative entry name (for example {@code "slides.yml"});
     *              never {@code null}
     * @return an open stream to write the entry's bytes; the caller must close it
     * @throws IOException if the entry cannot be opened for writing
     */
    OutputStream put(String entry) throws IOException;
}
